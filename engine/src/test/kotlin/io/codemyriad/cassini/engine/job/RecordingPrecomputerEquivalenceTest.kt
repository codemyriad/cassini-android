package io.codemyriad.cassini.engine.job

import io.codemyriad.cassini.core.audio.Limits
import io.codemyriad.cassini.core.checkpoint.CheckpointOrigin
import io.codemyriad.cassini.core.checkpoint.CheckpointState
import io.codemyriad.cassini.core.checkpoint.CommittedPrefix
import io.codemyriad.cassini.core.checkpoint.PrefixDecision
import io.codemyriad.cassini.core.checkpoint.WhistleCachedSegment
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.OggOpus
import io.codemyriad.cassini.core.format.OggOpusPrefixScanner
import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.engine.decode.CommittedPrefixDecoder
import io.codemyriad.cassini.engine.whistle.FileWhistleSegmentStore
import io.codemyriad.cassini.engine.whistle.WhistleRecognizer
import io.codemyriad.cassini.engine.whistle.WhistleReplay
import io.codemyriad.cassini.engine.whistle.WhistleResult
import io.codemyriad.cassini.engine.whistle.WhistleStream
import io.codemyriad.cassini.engine.whistle.WhistleTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Paired `AFTER_STOP` vs. `TRANSCRIBE_WHILE_RECORDING` equivalence and negative-control tests for both
 * Parakeet and Whistle (with `SortformerStream` + `VoiceClipCache` + voiceprint matching), plus resource
 * yielding and prefix-validation fallback (D-1124).
 */
class RecordingPrecomputerEquivalenceTest {
    @get:Rule val temp = TemporaryFolder()

