package io.codemyriad.cassini.qualification

import android.content.Context
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.audioDecoder
import io.codemyriad.cassini.core.audio.PcmAudio
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.OggOpus
import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.core.speakers.NoteVoiceCodec
import io.codemyriad.cassini.core.speakers.PrintWindow
import io.codemyriad.cassini.core.speakers.SpeakerPrint
import io.codemyriad.cassini.core.speakers.Voiceprints
import io.codemyriad.cassini.engine.capture.CaptureFactory
import io.codemyriad.cassini.engine.capture.MicrophoneCapture
import io.codemyriad.cassini.engine.job.NativeBudget
import io.codemyriad.cassini.engine.speakers.VoiceprintExtractor
import io.codemyriad.cassini.recording.CaptureFormat
import io.codemyriad.cassini.recording.CaptureSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 3-speaker real-speech stimulus (`Voice_A`, `Voice_B`, `Voice_C`), CAM++ enrollment, and production-codec
 * capture sources for D-1126 physical handset qualification.
 *
 * Uses a compact ~40 s 48 kHz cycle buffer (~3.8 MiB constant RAM) so both 5-min and 30-min runs add negligible
 * test-harness heap overhead on 4 GB handsets.
 */
internal class MultiSpeakerSpeechTrack(
    val cycle48k: ShortArray,
    val speakerClips16k: List<Pair<String, FloatArray>>,
) {
    fun readFrames48k(frameOffset: Long, dest: ShortArray, count: Int) {
        val len = cycle48k.size
        var idx = (frameOffset % len).toInt()
        for (i in 0 until count) {
            dest[i] = cycle48k[idx]
            idx++
            if (idx == len) idx = 0
        }
    }

    companion object {
        private const val RATE_16K = 16_000
        private const val RATE_48K = 48_000
        private const val GAP_SECONDS = 0.8
        private const val VOICE_C_RATIO = 1.28 // Distinct higher-formant/faster speaker profile vs 1.00x FLEURS
        private const val JFK_DC_OFFSET_LSB = 2060f
        private const val PCM16_SCALE = 32768f
        private const val TARGET_RMS = 0.12f

        val SYNTHETIC_VOICE_LABELS = listOf("Voice_A", "Voice_B", "Voice_C")

        fun fromAssets(targetContext: Context, testAssetsContext: Context): MultiSpeakerSpeechTrack {
            val cache = targetContext.cacheDir
            val fleursFile = copyAsset(testAssetsContext, cache, "fleurs-it-short.wav")
            val jfkFile = copyAsset(testAssetsContext, cache, "dc-quiet-jfk-v1.wav")
            try {
                val decoder = audioDecoder(targetContext)
                val fleurs16k = normalizeRms(decoder.decode(Uri.fromFile(fleursFile)).samples)
                // Restore dc-quiet-jfk-v1.wav (48 kHz with -2060 LSB DC offset) to clean 16 kHz speech.
                val jfkDecoded16k = decoder.decode(Uri.fromFile(jfkFile)).samples
                val offset = JFK_DC_OFFSET_LSB / PCM16_SCALE
                val jfkClean16k = normalizeRms(FloatArray(jfkDecoded16k.size) { jfkDecoded16k[it] + offset })
                val shifted16k = normalizeRms(resampleLinear(fleurs16k, 1.0 / VOICE_C_RATIO))

                val clips16k = listOf(
                    SYNTHETIC_VOICE_LABELS[0] to fleurs16k,
                    SYNTHETIC_VOICE_LABELS[1] to jfkClean16k,
                    SYNTHETIC_VOICE_LABELS[2] to shifted16k,
                )
                val gap48k = ShortArray((GAP_SECONDS * RATE_48K).roundToInt())
                val parts48k = clips16k.map { (_, samples16k) -> floatsToPcm16(resampleLinear(samples16k, 3.0)) }
                val total48k = parts48k.sumOf { it.size + gap48k.size }
                val cycle = ShortArray(total48k)
                var pos = 0
                for (part in parts48k) {
                    part.copyInto(cycle, pos)
                    pos += part.size
                    gap48k.copyInto(cycle, pos)
                    pos += gap48k.size
                }
                return MultiSpeakerSpeechTrack(cycle, clips16k)
            } finally {
                fleursFile.delete()
                jfkFile.delete()
            }
        }

        suspend fun enrollThreeVoices(graph: AppGraph, track: MultiSpeakerSpeechTrack): EnrolledVoiceSummary {
            val modelName = ModelCatalog.VOICEPRINT.artifacts.single().name
            val modelFile = File(ModelCatalog.VOICEPRINT.path(graph.paths.modelRoot, modelName))
            check(modelFile.isFile) { "CAM++ voiceprint model missing at $modelFile" }
            val extractedPrints = NativeBudget.withBudget {
                track.speakerClips16k.map { (label, samples16k) ->
                    val windows = mapOf("S" to listOf(PrintWindow(0, samples16k.size)))
                    val extracted = VoiceprintExtractor.compute(
                        modelFile,
                        PcmAudio(samples16k, RATE_16K),
                        windows,
                    ) {}
                    val (embedding, seconds) = requireNotNull(extracted["S"]) { "No CAM++ print for $label" }
                    label to SpeakerPrint(embedding, seconds, match = null)
                }
            }
            var maxOffDiag = -1f
            for (i in extractedPrints.indices) {
                for (j in i + 1 until extractedPrints.size) {
                    val a = extractedPrints[i].second.embedding
                    val b = extractedPrints[j].second.embedding
                    maxOffDiag = maxOf(maxOffDiag, Voiceprints.cosine(a, b))
                }
            }
            val voiceRepo = graph.awaitCatalogue().voices
            voiceRepo.forgetEveryone()
            val seedNoteId = UUID.randomUUID().toString()
            val seedPrints = extractedPrints.mapIndexed { idx, (_, print) -> "seed/S$idx" to print }.toMap()
            val seedFile = File(File(graph.paths.voices, "notes"), "$seedNoteId.json")
            seedFile.parentFile?.mkdirs()
            seedFile.writeText(NoteVoiceCodec.encode(ModelCatalog.voiceprintModel.sha256, seedPrints))
            val enrolled = extractedPrints.mapIndexed { idx, (label, _) ->
                requireNotNull(voiceRepo.enrol(seedNoteId, "seed", "S$idx", null, label)) {
                    "Failed to enrol synthetic voice $label"
                }
            }
            voiceRepo.deleteForNote(seedNoteId)
            val storedNames = voiceRepo.people().first().voices.map { it.name }.sorted()
            check(storedNames.size == SYNTHETIC_VOICE_LABELS.size) { "Expected 3 enrolled voices, got $storedNames" }
            return EnrolledVoiceSummary(enrolled.map { it.name }, maxOffDiag)
        }

        private fun copyAsset(assetsContext: Context, dir: File, name: String): File =
            File(dir, "qual-$name").also { target ->
                assetsContext.assets.open(name).use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
            }

        internal fun normalizeRms(samples: FloatArray): FloatArray {
            if (samples.isEmpty()) return samples
            val mean = samples.sumOf { it.toDouble() } / samples.size
            var energy = 0.0
            for (s in samples) {
                val ac = s - mean
                energy += ac * ac
            }
            val rms = sqrt(energy / samples.size).toFloat().coerceAtLeast(1e-6f)
            val gain = TARGET_RMS / rms
            return FloatArray(samples.size) { ((samples[it] - mean.toFloat()) * gain).coerceIn(-0.92f, 0.92f) }
        }

        internal fun resampleLinear(samples: FloatArray, factor: Double): FloatArray {
            if (samples.isEmpty() || factor <= 0.0) return FloatArray(0)
            val outCount = (samples.size * factor).roundToInt().coerceAtLeast(1)
            val step = 1.0 / factor
            return FloatArray(outCount) { i ->
                val pos = i * step
                val idx = pos.toInt().coerceIn(0, samples.lastIndex)
                val next = (idx + 1).coerceAtMost(samples.lastIndex)
                val frac = (pos - idx).toFloat()
                samples[idx] + (samples[next] - samples[idx]) * frac
            }
        }

        internal fun floatsToPcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) { i ->
            (samples[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
}

internal data class EnrolledVoiceSummary(
    val names: List<String>,
    val maxPairwiseCosine: Float,
)

/**
 * Deterministic hardware `MediaCodec("audio/opus")` + `OggOpus.LiveMuxer` capture source for paired
 * `AFTER_STOP` vs `TRANSCRIBE_WHILE_RECORDING` equivalence and pressure-injection runs.
 */
internal class DeterministicOpusCaptureSource(
    private val track: MultiSpeakerSpeechTrack,
    private val totalSeconds: Int,
    private val splitAfterSeconds: Int? = null,
    private val serial: Int = FIXED_SERIAL,
) : CaptureSource {
    val prefixCommitted = CompletableDeferred<Long>()
    val tailCommitted = CompletableDeferred<Long>()
    private val releaseTail = CountDownLatch(1)

    fun allowTail() = releaseTail.countDown()

    override suspend fun format(): CaptureFormat = CaptureFormat.OPUS

    override fun start(file: File, onError: () -> Unit): MicrophoneCapture = Capture(file, onError)

    private inner class Capture(override val file: File, private val onError: () -> Unit) : MicrophoneCapture {
        private val output = FileOutputStream(file)
        private val stream = output.buffered(1 shl 16)
        private val codec = MediaCodec.createEncoderByType("audio/opus")
        private val stopSignal = CountDownLatch(1)

        @Volatile private var samples = 0L

        @Volatile override var paused = false
            private set

        @Volatile private var failure: Throwable? = null

        private var muxer: OggOpus.LiveMuxer? = null
        private val thread: Thread

        override val opus = true
        override val elapsedMs: Long get() = samples * 1000L / RATE
        override val amplitude: Int get() = if (paused) 0 else 8_000

        init {
            codec.configure(
                MediaFormat.createAudioFormat("audio/opus", RATE, 1).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * 2)
                },
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            codec.start()
            thread = Thread(::encodeLoop, "cassini-qual-opus").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }

        private fun encodeLoop() {
            val frame = ShortArray(FRAME)
            val info = MediaCodec.BufferInfo()
            val totalSamples = totalSeconds.toLong() * RATE
            val prefixTarget = (splitAfterSeconds?.toLong()?.times(RATE) ?: totalSamples).coerceAtMost(totalSamples)
            try {
                encodeUntil(prefixTarget, frame, info)
                stream.flush()
                output.fd.sync()
                prefixCommitted.complete(file.length())

                if (prefixTarget < totalSamples) {
                    releaseTail.await(WAIT_MINUTES, TimeUnit.MINUTES)
                    encodeUntil(totalSamples, frame, info)
                    stream.flush()
                    output.fd.sync()
                }
                tailCommitted.complete(file.length())

                stopSignal.await(WAIT_MINUTES, TimeUnit.MINUTES)
                sealEos(frame, info)
            } catch (@Suppress("TooGenericExceptionCaught") err: Throwable) {
                failure = err
                prefixCommitted.completeExceptionally(err)
                tailCommitted.completeExceptionally(err)
                runCatching { muxer?.finish() }
            } finally {
                closeResources()
            }
            if (failure != null) onError()
        }

        private fun sealEos(frame: ShortArray, info: MediaCodec.BufferInfo) {
            queue(ShortArray(FRAME), FRAME, 0, info)
            queue(frame, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM, info)
            val deadline = SystemClock.elapsedRealtime() + EOS_DRAIN_MS
            while (!drain(info, DRAIN_US) && SystemClock.elapsedRealtime() < deadline) Unit
            muxer?.let { it.finish(it.preSkip + samples) }
        }

        private fun closeResources() {
            runCatching {
                stream.flush()
                output.fd.sync()
            }
            runCatching { stream.close() }
            runCatching { codec.stop() }
            codec.release()
        }

        private fun encodeUntil(targetSamples: Long, frame: ShortArray, info: MediaCodec.BufferInfo) {
            while (samples < targetSamples && stopSignal.count > 0L) {
                val n = minOf(FRAME.toLong(), targetSamples - samples).toInt()
                track.readFrames48k(samples, frame, n)
                queue(frame, n, 0, info)
                samples += n
                drain(info, 0L)
            }
            drain(info, DRAIN_US)
        }

        private fun queue(pcm: ShortArray, n: Int, flags: Int, info: MediaCodec.BufferInfo) {
            var index: Int
            do {
                index = codec.dequeueInputBuffer(DRAIN_US)
                if (index < 0) drain(info, 0L)
            } while (index < 0)
            val input = codec.getInputBuffer(index)!!.apply {
                clear()
                order(ByteOrder.LITTLE_ENDIAN)
            }
            for (i in 0 until n) input.putShort(pcm[i])
            codec.queueInputBuffer(index, 0, n * 2, samples * 1_000_000L / RATE, flags)
        }

        private fun drain(info: MediaCodec.BufferInfo, timeoutUs: Long): Boolean {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, timeoutUs)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    codec.outputFormat.getByteBuffer("csd-0")?.let { b ->
                        val bytes = ByteArray(b.remaining())
                        b.get(bytes)
                        config(bytes)
                    }
                    continue
                }
                if (index < 0) return false
                val buffer = codec.getOutputBuffer(index)!!
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                buffer.get(bytes)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    config(bytes)
                } else if (bytes.isNotEmpty()) {
                    (muxer ?: error("No OpusHead from platform encoder")).add(bytes)
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
            }
        }

        private fun config(bytes: ByteArray) {
            if (muxer != null) return
            val marker = "OpusHead".toByteArray()
            val start = (0..(bytes.size - 19)).firstOrNull { p ->
                marker.indices.all { bytes[p + it] == marker[it] }
            } ?: return
            muxer = OggOpus.LiveMuxer(
                stream,
                bytes.copyOfRange(start, start + 19),
                CassiniDocument.tagsPacket(emptyList()),
                serial = serial,
            )
        }

        override fun togglePause() {
            paused = !paused
        }

        override suspend fun stop(): File = runInterruptible(Dispatchers.IO) {
            releaseTail.countDown()
            stopSignal.countDown()
            thread.join(JOIN_MS)
            check(!thread.isAlive) { "Qualification Opus capture thread did not stop" }
            failure?.let { throw IllegalStateException("Qualification Opus capture failed", it) }
            file
        }

        override fun release() {
            releaseTail.countDown()
            stopSignal.countDown()
            thread.join(JOIN_MS)
        }
    }

    companion object {
        private const val RATE = 48_000
        private const val FRAME = RATE / 50
        private const val BIT_RATE = 48_000
        private const val DRAIN_US = 10_000L
        private const val EOS_DRAIN_MS = 5_000L
        private const val JOIN_MS = 15_000L
        private const val WAIT_MINUTES = 30L
        const val FIXED_SERIAL = 0xCA55126
    }
}

