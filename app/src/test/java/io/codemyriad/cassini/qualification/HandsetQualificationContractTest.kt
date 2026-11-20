package io.codemyriad.cassini.qualification

import io.codemyriad.cassini.core.asr.IncrementalState
import io.codemyriad.cassini.core.asr.TranscriptionMode
import io.codemyriad.cassini.core.checkpoint.CheckpointOrigin
import io.codemyriad.cassini.core.checkpoint.CheckpointState
import io.codemyriad.cassini.core.checkpoint.CommittedPrefix
import io.codemyriad.cassini.core.checkpoint.PrefixDecision
import io.codemyriad.cassini.core.checkpoint.RecognitionState
import io.codemyriad.cassini.core.checkpoint.WhistleCachedSegment
import io.codemyriad.cassini.core.checkpoint.WhistleSegmentCache
import io.codemyriad.cassini.core.checkpoint.WordLogRef
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.OggOpus
import io.codemyriad.cassini.core.format.OggOpusPrefixScanner
import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.engine.job.Recognizer
import io.codemyriad.cassini.engine.whistle.FileWhistleSegmentStore
import io.codemyriad.cassini.engine.whistle.WhistlePrefixAudit
import io.codemyriad.cassini.engine.whistle.WhistleRecognizer
import io.codemyriad.cassini.engine.whistle.WhistleReplay
import io.codemyriad.cassini.engine.whistle.WhistleResult
import io.codemyriad.cassini.engine.whistle.WhistleTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Deterministic JVM contract test for the D-1126 handset qualification invariants (zero sleeps, D88):
 * - Live Ogg Opus committed prefix bytes match EOS-sealed `.opus` container bytes via [OggOpusPrefixScanner].
 * - `CheckpointState.validateWithResult` accepts valid `oggOpusCommittedBytes` precompute checkpoints after EOS
 *   finalization (`length:mtime` changed) and silently falls back (`DISCARD_AND_FULL_DECODE`) on AAC or modified
 *   prefixes without ever failing the user's job.
 */
class HandsetQualificationContractTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun liveOggOpusPrefixMatchesFinalizedEosContainerAndValidatesPrecomputeCheckpoint() {
        val file = temp.newFile("capture.opus")
        val packet = ByteArray(48) { i -> if (i == 0) 0xfc.toByte() else (i * 13 + 3).toByte() }
        val out = FileOutputStream(file)
        val muxer = OggOpus.LiveMuxer(out, opusHead(), CassiniDocument.tagsPacket(emptyList()), serial = 0xCA55126)
        repeat(200) { muxer.add(packet) }
        out.flush()

        val scanner = OggOpusPrefixScanner(file)
        assertTrue(scanner.poll().isNotEmpty())
        val committedBytes = scanner.committedBytes
        assertTrue(committedBytes > 0L)
        assertFalse(scanner.sawEos)
        val liveSha = scanner.committedSha256()
        val checkpointJson = precomputeCheckpointJson(file, committedBytes, liveSha)

        // Append tail frames and seal EOS page (changing file length & signature, as OpusCapture.stop() does).
        repeat(50) { muxer.add(packet) }
        muxer.finish(312L + 250L * 960L)
        out.flush()
        out.close()

        val finalSha = OggOpusPrefixScanner.sha256Prefix(file, committedBytes)
        assertEquals(liveSha, finalSha)

        val expectedOpus = expectedFor(file, CommittedPrefix.KIND_OGG_OPUS_BYTES, finalSha)
        assertEquals(PrefixDecision.RESUME, CheckpointState.validateWithResult(checkpointJson, expectedOpus).decision)

