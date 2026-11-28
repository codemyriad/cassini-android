package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.asr.IncrementalTranscript
import io.codemyriad.cassini.core.audio.Limits
import io.codemyriad.cassini.core.audio.PcmAudio
import io.codemyriad.cassini.core.audio.PcmSource
import io.codemyriad.cassini.core.audio.WavDecoder
import io.codemyriad.cassini.core.checkpoint.CheckpointNames
import io.codemyriad.cassini.core.checkpoint.CheckpointOrigin
import io.codemyriad.cassini.core.checkpoint.CheckpointState
import io.codemyriad.cassini.core.checkpoint.DiarizerState
import io.codemyriad.cassini.core.checkpoint.EncoderState
import io.codemyriad.cassini.core.checkpoint.PrefixDecision
import io.codemyriad.cassini.core.checkpoint.RecognitionState
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.ModelPlan
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.core.speakers.PrintWindow
import io.codemyriad.cassini.core.speakers.SpeakerPrint
import io.codemyriad.cassini.core.speakers.SpeakerTurn
import io.codemyriad.cassini.core.speakers.Voice
import io.codemyriad.cassini.engine.asr.Parakeet
import io.codemyriad.cassini.engine.job.Adoption
import io.codemyriad.cassini.engine.job.AudioSource
import io.codemyriad.cassini.engine.job.CheckpointLoad
import io.codemyriad.cassini.engine.job.DiarizerFactory
import io.codemyriad.cassini.engine.job.DownloadProgress
import io.codemyriad.cassini.engine.job.ExtractedPrint
import io.codemyriad.cassini.engine.job.InstallOutcome
import io.codemyriad.cassini.engine.job.JobEngines
import io.codemyriad.cassini.engine.job.JobEnvironment
import io.codemyriad.cassini.engine.job.JobOutcome
import io.codemyriad.cassini.engine.job.JobStorage
import io.codemyriad.cassini.engine.job.JobVoices
import io.codemyriad.cassini.engine.job.ModelAccess
import io.codemyriad.cassini.engine.job.OpusSink
import io.codemyriad.cassini.engine.job.OpusSinkFactory
import io.codemyriad.cassini.engine.job.PrintExtractor
import io.codemyriad.cassini.engine.job.Recognizer
import io.codemyriad.cassini.engine.job.RecognizerFactory
import io.codemyriad.cassini.engine.job.StreamingDiarizer
import io.codemyriad.cassini.engine.job.TranscriptionJob
import io.codemyriad.cassini.engine.job.WorkFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class RecorderLivePrecomputerTest {
    @get:Rule val temp = TemporaryFolder()

    private val inference: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "test-live-precompute") }.asCoroutineDispatcher()

    @After fun closeInference() = inference.close()

    @Test fun streamingWavPrefixReaderMatchesWavDecoderBitForBit() {
        for (channels in listOf(1, 2)) {
            val frames = 16_000 * 3
            val wav = buildPcmWav(sampleRate = 16_000, channels = channels, frames = frames)
            val fullFile = temp.newFile("full-ch$channels.wav").apply { writeBytes(wav) }
            val expected = RandomAccessFile(fullFile, "r").use {
                WavDecoder.decodeWav(it, retainPcm = true, included = false)
            }

            val partFile = temp.newFile("part-ch$channels.wav.part")
            val staleHeader = buildWavHeader(dataBytes = 1024, sampleRate = 16_000, channels = channels)
            partFile.writeBytes(staleHeader)

            val reader = StreamingWavPrefixReader(partFile, included = false)
            val collected = ArrayList<Float>(frames)
            assertEquals(0, reader.poll { s, n -> for (i in 0 until n) collected += s[i] })

            var cursor = 44
            val chunks = listOf(513, 19_001, wav.size - 44 - 513 - 19_001)
            for (step in chunks) {
                partFile.appendBytes(wav.copyOfRange(cursor, cursor + step))
                cursor += step
                while (reader.poll { s, n -> for (i in 0 until n) collected += s[i] } > 0) {
                    // Drain all full 4096-sample resampler blocks emitted for this chunk.
                }
            }
            reader.finish { s, n -> for (i in 0 until n) collected += s[i] }
            reader.close()

            assertEquals("channels=$channels sample count", expected.samples.size, collected.size)
            assertArrayEquals(expected.samples, collected.toFloatArray(), 0f)
        }
    }

    @Test fun livePrecomputerCheckpointResumesCleanlyInTranscriptionJob() = runBlocking {
        val totalSeconds = 40
        val wav = buildPcmWav(sampleRate = 16_000, channels = 1, frames = 16_000 * totalSeconds)
        val prefixBytes = 44 + 16_000 * 30 * 2
        val partFile = temp.newFile("REC_0001.wav.part").apply {
            writeBytes(buildWavHeader(dataBytes = prefixBytes - 44, sampleRate = 16_000, channels = 1))
            appendBytes(wav.copyOfRange(44, prefixBytes))
        }

        val noteDir = temp.newFolder("note-live")
        val finalAudio = File(noteDir, "source.wav")
        val storage = TestJobStorage(noteDir, finalAudio, SpeechModel.PARAKEET)
        val voices = TestJobVoices()
        val models = TestModelAccess()
        val decodedWindows = CopyOnWriteArrayList<Long>()
        val audioStarts = CopyOnWriteArrayList<Long>()
        val engines = buildTestEngines(decodedWindows, audioStarts)
        val env = JobEnvironment(placeholderLabel = { "Speaker $it" }, inference = inference, io = Dispatchers.IO)
        val firstCheckpoint = CompletableDeferred<Long>()
        val precomputer = RecorderLivePrecomputer(
            RecorderLivePrecomputer.Parts(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                partFile = partFile,
                stagingDir = temp.newFolder("staging-live"),
                speechModel = SpeechModel.PARAKEET,
                models = models,
                engines = engines,
                env = env,
                storage = storage,
                voices = voices,
                speakerPrefix = { "diar_live_" },
                onCheckpointed = { doneMs -> if (doneMs > 0L) firstCheckpoint.complete(doneMs) },
            ),
        )
        precomputer.onBytesAvailable(partFile.length())
        assertEquals(28_000L, withTimeout(TIMEOUT_MS) { firstCheckpoint.await() })

        finalAudio.writeBytes(wav)
        precomputer.finishAndInstall("note-live")

        val state = (storage.loadCheckpoint("note-live") as CheckpointLoad.Valid).state
        assertEquals(CheckpointOrigin.PRECOMPUTE, state.origin)
        assertNull(state.committedPrefix)
        assertEquals(CheckpointState.signature(finalAudio), state.signature)
        assertEquals(28_000L, state.doneMs)

        val job = TranscriptionJob("note-live", storage, voices, models, engines, env, SpeechModel.PARAKEET)
        assertEquals(JobOutcome.Done, job.run())
        assertEquals(listOf(0L, 26_000L), decodedWindows.toList())
        assertEquals(listOf(state.positionSamples), audioStarts.toList())
        assertNotNull(storage.markedDocument)
        assertEquals(
            (0 until totalSeconds).map { "w$it" },
            storage.markedDocument!!.selected(null)!!.transcript!!.words.map { it.text },
        )
    }

    @Test fun concurrentJobOwnershipSkipsSnapshotInstallUnderNoteLock() = runBlocking {
        val totalSeconds = 40
        val wav = buildPcmWav(sampleRate = 16_000, channels = 1, frames = 16_000 * totalSeconds)
        val prefixBytes = 44 + 16_000 * 30 * 2
        val partFile = temp.newFile("REC_0002.wav.part").apply {
            writeBytes(buildWavHeader(dataBytes = prefixBytes - 44, sampleRate = 16_000, channels = 1))
            appendBytes(wav.copyOfRange(44, prefixBytes))
        }

        val noteDir = temp.newFolder("note-locked")
        val finalAudio = File(noteDir, "source.wav")
        val storage = TestJobStorage(noteDir, finalAudio, SpeechModel.PARAKEET)
        val voices = TestJobVoices()
        val models = TestModelAccess()
        val decodedWindows = CopyOnWriteArrayList<Long>()
        val audioStarts = CopyOnWriteArrayList<Long>()
        val engines = buildTestEngines(decodedWindows, audioStarts)
        val env = JobEnvironment(placeholderLabel = { "Speaker $it" }, inference = inference, io = Dispatchers.IO)
        val firstCheckpoint = CompletableDeferred<Long>()
        val stagingDir = temp.newFolder("staging-locked")
        var lockSeenByGuard = false
        val precomputer = RecorderLivePrecomputer(
            RecorderLivePrecomputer.Parts(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                partFile = partFile,
                stagingDir = stagingDir,
                speechModel = SpeechModel.PARAKEET,
                models = models,
                engines = engines,
                env = env,
                storage = storage,
                voices = voices,
                speakerPrefix = { "diar_live_" },
                canInstallBlocking = {
                    lockSeenByGuard = storage.insideLock
                    false
                },
                onCheckpointed = { doneMs -> if (doneMs > 0L) firstCheckpoint.complete(doneMs) },
            ),
        )
        precomputer.onBytesAvailable(partFile.length())
        assertEquals(28_000L, withTimeout(TIMEOUT_MS) { firstCheckpoint.await() })

        precomputer.awaitStopped()
        finalAudio.writeBytes(wav)
        precomputer.finishAndInstall("note-locked")

        assertTrue(lockSeenByGuard)
        assertEquals(CheckpointLoad.Missing, storage.loadCheckpoint("note-locked"))
        assertFalse(stagingDir.exists())
    }

    private class PrecomputeRig(
        private val partFile: File,
        private val stagingDir: File,
        private val storage: TestJobStorage,
        private val engines: JobEngines,
        private val env: JobEnvironment,
    ) {
        fun start(onCheckpointed: (Long) -> Unit): RecorderLivePrecomputer = RecorderLivePrecomputer(
            RecorderLivePrecomputer.Parts(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                partFile = partFile,
                stagingDir = stagingDir,
                speechModel = SpeechModel.PARAKEET,
                models = TestModelAccess(),
                engines = engines,
                env = env,
                storage = storage,
                voices = TestJobVoices(),
                speakerPrefix = { "diar_live_" },
                onCheckpointed = onCheckpointed,
            ),
        ).also { it.onBytesAvailable(partFile.length()) }
    }

    @Test fun livePrecomputerResumesAcrossDisconnectAndMatchesSinglePass() = runBlocking {
        val totalSeconds = 65
        val wav = buildPcmWav(sampleRate = 16_000, channels = 1, frames = 16_000 * totalSeconds)
        val prefixBytes = 44 + 16_000 * 30 * 2
        val partFile = temp.newFile("REC_0010.wav.part").apply {
            writeBytes(buildWavHeader(dataBytes = 1024, sampleRate = 16_000, channels = 1))
            appendBytes(wav.copyOfRange(44, prefixBytes))
        }
        val stagingDir = temp.newFolder("staging-resume")
        val noteDir = temp.newFolder("note-resume")
        val finalAudio = File(noteDir, "source.wav")
        val storage = TestJobStorage(noteDir, finalAudio, SpeechModel.PARAKEET)
        val decodedWindows = CopyOnWriteArrayList<Long>()
        val engines = buildTestEngines(decodedWindows, CopyOnWriteArrayList())
        val env = JobEnvironment(placeholderLabel = { "Speaker $it" }, inference = inference, io = Dispatchers.IO)

        val ckpt1 = CompletableDeferred<Long>()
        val rig = PrecomputeRig(partFile, stagingDir, storage, engines, env)
        val pre1 = rig.start { if (it > 0L) ckpt1.complete(it) }
        assertEquals(28_000L, withTimeout(TIMEOUT_MS) { ckpt1.await() })
        pre1.awaitStopped()
        pre1.close()
        assertTrue(stagingDir.isDirectory && File(stagingDir, "checkpoint.json").isFile)

        partFile.appendBytes(wav.copyOfRange(prefixBytes, wav.size))
        val ckpt2 = CompletableDeferred<Long>()
        val pre2 = rig.start { if (it >= 54_000L) ckpt2.complete(it) }
        assertEquals(54_000L, withTimeout(TIMEOUT_MS) { ckpt2.await() })
        assertEquals(listOf(0L, 26_000L), decodedWindows.toList())

        finalAudio.writeBytes(wav)
        RandomAccessFile(partFile, "rw").use { raf ->
            raf.seek(0L)
            raf.write(wav.copyOf(44))
        }
        pre2.finishAndInstall("note-resume")
        assertFalse(stagingDir.exists())
        assertEquals(54_000L, (storage.loadCheckpoint("note-resume") as CheckpointLoad.Valid).state.doneMs)

        val job = TranscriptionJob(
            "note-resume",
            storage,
            TestJobVoices(),
            TestModelAccess(),
            engines,
            env,
            SpeechModel.PARAKEET,
        )
        assertEquals(JobOutcome.Done, job.run())
        assertEquals(listOf(0L, 26_000L, 52_000L), decodedWindows.toList())
        assertEquals(
            (0 until totalSeconds).map { "w$it" },
            storage.markedDocument!!.selected(null)!!.transcript!!.words.map { it.text },
        )
    }

    @Test fun standaloneInstallInstallsDurableCheckpointAfterPostStopReconnect() = runBlocking {
        val totalSeconds = 40
        val wav = buildPcmWav(sampleRate = 16_000, channels = 1, frames = 16_000 * totalSeconds)
        val prefixBytes = 44 + 16_000 * 30 * 2
        val partFile = temp.newFile("REC_0011.wav.part").apply {
            writeBytes(buildWavHeader(dataBytes = 1024, sampleRate = 16_000, channels = 1))
            appendBytes(wav.copyOfRange(44, prefixBytes))
        }
        val stagingDir = temp.newFolder("staging-poststop")
        val noteDir = temp.newFolder("note-poststop")
        val finalAudio = File(noteDir, "source.wav")
        val storage = TestJobStorage(noteDir, finalAudio, SpeechModel.PARAKEET)
        val decodedWindows = CopyOnWriteArrayList<Long>()
        val engines = buildTestEngines(decodedWindows, CopyOnWriteArrayList())
        val env = JobEnvironment(placeholderLabel = { "Speaker $it" }, inference = inference, io = Dispatchers.IO)

        val ckpt = CompletableDeferred<Long>()
        val pre = PrecomputeRig(partFile, stagingDir, storage, engines, env)
            .start { if (it > 0L) ckpt.complete(it) }
        assertEquals(28_000L, withTimeout(TIMEOUT_MS) { ckpt.await() })
        pre.awaitStopped()
        pre.close()

        partFile.writeBytes(wav)
        finalAudio.writeBytes(wav)
        RecorderLivePrecomputeStore.installIntoNote(
            noteId = "note-poststop",
            partFile = partFile,
            stagingDir = stagingDir,
            inMemorySnapshot = null,
            speechModel = SpeechModel.PARAKEET,
            models = TestModelAccess(),
            storage = storage,
            voices = TestJobVoices(),
            env = env,
            canInstallBlocking = { true },
        )
        assertFalse(stagingDir.exists())
        assertEquals(28_000L, (storage.loadCheckpoint("note-poststop") as CheckpointLoad.Valid).state.doneMs)

        val job = TranscriptionJob(
            "note-poststop",
            storage,
            TestJobVoices(),
            TestModelAccess(),
            engines,
            env,
            SpeechModel.PARAKEET,
        )
        assertEquals(JobOutcome.Done, job.run())
        assertEquals(listOf(0L, 26_000L), decodedWindows.toList())
        assertEquals(
            (0 until totalSeconds).map { "w$it" },
            storage.markedDocument!!.selected(null)!!.transcript!!.words.map { it.text },
        )
    }

    @Test fun mutatedPcmDiscardsStaleStagedPrecomputeCleanly() = runBlocking {
        val wav = buildPcmWav(sampleRate = 16_000, channels = 1, frames = 16_000 * 30)
        val partFile = temp.newFile("REC_0012.wav.part").apply { writeBytes(wav) }
        val stagingDir = temp.newFolder("staging-corrupt")
        val noteDir = temp.newFolder("note-corrupt")
        val finalAudio = File(noteDir, "source.wav")
        val storage = TestJobStorage(noteDir, finalAudio, SpeechModel.PARAKEET)
        val models = TestModelAccess()
        val decodedWindows = CopyOnWriteArrayList<Long>()
        val engines = buildTestEngines(decodedWindows, CopyOnWriteArrayList())
        val env = JobEnvironment(placeholderLabel = { "Speaker $it" }, inference = inference, io = Dispatchers.IO)

        val ckpt = CompletableDeferred<Long>()
        val pre = RecorderLivePrecomputer(
            RecorderLivePrecomputer.Parts(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                partFile = partFile,
                stagingDir = stagingDir,
                speechModel = SpeechModel.PARAKEET,
                models = models,
                engines = engines,
                env = env,
                storage = storage,
                voices = TestJobVoices(),
                onCheckpointed = { doneMs -> if (doneMs > 0L) ckpt.complete(doneMs) },
            ),
        )
        pre.onBytesAvailable(partFile.length())
        assertEquals(28_000L, withTimeout(TIMEOUT_MS) { ckpt.await() })
        pre.awaitStopped()
        pre.close()

        val bytes = partFile.readBytes()
        bytes[100] = (bytes[100].toInt() xor 0x5A).toByte()
        partFile.writeBytes(bytes)
        assertNull(RecorderLivePrecomputeStore.loadStaged(partFile, stagingDir, SpeechModel.PARAKEET, models))
        assertFalse(stagingDir.exists())
    }

    private fun buildTestEngines(
        decodedWindows: MutableList<Long>,
        audioStarts: MutableList<Long>,
    ): JobEngines = JobEngines(
        recognizers = RecognizerFactory { restored, cb -> TestRecognizer(restored, cb, decodedWindows) },
        diarizers = DiarizerFactory { restored -> TestDiarizer(restored) },
        prints = TestPrints(),
        audio = object : AudioSource {
            override fun stream(
                file: File,
                startSample: Long,
                onSamples: (FloatArray, Int) -> Unit,
                onProgress: (Long, Long?) -> Unit,
            ): Long {
                audioStarts += startSample
                return RandomAccessFile(file, "r").use { raf ->
                    var total = startSample
                    WavDecoder.decodeWav(
                        raf,
                        onSamples = { s, n ->
                            total += n
                            onSamples(s, n)
                        },
                        retainPcm = false,
                        startSample = startSample,
                        included = false,
                        onProgress = onProgress,
                    )
                    total * 1000L / Limits.ASR_RATE
                }
            }
        },
        encoders = OpusSinkFactory { file, restored -> TestOpusSink(file, restored) },
    )

    private fun buildPcmWav(sampleRate: Int, channels: Int, frames: Int): ByteArray {
        val dataBytes = frames * channels * 2
        val header = buildWavHeader(dataBytes, sampleRate, channels)
        val out = ByteArray(44 + dataBytes)
        header.copyInto(out)
        val buf = ByteBuffer.wrap(out, 44, dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) {
            repeat(channels) { ch ->
                val sample = (((f * (17 + ch * 13)) % 12000) - 6000).toShort()
                buf.putShort(sample)
            }
        }
        return out
    }

    private fun buildWavHeader(dataBytes: Int, sampleRate: Int, channels: Int): ByteArray {
        val out = ByteArray(44)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        "RIFF".toByteArray(Charsets.US_ASCII).forEach(buf::put)
        buf.putInt(36 + dataBytes)
        "WAVE".toByteArray(Charsets.US_ASCII).forEach(buf::put)
        "fmt ".toByteArray(Charsets.US_ASCII).forEach(buf::put)
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * channels * 2)
        buf.putShort((channels * 2).toShort())
        buf.putShort(16)
        "data".toByteArray(Charsets.US_ASCII).forEach(buf::put)
        buf.putInt(dataBytes)
        return out
    }

    private class TestRecognizer(
        restored: RecognitionState?,
        private val callbacks: Recognizer.Callbacks,
        private val decodedWindows: MutableList<Long>,
    ) : Recognizer {
        override var totalMs = 0L
        override var inferenceMs = restored?.inferenceMs ?: 0L
            private set
        override val segmentation = "Fake segmentation"
        override val language = Transcript.UNDETERMINED

        private val chunks = IncrementalTranscript(restored?.chunks) { audio, startMs ->
            decodedWindows += startMs
            inferenceMs += 100L
            val endMs = startMs + audio.durationMs
            val words = (startMs / 1000..endMs / 1000).mapNotNull { sec ->
                val s = sec * 1000 + 100
                val e = sec * 1000 + 800
                if (s >= startMs && e <= endMs) Word("", s - startMs, e - startMs, "w$sec") else null
            }
            callbacks.onChunk(audio, startMs, Transcript(words))
        }

        override val positionSamples: Long get() = chunks.positionSamples
        override val doneMs: Long get() = chunks.doneMs

        override fun accept(samples: FloatArray, count: Int) = chunks.accept(samples, count) { done, words ->
            callbacks.onProgress(Parakeet.Progress(done, maxOf(totalMs, done), 0, words))
        }

        override fun finish(durationMs: Long): Transcript = chunks.finish().also {
            callbacks.onProgress(Parakeet.Progress(durationMs, durationMs, 0, it.words))
        }

        override fun checkpoint() = RecognitionState(inferenceMs, chunks.checkpoint())

        override fun close() = Unit
    }

    private class TestDiarizer(restored: DiarizerState?) : StreamingDiarizer {
        private val found = restored?.turns.orEmpty().toMutableList()
        override val turns: List<SpeakerTurn> get() = found.toList()
        override var elapsedMs = restored?.elapsedMs ?: 0L
            private set

        override fun process(audio: PcmAudio, startMs: Long): List<SpeakerTurn> {
            val endMs = startMs + audio.durationMs
            return (startMs / 10_000L..(endMs - 1) / 10_000L).map { k ->
                val s = maxOf(k * 10_000L, startMs)
                val e = minOf((k + 1) * 10_000L, endMs)
                val spk = (k % 2L).toInt()
                found += SpeakerTurn(s, e, spk)
                SpeakerTurn(s - startMs, e - startMs, spk)
            }
        }

        override fun checkpoint() = DiarizerState(floatArrayOf(1f, 2f, 3f), elapsedMs, turns)

        override fun close() = Unit
    }

    private class TestPrints : PrintExtractor {
        override fun compute(
            audio: PcmSource,
            windows: Map<String, List<PrintWindow>>,
            active: () -> Unit,
        ): Map<String, ExtractedPrint> = windows.mapValues { (id, spans) ->
            active()
            val sec = spans.sumOf { it.endSample - it.startSample }.toDouble() / Limits.ASR_RATE
            val vec = FloatArray(8).also { it[(id.last().digitToIntOrNull() ?: 0) % 8] = 1f }
            vec to sec
        }
    }

    private class TestOpusSink(
        private val file: File,
        restored: EncoderState?,
    ) : OpusSink {
        private var sampleCount = restored?.samples ?: 0L
        private var syncs = restored?.sequence ?: 0
        private val pending = ByteArrayOutputStream()

        init {
            if (restored == null) {
                file.writeBytes(ByteArray(0))
            } else {
                RandomAccessFile(file, "rw").use { it.setLength(restored.length) }
            }
        }

        override fun accept(samples: FloatArray, count: Int) {
            pending.write(byteArrayOf(1, 2, 3, 4))
            sampleCount += count
        }

        override fun checkpoint(): EncoderState {
            file.appendBytes(pending.toByteArray())
            pending.reset()
            syncs++
            return EncoderState(
                history = null,
                frame = FloatArray(CheckpointState.FRAME_FLOATS),
                samples = sampleCount,
                pending = 0,
                length = file.length(),
                serial = 7,
                sequence = syncs,
                granule = 0,
                packet = null,
            )
        }

        override fun finish() {
            FIXTURE_OPUS.copyTo(file, overwrite = true)
        }

        override fun close() = Unit
    }

    private class TestJobStorage(
        noteDir: File,
        private val audio: File,
        private val speechModel: SpeechModel,
    ) : JobStorage {
        private val work = File(noteDir, "work")
        private val marker = File(work, "sealed")
        private val namesRef = AtomicReference(CheckpointNames(emptyMap(), emptyMap()))
        private var checkpointJson: String? = null
        private var lockDepth = 0
        val insideLock: Boolean get() = lockDepth > 0
        var markedDocument: CassiniDocument? = null

        override suspend fun loadCheckpoint(noteId: String): CheckpointLoad {
            val raw = checkpointJson ?: return CheckpointLoad.Missing
            val json = JSONObject(raw)
            val expected = CheckpointState.Expected(
                source = audio.name,
                signature = CheckpointState.signature(audio),
                modelRevision = speechModel.identity,
                speakersSha256 = ModelCatalog.diarizationModel.sha256,
                clipsBytes = File(work, "clips.pcm").length(),
                audioBytes = File(work, "audio.opus.part").length(),
                wordLogBytes = 0L,
            )
            val result = CheckpointState.validateWithResult(json, expected)
            return when (result.decision) {
                PrefixDecision.RESUME -> CheckpointLoad.Valid(CheckpointState.decode(json))
                PrefixDecision.DISCARD_AND_FULL_DECODE -> {
                    discardWorkBlocking(noteId)
                    CheckpointLoad.Missing
                }
                PrefixDecision.REJECT_BATCH_CHECKPOINT ->
                    CheckpointLoad.Mismatch(result.reason?.token ?: "mismatch")
            }
        }

        override fun saveCheckpoint(noteId: String, state: CheckpointState) {
            checkpointJson = CheckpointState.encode(state).toString()
        }

        override fun names(noteId: String) = namesRef

        override fun mutateNames(noteId: String, reducer: (CheckpointNames) -> CheckpointNames): CheckpointNames =
            namesRef.updateAndGet(reducer)

        override fun openNames(noteId: String) = Unit

        override fun closeNames(noteId: String): CheckpointNames = namesRef.get()

        override suspend fun audioFile(noteId: String) = audio

        override suspend fun knownDurationMs(noteId: String): Long? = null

        override fun workFiles(noteId: String): WorkFiles {
            work.mkdirs()
            return WorkFiles(File(work, "audio.opus.part"), File(work, "clips.pcm"))
        }

        override fun sealTarget(noteId: String) = File(work, "sealed.opus")

        override fun <T> withNoteLockBlocking(noteId: String, block: () -> T): T {
            lockDepth++
            return try {
                block()
            } finally {
                lockDepth--
            }
        }

        override fun existingDocumentBlocking(noteId: String): CassiniDocument? = null

        override fun recordedAtUtcBlocking(noteId: String): Instant? = null

        override fun titleBlocking(noteId: String) = "Live sync"

        override fun markSealedBlocking(noteId: String, document: CassiniDocument) {
            markedDocument = document
            marker.writeText("ok")
        }

        override fun discardSealedBlocking(noteId: String) {
            marker.delete()
            sealTarget(noteId).delete()
        }

        override fun discardWorkBlocking(noteId: String) {
            checkpointJson = null
            work.deleteRecursively()
        }

        override suspend fun adopt(noteId: String, adoption: Adoption) = Unit
    }

    private class TestJobVoices : JobVoices {
        override fun peopleBlocking(): List<Voice> = emptyList()
        override fun generationBlocking(): Long = 1L
        override fun noteVoicesBlocking(noteId: String): Map<String, SpeakerPrint> = emptyMap()
        override fun savePendingBlocking(
            noteId: String,
            variantId: String,
            model: String,
            prints: Map<String, SpeakerPrint>,
            generation: Long,
        ) = Unit
        override fun discardPendingBlocking(noteId: String) = Unit
    }

    private class TestModelAccess : ModelAccess {
        override suspend fun consented(): Boolean = true
        override fun ready(part: ModelPart): Boolean = true
        override fun plan(): ModelPlan = ModelPlan(emptyList())
        override fun detectorReady(): Boolean = true
        override fun hasValidatedNetwork(): Boolean = true
        override suspend fun install(parts: Set<ModelPart>, onProgress: (DownloadProgress) -> Unit): InstallOutcome =
            InstallOutcome.Installed()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        val FIXTURE_OPUS: File by lazy {
            generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
                .map { File(it, "fixtures/audio/fleurs-it-short.opus") }
                .first { it.isFile }
        }
    }
}