/**
 * Post-read observer installed on production `OpusCapture` (`CaptureFactory.OpusReadHook`) for the 30-minute
 * (`1,800 s`) capture invulnerability run.
 *
 * Production `OpusCapture` owns its actual 1-second `AudioRecord` buffer (`maxOf(minimum, RATE * 2)` bytes =
 * `48,000` samples), capture thread, `MediaCodec`, `OggOpus.LiveMuxer`, flush/sync cadence, and pause/stop
 * lifecycle. This hook inspects HAL `AudioTimestamp` continuity + inter-frame cadence against `OpusCapture`'s
 * actual ring buffer and overlays 3-speaker speech onto silent rack-room mic audio so full VAD/ASR/diarization
 * precomputation executes simultaneously.
 */
internal class ProductionOpusEnduranceObserver(
    private val track: MultiSpeakerSpeechTrack,
    private val targetSeconds: Int,
    private val onProgressSeconds: (Int) -> Unit = {},
) : CaptureFactory.OpusReadHook {
    val targetReached = CompletableDeferred<CaptureHealth>()
    private val speechFrame = ShortArray(FRAME)
    private val timestamp = AudioTimestamp()
    private val halTracker = HandsetQualificationAudit.HalTimestampDropTracker()
    private val timingDroppedCount = AtomicInteger(0)
    private val maxFrameIntervalMsValue = AtomicLong(0L)
    private val micPeakValue = AtomicInteger(0)

    @Volatile private var lastFrameAtMs: Long = 0L

    @Volatile private var lastReportedSec: Int = 0

    data class CaptureHealth(
        val capturedSamples: Long,
        val capturedSeconds: Double,
        val halSummary: HandsetQualificationAudit.HalDropSummary,
        val maxFrameIntervalMs: Long,
        val ringBufferMs: Long,
        val micPeakAmplitude: Int,
    )

    override fun onRead(
        record: AudioRecord,
        ringSamples: Int,
        frame: ShortArray,
        count: Int,
        sampleOffset: Long,
    ) {
        val now = SystemClock.elapsedRealtime()
        val ringMs = ringSamples * 1000L / RATE
        val prevAt = lastFrameAtMs
        if (prevAt > 0L) {
            val intervalMs = (now - prevAt).coerceAtLeast(0L)
            maxFrameIntervalMsValue.accumulateAndGet(intervalMs, ::maxOf)
            if (intervalMs > ringMs) timingDroppedCount.incrementAndGet()
        }
        lastFrameAtMs = now

        mixSpeechOverMic(frame, count, sampleOffset)
        val nextSamples = sampleOffset + count
        checkHalTimestamp(record, ringSamples, nextSamples)
        reportProgressAndTarget(ringMs, nextSamples)
    }

    private fun mixSpeechOverMic(frame: ShortArray, count: Int, sampleOffset: Long) {
        val step = minOf(count, speechFrame.size)
        track.readFrames48k(sampleOffset, speechFrame, step)
        var sum = 0L
        for (i in 0 until step) sum += frame[i]
        val mean = (sum / step).toInt()
        for (i in 0 until step) {
            val ac = abs(frame[i] - mean)
            if (ac > micPeakValue.get()) micPeakValue.set(ac)
            val mixed = speechFrame[i].toInt() + ((frame[i] - mean) / 64)
            frame[i] = mixed.coerceIn(-32768, 32767).toShort()
        }
    }

    private fun checkHalTimestamp(record: AudioRecord, ringSamples: Int, nextSamples: Long) {
        if (nextSamples % RATE != 0L ||
            record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS
        ) {
            return
        }
        halTracker.record(timestamp.framePosition, nextSamples, ringSamples)
    }

    private fun reportProgressAndTarget(ringMs: Long, nextSamples: Long) {
        val secNow = (nextSamples / RATE).toInt()
        if (secNow >= lastReportedSec + 30) {
            lastReportedSec = secNow
            onProgressSeconds(secNow)
        }
        if (!targetReached.isCompleted && nextSamples >= targetSeconds.toLong() * RATE) {
            targetReached.complete(
                CaptureHealth(
                    capturedSamples = nextSamples,
                    capturedSeconds = nextSamples.toDouble() / RATE,
                    halSummary = halTracker.snapshot(timingDroppedCount.get()),
                    maxFrameIntervalMs = maxFrameIntervalMsValue.get(),
                    ringBufferMs = ringMs,
                    micPeakAmplitude = micPeakValue.get(),
                ),
            )
        }
    }

    companion object {
        private const val RATE = 48_000
        private const val FRAME = RATE / 50
    }
}

/** Forces `CaptureFormat.M4A` (`AacCapture` via `CaptureFactory.create`) even on API 29+ to exercise AAC fallback. */
internal class ExplicitAacCaptureSource(private val context: Context) : CaptureSource {
    override suspend fun format(): CaptureFormat = CaptureFormat.M4A
    override fun start(file: File, onError: () -> Unit): MicrophoneCapture =
        CaptureFactory.create(context.applicationContext, file, onError)
}