        // API 26-28 AAC fallback kind mismatch on a precompute checkpoint must silently discard and full-decode.
        val expectedAac = expectedFor(file, CommittedPrefix.KIND_AAC_M4A, null)
        assertEquals(
            PrefixDecision.DISCARD_AND_FULL_DECODE,
            CheckpointState.validateWithResult(checkpointJson, expectedAac).decision,
        )
    }

    @Test
    fun transcriptionModePolicySkipsAacAndPreemptsOnForegroundWork() {
        val opusOk = TranscriptionMode.evaluate(
            rawMode = "transcribeWhileRecording",
            hasForegroundSavedJob = false,
            manualTranscribeRequested = false,
            platformSupportsPrecompute = true,
        )
        assertTrue(opusOk.spawnWorkerDuringRecording)
        assertTrue(opusOk.autoEnqueueOnStop)

        val aacFallback = TranscriptionMode.evaluate(
            rawMode = "transcribeWhileRecording",
            hasForegroundSavedJob = false,
            manualTranscribeRequested = false,
            platformSupportsPrecompute = false,
        )
        assertFalse(
            "AAC capture must never spawn Ogg-Opus prefix precomputation",
            aacFallback.spawnWorkerDuringRecording,
        )
        assertTrue("AAC capture must still auto-enqueue post-Stop transcription", aacFallback.autoEnqueueOnStop)
    }

    @Test
    fun whistleQualificationFailsWhenOneValidPrefixCacheEntryIsLost() {
        val segFile = temp.newFile("whistle-segments.json")
        val prefixSegments = listOf(
            FakeSeg(0L, 16_000L, "a".repeat(64), """{"words":[{"word":"uno","start":0.05,"end":0.40}]}"""),
            FakeSeg(16_000L, 32_000L, "b".repeat(64), """{"words":[{"word":"due","start":0.05,"end":0.40}]}"""),
            FakeSeg(32_000L, 48_000L, "c".repeat(64), """{"words":[{"word":"tre","start":0.05,"end":0.40}]}"""),
        )
        val tail = FakeSeg(
            48_000L,
            64_000L,
            "d".repeat(64),
            """{"words":[{"word":"quattro","start":0.05,"end":0.40}]}""",
        )
        val allSegments = prefixSegments + tail
        val fullPcm = FloatArray(64_000) { i -> ((i % 251) - 125) / 512f }

        // 1. Precompute settles 3 prefix segments into FileWhistleSegmentStore + RecognitionState checkpoint.
        val (savedRecognition, precomputedSpans) = precomputeWhistlePrefix(segFile, prefixSegments, fullPcm)
        assertEquals(3, precomputedSpans.size)

        // 2. Intact post-Stop resume: all 3 prefix segments hit cache, only 1 tail segment runs neural inference.
        val intactPost = runPostStopWhistle(savedRecognition, FileWhistleSegmentStore(segFile), allSegments, fullPcm)
        WhistlePrefixAudit.verifyNoNeuralPrefixRedecode(
            precomputedPrefixSpans = precomputedSpans,
            postStopCacheHitSpans = intactPost.cacheHitSpans,
            postStopNeuralSpans = intactPost.neuralSpans,
            baselineTotalSegments = 4,
        )

        // 3. Lose ONE valid prefix cache entry (segment #2) before Stop: total (2 hits + 2 neural = 4) still equals
        //    baseline 4, yet WhistlePrefixAudit must fail qualification naming the re-decoded prefix segment.
        dropSingleCachedSegment(segFile, prefixSegments[1].sha256)
        val degradedPost = runPostStopWhistle(savedRecognition, FileWhistleSegmentStore(segFile), allSegments, fullPcm)
        assertEquals(4, degradedPost.cacheHitSpans.size + degradedPost.neuralSpans.size)
        val err = assertThrows(AssertionError::class.java) {
            WhistlePrefixAudit.verifyNoNeuralPrefixRedecode(
                precomputedPrefixSpans = precomputedSpans,
                postStopCacheHitSpans = degradedPost.cacheHitSpans,
                postStopNeuralSpans = degradedPost.neuralSpans,
                baselineTotalSegments = 4,
            )
        }
        assertTrue(err.message.orEmpty().contains(precomputedSpans[1]))
    }

    private fun precomputeWhistlePrefix(
        segFile: File,
        prefixSegments: List<FakeSeg>,
        fullPcm: FloatArray,
    ): Pair<RecognitionState, List<String>> {
        val preTransport = FakeWhistleTransport(prefixSegments)
        val preRec = WhistleRecognizer(
            restored = null,
            callbacks = noopCallbacks(),
            replay = WhistleReplay { _, _ -> },
            open = { emit -> preTransport.bind(emit) },
            segmentStore = FileWhistleSegmentStore(segFile),
        )
        preRec.accept(fullPcm, 48_000)
        val saved = preRec.checkpoint()
        preRec.close()
        return saved to preTransport.neuralSpans.toList()
    }

    private fun dropSingleCachedSegment(segFile: File, droppedSha256: String) {
        val trimmed = WhistleSegmentCache(WhistleRecognizer.PIPELINE).apply {
            WhistleSegmentCache.readFrom(segFile, WhistleRecognizer.PIPELINE).entries()
                .filter { it.pcmSha256 != droppedSha256 }
                .forEach { put(it.pcmSha256, it.sampleCount, it.json) }
        }
        trimmed.writeTo(segFile)
    }

    private fun noopCallbacks() = Recognizer.Callbacks(
        onChunk = { _, _, words -> words },
        onStage = { _, _, _ -> },
        onProgress = {},
    )

    @Test
    fun whistleQualificationRejectsPartialHitRatioEvenWhenSegmentTotalMatchesBaseline() {
        val prefix38 = (0 until 38).map { "seg-$it:sha$it" }
        val tail6 = (38 until 44).map { "seg-$it:sha$it" }
        assertThrows(AssertionError::class.java) {
            WhistlePrefixAudit.verifyNoNeuralPrefixRedecode(
                precomputedPrefixSpans = prefix38,
                postStopCacheHitSpans = prefix38.take(1),
                postStopNeuralSpans = prefix38.drop(1) + tail6,
                baselineTotalSegments = 44,
            )
        }
    }

    private data class FakeSeg(val start: Long, val end: Long, val sha256: String, val json: String)

    private class FakeWhistleTransport(private val segments: List<FakeSeg>) : WhistleTransport {
        private val seeded = HashMap<Pair<String, Int>, ByteArray>()
        private var accepted = 0L
        private var nextIdx = 0
        private lateinit var emit: (WhistleResult) -> Unit
        val cacheHitSpans = ArrayList<String>()
        val neuralSpans = ArrayList<String>()

        fun bind(sink: (WhistleResult) -> Unit): WhistleTransport {
            emit = sink
            return this
        }

        override fun seedCache(entries: List<WhistleCachedSegment>) {
            entries.forEach { seeded[it.pcmSha256 to it.sampleCount] = it.json }
        }

        override fun accept(samples: FloatArray, count: Int) {
            accepted += count
            while (nextIdx < segments.size && segments[nextIdx].end <= accepted) {
                val seg = segments[nextIdx++]
                val span = WhistlePrefixAudit.spanKey(seg.start, seg.end, seg.sha256)
                val cached = seeded[seg.sha256 to (seg.end - seg.start).toInt()]
                val payload = if (cached != null) {
                    cacheHitSpans.add(span)
                    cached
                } else {
                    neuralSpans.add(span)
                    seg.json.toByteArray()
                }
                emit(WhistleResult(seg.start, seg.end, payload, seg.sha256))
            }
        }

        override fun finish() = Unit
        override fun close() = Unit
    }

    private fun runPostStopWhistle(
        restored: RecognitionState,
        store: FileWhistleSegmentStore,
        segments: List<FakeSeg>,
        fullPcm: FloatArray,
    ): FakeWhistleTransport {
        val transport = FakeWhistleTransport(segments)
        val rec = WhistleRecognizer(
            restored = restored,
            callbacks = noopCallbacks(),
            replay = WhistleReplay { limit, sink -> sink(fullPcm, limit.toInt()) },
            open = { emit -> transport.bind(emit) },
            segmentStore = store,
        )
        rec.accept(fullPcm.copyOfRange(48_000, 64_000), 16_000)
        rec.finish(4_000L)
        rec.close()
        return transport
    }

    private fun opusHead(): ByteArray = ByteArray(19).also { h ->
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(h, 0)
        h[8] = 1
        h[9] = 1
        ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).putShort(10, 312).putInt(12, 48_000)
    }

    private fun precomputeCheckpointJson(file: File, committedBytes: Long, liveSha: String): JSONObject {
        val state = CheckpointState(
            source = file.name,
            signature = CheckpointState.signature(file),
            model = SpeechModel.PARAKEET.identity,
            speakers = ModelCatalog.diarizationModel.sha256,
            recognition = RecognitionState(100L, IncrementalState(32_000L, 32_000L, FloatArray(0), emptyList())),
            encoder = null,
            diarizer = null,
            speakerPrefix = "diar_qual_",
            speakerFailure = false,
            clipSamples = 0,
            voiceClips = emptyMap(),
            positionSamples = 32_000L,
            doneMs = 2_000L,
            durationMs = 0L,
            elapsedMs = 150L,
            origin = CheckpointOrigin.PRECOMPUTE,
            committedPrefix = CommittedPrefix(
                kind = CommittedPrefix.KIND_OGG_OPUS_BYTES,
                committedUnitCount = committedBytes,
                prefixSha256 = liveSha,
            ),
        )
        return CheckpointState.encode(state, WordLogRef.EMPTY)
    }

    private fun expectedFor(file: File, kind: String, prefixSha: String?) = CheckpointState.Expected(
        source = file.name,
        signature = CheckpointState.signature(file),
        modelRevision = SpeechModel.PARAKEET.identity,
        speakersSha256 = ModelCatalog.diarizationModel.sha256,
        clipsBytes = 0L,
        audioBytes = 0L,
        wordLogBytes = 0L,
        prefixKind = kind,
        finalizedUnitCount = file.length(),
        prefixSha256 = prefixSha,
        deferDecodedPcmCheck = true,
    )
}