    private val inference: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "test-precompute-inference") }.asCoroutineDispatcher()

    @After fun closeInference() = inference.close()

    private fun opusHead(preSkip: Int = 312): ByteArray = ByteArray(19).also { head ->
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = 1
        head[10] = (preSkip and 0xff).toByte()
        head[11] = ((preSkip ushr 8) and 0xff).toByte()
        OggOpus.put32(head, 12, 48_000)
    }

    /** Valid 20 ms Opus packet header (960 @ 48 kHz = 320 @ 16 kHz). */
    private fun fakeOpusPacket(index: Int): ByteArray = ByteArray(48) { i ->
        if (i == 0) 0xfc.toByte() else ((index * 17 + i) and 0xff).toByte()
    }

    private class ScriptedOggPrefixDecoder(
        file: File,
        private val fullTrack: FloatArray,
        private var cursor: Long,
        margin: Int = 1,
    ) : CommittedPrefixDecoder {
        private val scanner = OggOpusPrefixScanner(file, trailingPagesMargin = margin)
        private var seenSamples = 0L

        override val committedBytes: Long get() = scanner.committedBytes
        override val sawEos: Boolean get() = scanner.sawEos
        override val corrupt: Boolean get() = scanner.corrupt
        override fun committedSha256(): String = scanner.committedSha256()

        override fun poll(onSamples: (FloatArray, Int) -> Unit): Int {
            val packets = scanner.poll()
            for (packet in packets) {
                val samples16k = packet.sampleCount / 3
                val packetStart = seenSamples
                seenSamples += samples16k
                val emitStart = maxOf(packetStart, cursor)
                val emitEnd = minOf(seenSamples, fullTrack.size.toLong())
                if (emitEnd > emitStart) {
                    val count = (emitEnd - emitStart).toInt()
                    onSamples(fullTrack.copyOfRange(emitStart.toInt(), emitEnd.toInt()), count)
                    cursor = emitEnd
                }
            }
            return packets.size
        }

        override fun close() = Unit
    }

    private class PrefixValidatingStorage(
        noteDir: File,
        audio: File,
        log: MutableList<String>,
        private val speechModel: SpeechModel,
    ) : FakeStorage(noteDir, audio, log) {
        val whistleCacheFile = File(work, "whistle-segments.json")
        var discardedCount = 0

        override fun whistleSegmentCache(noteId: String): File = whistleCacheFile

        override fun discardWorkBlocking(noteId: String) {
            discardedCount++
            checkpointJson = null
            work.deleteRecursively()
        }

        override suspend fun loadCheckpoint(noteId: String): CheckpointLoad {
            val raw = checkpointJson ?: return CheckpointLoad.Missing
            val json = JSONObject(raw)
            val isOpus = OggOpusPrefixScanner.isOggOpusFile(audio)
            val prefixJson = json.optJSONObject("committedPrefix")
            val committedUnits = prefixJson?.optLong("committedUnitCount", -1L) ?: -1L
            val prefixSha = if (isOpus && committedUnits > 0L) {
                OggOpusPrefixScanner.sha256Prefix(audio, committedUnits)
            } else {
                null
            }
            val expected = CheckpointState.Expected(
                source = audio.name,
                signature = CheckpointState.signature(audio),
                modelRevision = speechModel.identity,
                speakersSha256 = ModelCatalog.diarizationModel.sha256,
                clipsBytes = File(work, "clips.pcm").length(),
                audioBytes = File(work, "audio.opus.part").length(),
                wordLogBytes = 0L,
                prefixKind = if (isOpus) CommittedPrefix.KIND_OGG_OPUS_BYTES else CommittedPrefix.KIND_AAC_M4A,
                finalizedUnitCount = audio.length(),
                prefixSha256 = prefixSha,
                deferDecodedPcmCheck = true,
            )
            val result = CheckpointState.validateWithResult(json, expected)
            return when (result.decision) {
                PrefixDecision.RESUME -> CheckpointLoad.Valid(CheckpointState.decode(json))
                PrefixDecision.DISCARD_AND_FULL_DECODE -> {
                    discardWorkBlocking(noteId)
                    CheckpointLoad.Missing
                }
                PrefixDecision.REJECT_BATCH_CHECKPOINT -> CheckpointLoad.Mismatch(result.reason?.token ?: "mismatch")
            }
        }
    }

    private fun trackAudioSource(track: FloatArray, totalSeconds: Int): AudioSource = object : AudioSource {
        override fun stream(
            file: File,
            startSample: Long,
            onSamples: (FloatArray, Int) -> Unit,
            onProgress: (Long, Long?) -> Unit,
        ): Long {
            onProgress(startSample * 1000L / Limits.ASR_RATE, totalSeconds * 1000L)
            var at = startSample.toInt()
            while (at < track.size) {
                val n = minOf(8192, track.size - at)
                onSamples(track.copyOfRange(at, at + n), n)
                at += n
            }
            return totalSeconds * 1000L
        }
    }

    private fun parakeetEngines(script: Script, track: FloatArray, totalSeconds: Int): JobEngines = JobEngines(
        recognizers = RecognizerFactory { restored, cb -> FakeRecognizer(restored, cb, script) },
        diarizers = DiarizerFactory { restored ->
            FakeDiarizer(restored, script).also { script.diarizers += it }
        },
        prints = FakePrints(script),
        audio = trackAudioSource(track, totalSeconds),
        encoders = OpusSinkFactory { _, _ -> error("Opus source never re-encodes") },
    )

    private fun whistleTransport(
        onResult: (WhistleResult) -> Unit,
        transcribeCalls: AtomicInteger,
    ): WhistleTransport {
        val cache = HashMap<Pair<String, Int>, ByteArray>()
        var frameIdx = 0
        val stream = WhistleStream(
            rate = Limits.ASR_RATE,
            probability = {
                val sec = (frameIdx++ * 512) / Limits.ASR_RATE
                if (sec in 0..4 || sec in 7..11 || sec in 14..18) 0.95f else 0.01f
            },
            transcribe = { seg ->
                transcribeCalls.incrementAndGet()
                val endSec = maxOf(0.4, (seg.size.toDouble() / Limits.ASR_RATE) - 0.05)
                val formattedEnd = String.format(java.util.Locale.ROOT, "%.3f", endSec)
                """{"words":[{"word":"spoken${seg.size}","start":0.050,"end":$formattedEnd}]}""".toByteArray()
            },
            segment = { range, json, hash ->
                onResult(WhistleResult(range.startSample, range.endSample, json, hash))
            },
            cachedSegment = { hash, count -> cache[hash to count] },
        )
        return object : WhistleTransport {
            override fun seedCache(entries: List<WhistleCachedSegment>) {
                entries.forEach { cache[it.pcmSha256 to it.sampleCount] = it.json }
            }

            override fun accept(samples: FloatArray, count: Int) {
                stream.accept(if (count == samples.size) samples else samples.copyOf(count))
            }

            override fun finish() {
                stream.finish()
            }

            override fun close() = Unit
        }
    }

    private fun whistleEngines(
        cacheFile: File,
        script: Script,
        track: FloatArray,
        totalSeconds: Int,
        transcribeCalls: AtomicInteger,
    ): JobEngines {
        val replay = WhistleReplay { limit, sink ->
            var at = 0
            while (at < limit.toInt()) {
                val n = minOf(8192, limit.toInt() - at)
                sink(track.copyOfRange(at, at + n), n)
                at += n
            }
        }
        return JobEngines(
            recognizers = RecognizerFactory { restored, cb ->
                WhistleRecognizer(
                    restored = restored,
                    callbacks = cb,
                    replay = replay,
                    open = { onResult -> whistleTransport(onResult, transcribeCalls) },
                    segmentStore = FileWhistleSegmentStore(cacheFile),
                )
            },
            diarizers = DiarizerFactory { restored ->
                FakeDiarizer(restored, script).also { script.diarizers += it }
            },
            prints = FakePrints(script),
            audio = trackAudioSource(track, totalSeconds),
            encoders = OpusSinkFactory { _, _ -> error("Opus source never re-encodes") },
        )
    }

    private fun openLiveOpus(name: String, initialPackets: Int): Triple<File, FileOutputStream, OggOpus.LiveMuxer> {
        val file = temp.newFile(name)
        val out = FileOutputStream(file)
        val muxer = OggOpus.LiveMuxer(out, opusHead(), CassiniDocument.tagsPacket(emptyList()), serial = 101)
        repeat(initialPackets) { muxer.add(fakeOpusPacket(it)) }
        out.flush()
        return Triple(file, out, muxer)
    }

    private fun finishLiveOpus(
        out: FileOutputStream,
        muxer: OggOpus.LiveMuxer,
        fromPacket: Int,
        totalPackets: Int,
    ) {
        for (i in fromPacket until totalPackets) muxer.add(fakeOpusPacket(i))
        muxer.finish(312L + totalPackets * 960L)
        out.flush()
        out.close()
    }

    private class Lane(
        val noteId: String,
        val storage: PrefixValidatingStorage,
        val voices: FakeVoices,
        val script: Script,
        val env: JobEnvironment,
        val precomputeModels: FakeModels = FakeModels(),
    )

    private fun newLane(
        name: String,
        noteId: String,
        audioFile: File,
        model: SpeechModel,
        prefix: String,
    ): Lane {
        val log = mutableListOf<String>()
        val storage = PrefixValidatingStorage(temp.newFolder(name), audioFile, log, model)
        val voices = FakeVoices(log).apply { people = savedVoices() }
        val env = JobEnvironment({ "Speaker $it" }, inference = inference, speakerPrefix = { prefix })
        return Lane(noteId, storage, voices, Script(), env)
    }

    private suspend fun runSinglePassPrecompute(
        lane: Lane,
        audioFile: File,
        model: SpeechModel,
        engines: JobEngines,
        track: FloatArray,
    ): PrecomputeStopReason {
        val stop = AtomicBoolean(false)
        return RecordingPrecomputer(
            noteId = lane.noteId,
            audioFile = audioFile,
            speechModel = model,
            storage = lane.storage,
            voices = lane.voices,
            models = lane.precomputeModels,
            engines = engines,
            env = lane.env,
            openDecoder = { file, _, start -> ScriptedOggPrefixDecoder(file, track, start) },
            shouldContinue = { !stop.get() },
            waitForMoreBytes = {
                stop.set(true)
                false
            },
        ).run()
    }

    private suspend fun runBatchJob(lane: Lane, model: SpeechModel, engines: JobEngines): JobOutcome =
        TranscriptionJob(lane.noteId, lane.storage, lane.voices, FakeModels(), engines, lane.env, model).run()

    private fun assertEquivalentSealedOutputs(batch: Lane, pre: Lane) {
        val preDoc = CassiniDocument.read(pre.storage.sealTarget(pre.noteId))
        val batchDoc = CassiniDocument.read(batch.storage.sealTarget(batch.noteId))
        assertEquals(batchDoc.selected(null)!!.transcript!!.words, preDoc.selected(null)!!.transcript!!.words)
        assertEquals(diarizationCounts(batchDoc), diarizationCounts(preDoc))
        assertEquals(batch.script.diarizers.last().turns, pre.script.diarizers.last().turns)
        assertEquals(speakerLabels(batchDoc), speakerLabels(preDoc))
        assertEquals(batch.voices.pendingFile!!.prints.keys, pre.voices.pendingFile!!.prints.keys)
        batch.voices.pendingFile!!.prints.forEach { (speaker, expectedPrint) ->
            assertEquals(expectedPrint.match, pre.voices.pendingFile!!.prints[speaker]!!.match)
        }
    }

    private fun speakerLabels(document: CassiniDocument): Map<String, String> {
        val speakers = document.manifest?.optJSONArray("speakers") ?: return emptyMap()
        return (0 until speakers.length()).associate { i ->
            val speaker = speakers.getJSONObject(i)
            speaker.getString("id") to speaker.getString("label")
        }
    }

    private fun diarizationCounts(document: CassiniDocument): Pair<Int, Int> {
        val speech = document.manifest!!.getJSONObject("provenance").getJSONObject("speechToText")
        val diar = speech.getJSONObject(document.defaultId!!).getJSONObject("x-speakerDiarization")
        return diar.getInt("turnCount") to diar.getInt("speakerCount")
    }

    private fun savedVoices() = listOf(
        voice("v-alice", "Alice", axis(1)),
        voice("v-bob", "Bob", axis(2)),
    )

    @Test fun pairedParakeetAfterStopAndTranscribeWhileRecordingProduceIdenticalSealedTranscript() = runBlocking {
        val totalSeconds = 70
        val totalPackets = totalSeconds * 50
        val precomputePackets = 60 * 50
        val track = FloatArray(totalSeconds * Limits.ASR_RATE) { i -> ((i / (10 * Limits.ASR_RATE)) % 3 + 1) * 0.1f }
        val (audioFile, out, muxer) = openLiveOpus("capture.opus", precomputePackets)

        val pre = newLane("pre-p", "note-pair", audioFile, SpeechModel.PARAKEET, "diar_pair_")
        val preEngines = parakeetEngines(pre.script, track, totalSeconds)
        val stopReason = runSinglePassPrecompute(pre, audioFile, SpeechModel.PARAKEET, preEngines, track)
        assertEquals(PrecomputeStopReason.YIELDED, stopReason)
        assertEquals(listOf(0L, 26_000L), pre.script.windows)
        assertEquals(CheckpointOrigin.PRECOMPUTE, pre.storage.saved.last().origin)
        assertNotNull(pre.storage.saved.last().committedPrefix)

        finishLiveOpus(out, muxer, precomputePackets, totalPackets)

        pre.script.windows.clear()
        assertEquals(JobOutcome.Done, runBatchJob(pre, SpeechModel.PARAKEET, preEngines))
        assertEquals("Post-Stop must only decode the remaining tail window", listOf(52_000L), pre.script.windows)
        assertEquals(0, pre.storage.discardedCount)

        val batch = newLane("batch-p", "note-pair", audioFile, SpeechModel.PARAKEET, "diar_pair_")
        val batchEngines = parakeetEngines(batch.script, track, totalSeconds)
        assertEquals(JobOutcome.Done, runBatchJob(batch, SpeechModel.PARAKEET, batchEngines))
        assertEquals(listOf(0L, 26_000L, 52_000L), batch.script.windows)

        assertEquivalentSealedOutputs(batch, pre)
    }

    @Test fun pairedWhistleProducesIdenticalOutputWithZeroPrefixNeuralReDecodes() = runBlocking {
        val totalSeconds = 23
        val totalPackets = totalSeconds * 50
        val precomputePackets = 14 * 50 + 5
        val track = FloatArray(totalSeconds * Limits.ASR_RATE) { i ->
            val sec = i / Limits.ASR_RATE
            if (sec in 0..4 || sec in 7..11 || sec in 14..18) (((i % 389) - 194) / 1024f) else 0f
        }
        val (audioFile, out, muxer) = openLiveOpus("capture-whistle.opus", precomputePackets)

        val pre = newLane("pre-w", "note-w", audioFile, SpeechModel.INCLUDED, "diar_w_")
        val preCalls = AtomicInteger()
        val preEngines = whistleEngines(pre.storage.whistleCacheFile, pre.script, track, totalSeconds, preCalls)
        assertEquals(
            PrecomputeStopReason.YIELDED,
            runSinglePassPrecompute(pre, audioFile, SpeechModel.INCLUDED, preEngines, track),
        )
        assertEquals(2, preCalls.get())
        assertTrue(pre.storage.whistleCacheFile.isFile)

        finishLiveOpus(out, muxer, precomputePackets, totalPackets)

        val postStopCalls = AtomicInteger()
        val resumeEngines = whistleEngines(pre.storage.whistleCacheFile, pre.script, track, totalSeconds, postStopCalls)
        assertEquals(JobOutcome.Done, runBatchJob(pre, SpeechModel.INCLUDED, resumeEngines))
        assertEquals("Post-Stop Whistle must run neural ASR ONLY on the 1 tail segment", 1, postStopCalls.get())
        assertEquals(0, pre.storage.discardedCount)

        val batch = newLane("batch-w", "note-w", audioFile, SpeechModel.INCLUDED, "diar_w_")
        val batchCalls = AtomicInteger()
        val batchEngines = whistleEngines(batch.storage.whistleCacheFile, batch.script, track, totalSeconds, batchCalls)
        assertEquals(JobOutcome.Done, runBatchJob(batch, SpeechModel.INCLUDED, batchEngines))
        assertEquals(3, batchCalls.get())

        assertEquivalentSealedOutputs(batch, pre)
    }

    @Test fun alteredOggPrefixSilentlyDiscardsPrecomputeAndRunsFullDecode() = runBlocking {
        val totalSeconds = 40
        val totalPackets = totalSeconds * 50
        val precomputePackets = 30 * 50
        val track = FloatArray(totalSeconds * Limits.ASR_RATE) { 0.1f }
        val (audioFile, out, muxer) = openLiveOpus("capture-neg.opus", precomputePackets)

        val lane = newLane("neg-dir", "note-neg", audioFile, SpeechModel.PARAKEET, "diar_neg_")
        val engines = parakeetEngines(lane.script, track, totalSeconds)
        runSinglePassPrecompute(lane, audioFile, SpeechModel.PARAKEET, engines, track)
        assertEquals(listOf(0L), lane.script.windows)

        finishLiveOpus(out, muxer, precomputePackets, totalPackets)
        val rewritten = temp.newFile("rewritten.opus")
        OggOpus.rewrite(audioFile, CassiniDocument.tagsPacket(listOf("TITLE=Rewritten")), rewritten, serial = 999)
        rewritten.copyTo(audioFile, overwrite = true)

        lane.script.windows.clear()
        assertEquals(JobOutcome.Done, runBatchJob(lane, SpeechModel.PARAKEET, engines))
        assertEquals(1, lane.storage.discardedCount)
        assertEquals(listOf(0L, 26_000L), lane.script.windows)
    }

    @Test fun precomputeReplayMismatchInWhistleSilentlyFallsBackToFullBatchDecode() = runBlocking {
        val log = mutableListOf<String>()
        val dir = temp.newFolder("whistle-fallback")
        val audio = FIXTURE_OPUS.copyTo(File(dir, "capture.opus"))
        val storage = FakeStorage(dir, audio, log)
        val script = Script()
        val env = JobEnvironment({ "Speaker $it" }, inference = inference)

        storage.saveCheckpoint(
            "note-wf",
            CheckpointState(
                source = audio.name,
                signature = CheckpointState.signature(audio),
                model = SpeechModel.INCLUDED.identity,
                speakers = ModelCatalog.diarizationModel.sha256,
                recognition = FakeRecognizer(null, Recognizer.Callbacks({ _, _, t -> t }, { _, _, _ -> }, {}), script)
                    .checkpoint(),
                encoder = null,
                diarizer = null,
                speakerPrefix = "diar_wf_",
                speakerFailure = false,
                clipSamples = 0,
                voiceClips = emptyMap(),
                positionSamples = 16_000L,
                doneMs = 1_000L,
                durationMs = 0L,
                elapsedMs = 100L,
                origin = CheckpointOrigin.PRECOMPUTE,
            ),
        )

        var openCalls = 0
        val engines = JobEngines(
            recognizers = RecognizerFactory { restored, cb ->
                openCalls++
                if (restored != null) throw RecognitionCheckpointMismatch("Simulated Whistle PCM digest mismatch")
                FakeRecognizer(null, cb, script)
            },
            diarizers = DiarizerFactory { restored -> FakeDiarizer(restored, script) },
            prints = FakePrints(script),
            audio = FakeAudio(totalMs = 5_000L),
            encoders = OpusSinkFactory { _, _ -> error("Opus source never re-encodes") },
        )

        val outcome = TranscriptionJob(
            noteId = "note-wf",
            storage = storage,
            voices = FakeVoices(log),
            models = FakeModels(),
            engines = engines,
            env = env,
            speechModel = SpeechModel.INCLUDED,
        ).run()
        assertEquals(
            "Precompute replay mismatch must fall back cleanly, never CheckpointMismatch",
            JobOutcome.Done,
            outcome,
        )
        assertEquals(2, openCalls)
    }

    @Test fun precomputerYieldsOnOomAndPauseWithoutDisturbingCapture() = runBlocking {
        val (audioFile, out, _) = openLiveOpus("oom.opus", 5)
        out.close()
        val log = mutableListOf<String>()
        val storage = FakeStorage(temp.newFolder("oom-dir"), audioFile, log)
        val env = JobEnvironment({ "Speaker $it" }, inference = inference)

        val oomEngines = JobEngines(
            recognizers = RecognizerFactory { _, _ -> throw OutOfMemoryError("Simulated OOM") },
            diarizers = DiarizerFactory { error("unused") },
            prints = FakePrints(Script()),
            audio = FakeAudio(1000L),
            encoders = OpusSinkFactory { _, _ -> error("unused") },
        )
        val oomReason = RecordingPrecomputer(
            noteId = "note-oom",
            audioFile = audioFile,
            speechModel = SpeechModel.PARAKEET,
            storage = storage,
            voices = FakeVoices(log),
            models = FakeModels(),
            engines = oomEngines,
            env = env,
            openDecoder = { file, _, start -> ScriptedOggPrefixDecoder(file, FloatArray(16_000), start) },
        ).run()
        assertEquals(PrecomputeStopReason.OUT_OF_MEMORY, oomReason)
        assertFalse("NativeBudget must be released after OOM", NativeBudget.isHeld)

        val gate = Channel<Boolean>(1)
        val allow = AtomicBoolean(true)
        gate.trySend(false)
        val pauseReason = RecordingPrecomputer(
            noteId = "note-pause",
            audioFile = audioFile,
            speechModel = SpeechModel.PARAKEET,
            storage = storage,
            voices = FakeVoices(log),
            models = FakeModels(),
            engines = parakeetEngines(Script(), FloatArray(16_000), 1),
            env = env,
            openDecoder = { file, _, start -> ScriptedOggPrefixDecoder(file, FloatArray(16_000), start) },
            shouldContinue = { allow.get() },
            waitForMoreBytes = {
                allow.set(false)
                gate.receive()
            },
        ).run()
        assertEquals(PrecomputeStopReason.YIELDED, pauseReason)
        assertFalse("NativeBudget must be released after pause", NativeBudget.isHeld)
    }

    @Test fun cancellationWhileOpeningSessionClosesRecognizerAndDecoderOnInferenceThread() = runBlocking {
        val (audioFile, out, _) = openLiveOpus("cancel-open.opus", 5)
        out.close()
        val log = mutableListOf<String>()
        val storage = FakeStorage(temp.newFolder("cancel-open-dir"), audioFile, log)
        val env = JobEnvironment({ "Speaker $it" }, inference = inference)

        val opened = CompletableDeferred<Unit>()
        val releaseOpen = CountDownLatch(1)
        val recognizerClosed = AtomicInteger()
        val decoderClosed = AtomicInteger()
        val openThread = AtomicReference<Thread?>(null)
        val closeThread = AtomicReference<Thread?>(null)
        val script = Script()

        val engines = JobEngines(
            recognizers = RecognizerFactory { restored, cb ->
                openThread.set(Thread.currentThread())
                val inner = FakeRecognizer(restored, cb, script)
                val tracked = object : Recognizer by inner {
                    override fun close() {
                        recognizerClosed.incrementAndGet()
                        closeThread.set(Thread.currentThread())
                        inner.close()
                    }
                }
                opened.complete(Unit)
                runCatching { releaseOpen.await(10, TimeUnit.SECONDS) }
                Thread.interrupted()
                tracked
            },
            diarizers = DiarizerFactory { restored -> FakeDiarizer(restored, script) },
            prints = FakePrints(script),
            audio = FakeAudio(1_000L),
            encoders = OpusSinkFactory { _, _ -> error("unused") },
        )

        val job = launch(Dispatchers.Default) {
            RecordingPrecomputer(
                noteId = "note-cancel-open",
                audioFile = audioFile,
                speechModel = SpeechModel.PARAKEET,
                storage = storage,
                voices = FakeVoices(log),
                models = FakeModels(),
                engines = engines,
                env = env,
                openDecoder = { file, _, start ->
                    val inner = ScriptedOggPrefixDecoder(file, FloatArray(16_000), start)
                    object : CommittedPrefixDecoder by inner {
                        override fun close() {
                            decoderClosed.incrementAndGet()
                            inner.close()
                        }
                    }
                },
            ).run()
        }

        opened.await()
        job.cancel()
        releaseOpen.countDown()
        job.join()

        assertEquals(1, recognizerClosed.get())
        assertEquals(1, decoderClosed.get())
        assertEquals(openThread.get(), closeThread.get())
        assertTrue(closeThread.get()?.name?.startsWith("test-precompute-inference") == true)
        assertFalse("NativeBudget must be released after cancellation", NativeBudget.isHeld)
    }

    @Test fun missingSpeakerModelDuringPrecomputeDiscardsAndDiarizesAfterBatchInstall() = runBlocking {
        val totalSeconds = 70
        val totalPackets = totalSeconds * 50
        val precomputePackets = 60 * 50
        val track = FloatArray(totalSeconds * Limits.ASR_RATE) { i -> ((i / (10 * Limits.ASR_RATE)) % 3 + 1) * 0.1f }
        val (audioFile, out, muxer) = openLiveOpus("missing-speakers.opus", precomputePackets)

        val pre = newLane("pre-no-spk", "note-no-spk", audioFile, SpeechModel.PARAKEET, "diar_nospk_")
        pre.precomputeModels.readyParts = setOf(ModelPart.SPEECH, ModelPart.VOICES)
        val preEngines = parakeetEngines(pre.script, track, totalSeconds)
        assertEquals(
            PrecomputeStopReason.YIELDED,
            runSinglePassPrecompute(pre, audioFile, SpeechModel.PARAKEET, preEngines, track),
        )
        assertTrue(pre.storage.saved.last().speakerFailure)
        assertTrue("Diarizer must not run when speaker model is absent", pre.script.diarizers.isEmpty())

        finishLiveOpus(out, muxer, precomputePackets, totalPackets)

        pre.script.windows.clear()
        // Post-Stop batch job has all models installed (including SPEAKERS) and must discard & diarize.
        assertEquals(JobOutcome.Done, runBatchJob(pre, SpeechModel.PARAKEET, preEngines))
        assertEquals(1, pre.storage.discardedCount)
        assertTrue("Diarizer must run after batch install", pre.script.diarizers.isNotEmpty())
        assertEquals(listOf(0L, 26_000L, 52_000L), pre.script.windows)

        val batch = newLane("batch-no-spk", "note-no-spk", audioFile, SpeechModel.PARAKEET, "diar_nospk_")
        val batchEngines = parakeetEngines(batch.script, track, totalSeconds)
        assertEquals(JobOutcome.Done, runBatchJob(batch, SpeechModel.PARAKEET, batchEngines))
        assertEquivalentSealedOutputs(batch, pre)
    }

    @Test fun missingVoiceModelDuringPrecomputeDiscardsAndCollectsVoiceClipsAfterBatchInstall() = runBlocking {
        val totalSeconds = 70
        val totalPackets = totalSeconds * 50
        val precomputePackets = 60 * 50
        val track = FloatArray(totalSeconds * Limits.ASR_RATE) { i -> ((i / (10 * Limits.ASR_RATE)) % 3 + 1) * 0.1f }
        val (audioFile, out, muxer) = openLiveOpus("missing-voices.opus", precomputePackets)

        val pre = newLane("pre-no-vox", "note-no-vox", audioFile, SpeechModel.PARAKEET, "diar_novox_")
        pre.precomputeModels.readyParts = setOf(ModelPart.SPEECH, ModelPart.SPEAKERS)
        val preEngines = parakeetEngines(pre.script, track, totalSeconds)
        assertEquals(
            PrecomputeStopReason.YIELDED,
            runSinglePassPrecompute(pre, audioFile, SpeechModel.PARAKEET, preEngines, track),
        )
        assertFalse(pre.storage.saved.last().speakerFailure)
        assertNull("Precompute without voice model must mark voiceClips absent", pre.storage.saved.last().voiceClips)

        finishLiveOpus(out, muxer, precomputePackets, totalPackets)

        pre.script.windows.clear()
        // Post-Stop batch job has VOICES installed and must discard & recompute to collect voice clips.
        assertEquals(JobOutcome.Done, runBatchJob(pre, SpeechModel.PARAKEET, preEngines))
        assertEquals(1, pre.storage.discardedCount)
        assertEquals(listOf(0L, 26_000L, 52_000L), pre.script.windows)

        val batch = newLane("batch-no-vox", "note-no-vox", audioFile, SpeechModel.PARAKEET, "diar_novox_")
        val batchEngines = parakeetEngines(batch.script, track, totalSeconds)
        assertEquals(JobOutcome.Done, runBatchJob(batch, SpeechModel.PARAKEET, batchEngines))
        assertEquivalentSealedOutputs(batch, pre)
    }

    @Test fun malformedWhistleSegmentCacheEntryTreatedAsCacheMissThroughPostStopTranscription() = runBlocking {
        val totalSeconds = 23
        val totalPackets = totalSeconds * 50
        val precomputePackets = 14 * 50 + 5
        val track = FloatArray(totalSeconds * Limits.ASR_RATE) { i ->
            val sec = i / Limits.ASR_RATE
            if (sec in 0..4 || sec in 7..11 || sec in 14..18) (((i % 389) - 194) / 1024f) else 0f
        }
        val (audioFile, out, muxer) = openLiveOpus("malformed-whistle-cache.opus", precomputePackets)

        val pre = newLane("pre-wm", "note-wm", audioFile, SpeechModel.INCLUDED, "diar_wm_")
        val preCalls = AtomicInteger()
        val preEngines = whistleEngines(pre.storage.whistleCacheFile, pre.script, track, totalSeconds, preCalls)
        assertEquals(
            PrecomputeStopReason.YIELDED,
            runSinglePassPrecompute(pre, audioFile, SpeechModel.INCLUDED, preEngines, track),
        )
        assertEquals(2, preCalls.get())
        assertTrue(pre.storage.whistleCacheFile.isFile)

        // Replace one cached segment entry with {"words":[{}]} on disk.
        val cacheJson = JSONObject(pre.storage.whistleCacheFile.readText(Charsets.UTF_8))
        val segments = cacheJson.getJSONObject("segments")
        val firstKey = segments.keys().next()
        segments.getJSONObject(firstKey).put(
            "jsonBase64",
            Base64.getEncoder().encodeToString("""{"words":[{}]}""".toByteArray(Charsets.UTF_8)),
        )
        pre.storage.whistleCacheFile.writeText(cacheJson.toString())

        finishLiveOpus(out, muxer, precomputePackets, totalPackets)

        // Post-Stop transcription must treat the malformed entry as a cache miss (1 miss + 1 tail = 2 calls),
        // keeping the other valid cached segment as a hit, and complete cleanly without UNKNOWN failure.
        val postStopCalls = AtomicInteger()
        val resumeEngines = whistleEngines(pre.storage.whistleCacheFile, pre.script, track, totalSeconds, postStopCalls)
        assertEquals(JobOutcome.Done, runBatchJob(pre, SpeechModel.INCLUDED, resumeEngines))
        assertEquals(2, postStopCalls.get())
        assertEquals(0, pre.storage.discardedCount)

        val batch = newLane("batch-wm", "note-wm", audioFile, SpeechModel.INCLUDED, "diar_wm_")
        val batchCalls = AtomicInteger()
        val batchEngines = whistleEngines(batch.storage.whistleCacheFile, batch.script, track, totalSeconds, batchCalls)
        assertEquals(JobOutcome.Done, runBatchJob(batch, SpeechModel.INCLUDED, batchEngines))
        assertEquivalentSealedOutputs(batch, pre)
    }

    @Test fun lowMemoryPreflightAndDynamicMidRunSuppressionReturnLowMemoryAndRecoverPostStop() = runBlocking {
        val totalSeconds = 70
        val totalPackets = totalSeconds * 50
        val precomputePackets = 60 * 50
        val track = FloatArray(totalSeconds * Limits.ASR_RATE) { i -> ((i / (10 * Limits.ASR_RATE)) % 3 + 1) * 0.1f }
        val (audioFile, out, muxer) = openLiveOpus("lowmem-pre.opus", precomputePackets)

        val lowMemFlag = AtomicBoolean(true)
        val baseLane = newLane("pre-lowmem", "note-lowmem", audioFile, SpeechModel.PARAKEET, "diar_lm_")
        val lane = Lane(
            noteId = baseLane.noteId,
            storage = baseLane.storage,
            voices = baseLane.voices,
            script = baseLane.script,
            env = JobEnvironment(
                placeholderLabel = { "Speaker $it" },
                inference = inference,
                speakerPrefix = { "diar_lm_" },
                lowMemory = { lowMemFlag.get() },
            ),
            precomputeModels = baseLane.precomputeModels,
        )
        val preEngines = parakeetEngines(lane.script, track, totalSeconds)

        // 1. Preflight low memory stops immediately with LOW_MEMORY and 0 checkpoints.
        assertEquals(
            PrecomputeStopReason.LOW_MEMORY,
            runSinglePassPrecompute(lane, audioFile, SpeechModel.PARAKEET, preEngines, track),
        )
        assertTrue(lane.storage.saved.isEmpty())

        // 2. Dynamic mid-run low memory set on first checkpoint stops at next step with LOW_MEMORY.
        lowMemFlag.set(false)
        val midStopReason = RecordingPrecomputer(
            noteId = lane.noteId,
            audioFile = audioFile,
            speechModel = SpeechModel.PARAKEET,
            storage = lane.storage,
            voices = lane.voices,
            models = lane.precomputeModels,
            engines = preEngines,
            env = lane.env,
            openDecoder = { file, _, start -> ScriptedOggPrefixDecoder(file, track, start) },
            onCheckpointed = { lowMemFlag.set(true) },
        ).run()
        assertEquals(PrecomputeStopReason.LOW_MEMORY, midStopReason)
        assertTrue(lane.storage.saved.isNotEmpty())

        // 3. Clearing dynamic lowMemory allows post-Stop TranscriptionJob to finish identically to batch.
        lowMemFlag.set(false)
        finishLiveOpus(out, muxer, precomputePackets, totalPackets)
        assertEquals(JobOutcome.Done, runBatchJob(lane, SpeechModel.PARAKEET, preEngines))

        val batch = newLane("batch-lowmem", "note-lowmem", audioFile, SpeechModel.PARAKEET, "diar_lm_")
        val batchEngines = parakeetEngines(batch.script, track, totalSeconds)
        assertEquals(JobOutcome.Done, runBatchJob(batch, SpeechModel.PARAKEET, batchEngines))
        assertEquivalentSealedOutputs(batch, lane)
    }
}
