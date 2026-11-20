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

    @Test
    fun halTimestampDropTrackerDetectsFrameDeficitAndRejectsUnavailableTimestamps() {
        val ringSamples = 48_000

        // 1. Clean continuity: HAL delta equals app delta across samples.
        val clean = HandsetQualificationAudit.HalTimestampDropTracker()
        assertFalse(clean.record(halFramePosition = 48_000L, appSamples = 48_000L, ringSamples = ringSamples))
        assertFalse(clean.record(halFramePosition = 96_000L, appSamples = 96_000L, ringSamples = ringSamples))
        val cleanSummary = clean.snapshot(timingDropEvents = 0)
        assertTrue(cleanSummary.halTimestampAvailable)
        assertEquals(0, cleanSummary.droppedBuffers)
        assertEquals(0L, cleanSummary.maxHalFrameDeficit)
        HandsetQualificationAudit.verifyCaptureHealth(cleanSummary, maxFrameIntervalMs = 48L, ringBufferMs = 1_000L)

        // 2. Known frame deficit (50,000 frames > 48,000 ring) starting after initial 48,000-sample baseline.
        //    Comparing deltas (98,000 - 48,000 = 50,000) catches the overrun that subtracting total appSamples hid.
        val deficit = HandsetQualificationAudit.HalTimestampDropTracker()
        assertFalse(deficit.record(halFramePosition = 48_000L, appSamples = 48_000L, ringSamples = ringSamples))
        assertTrue(deficit.record(halFramePosition = 146_000L, appSamples = 96_000L, ringSamples = ringSamples))
        val deficitSummary = deficit.snapshot(timingDropEvents = 0)
        assertTrue(deficitSummary.halTimestampAvailable)
        assertEquals(1, deficitSummary.halDropEvents)
        assertEquals(1, deficitSummary.droppedBuffers)
        assertEquals(50_000L, deficitSummary.maxHalFrameDeficit)
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyCaptureHealth(
                deficitSummary,
                maxFrameIntervalMs = 48L,
                ringBufferMs = 1_000L,
            )
        }

        // 3. Unavailable HAL timestamps (0 samples, or only 1 sample with no delta) must NOT report 0 drops.
        val noneSummary = HandsetQualificationAudit.HalTimestampDropTracker().snapshot(timingDropEvents = 0)
        assertFalse(noneSummary.halTimestampAvailable)
        assertEquals(null, noneSummary.droppedBuffers)
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyCaptureHealth(
                noneSummary,
                maxFrameIntervalMs = 48L,
                ringBufferMs = 1_000L,
            )
        }

        val single = HandsetQualificationAudit.HalTimestampDropTracker()
        single.record(halFramePosition = 48_000L, appSamples = 48_000L, ringSamples = ringSamples)
        val singleSummary = single.snapshot(timingDropEvents = 0)
        assertFalse(singleSummary.halTimestampAvailable)
        assertEquals(null, singleSummary.droppedBuffers)
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyCaptureHealth(
                singleSummary,
                maxFrameIntervalMs = 48L,
                ringBufferMs = 1_000L,
            )
        }
    }

    @Test
    fun longLaneProgressValidatorRejectsZeroCheckpointZeroResumeAndMissingTail() {
        val valid = HandsetQualificationAudit.LongLaneProgress(
            expectedDurationMs = 1_800_000L,
            precomputeCheckpoints = 68,
            resumedFromMs = 1_770_000L,
            wordCount = 3_115,
            lastWordEndMs = 1_795_000L,
        )
        HandsetQualificationAudit.verifyLongLaneProgress(valid)

        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyLongLaneProgress(valid.copy(precomputeCheckpoints = 0))
        }
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyLongLaneProgress(valid.copy(resumedFromMs = 0L))
        }
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyLongLaneProgress(valid.copy(resumedFromMs = 60_000L))
        }
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyLongLaneProgress(valid.copy(lastWordEndMs = 900_000L))
        }
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyLongLaneProgress(valid.copy(wordCount = 0))
        }
    }

    @Test
    fun laneEquivalenceValidatorRejectsShiftedTurnBoundaryInSpeechGap() {
        val enrolled = listOf("Voice_A", "Voice_B", "Voice_C")
        val baseline = sampleBaselineEquivalence()
        val shiftedGapTurns = listOf(
            HandsetQualificationAudit.ComparableTurn(speaker = 0, startMs = 0L, endMs = 560L),
            HandsetQualificationAudit.ComparableTurn(speaker = 1, startMs = 700L, endMs = 1_200L),
        )
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyLaneEquivalence(
                "PARAKEET",
                baseline,
                baseline.copy(turns = shiftedGapTurns),
                enrolled,
            )
        }
    }

    private fun sampleBaselineEquivalence(): HandsetQualificationAudit.EquivalenceSnapshot {
        val baselineTurns = listOf(
            HandsetQualificationAudit.ComparableTurn(speaker = 0, startMs = 0L, endMs = 500L),
            HandsetQualificationAudit.ComparableTurn(speaker = 1, startMs = 700L, endMs = 1_200L),
        )
        return HandsetQualificationAudit.EquivalenceSnapshot(
            captureOpusSha256 = "1".repeat(64),
            decodedPcmSha256 = "2".repeat(64),
            words = listOf(HandsetQualificationAudit.ComparableWord("hello", 100L, 450L, "diar_0")),
            turns = baselineTurns,
            turnCount = baselineTurns.size,
            speakerCount = 3,
            speakerLabels = mapOf("diar_0" to "Voice_A", "diar_1" to "Voice_B", "diar_2" to "Voice_C"),
            matchedVoices = listOf("diar_0:Voice_A:AUTO", "diar_1:Voice_B:AUTO", "diar_2:Voice_C:SUGGESTED"),
        )
    }

    @Test
    fun laneEquivalenceValidatorRequiresNonEmptyBaselineVoicesAndExactHashesAndSpeakerMetadata() {
        val enrolled = listOf("Voice_A", "Voice_B", "Voice_C")
        val baseline = sampleBaselineEquivalence()

        HandsetQualificationAudit.verifyLaneEquivalence("PARAKEET thermal", baseline, baseline.copy(), enrolled)

        // Empty baseline words or missing enrolled voices must fail even when candidate matches identically.
        val emptyWords = baseline.copy(words = emptyList())
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyLaneEquivalence("PARAKEET", emptyWords, emptyWords, enrolled)
        }
        val missingVoiceC = baseline.copy(matchedVoices = listOf("diar_0:Voice_A:AUTO", "diar_1:Voice_B:AUTO"))
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyLaneEquivalence("PARAKEET", missingVoiceC, missingVoiceC, enrolled)
        }

        // Hash or speaker metadata mismatch must fail.
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyLaneEquivalence(
                "PARAKEET thermal",
                baseline,
                baseline.copy(captureOpusSha256 = "f".repeat(64)),
                enrolled,
            )
        }
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyLaneEquivalence(
                "PARAKEET thermal",
                baseline,
                baseline.copy(decodedPcmSha256 = "e".repeat(64)),
                enrolled,
            )
        }
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyLaneEquivalence(
                "PARAKEET thermal",
                baseline,
                baseline.copy(speakerCount = 2),
                enrolled,
            )
        }
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyLaneEquivalence(
                "PARAKEET thermal",
                baseline,
                baseline.copy(speakerLabels = mapOf("diar_0" to "Speaker 1")),
                enrolled,
            )
        }
    }

    @Test
    fun transientAudioFocusValidatorRequiresBothGrantsAndOrderedTransientLossToGain() {
        HandsetQualificationAudit.verifyTransientAudioFocusTransition(
            primaryGranted = true,
            competingGranted = true,
            observedEvents = listOf("LOST_FOR_NOW", "GAINED"),
        )

        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyTransientAudioFocusTransition(
                primaryGranted = false,
                competingGranted = true,
                observedEvents = listOf("LOST_FOR_NOW", "GAINED"),
            )
        }
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyTransientAudioFocusTransition(
                primaryGranted = true,
                competingGranted = false,
                observedEvents = listOf("LOST_FOR_NOW", "GAINED"),
            )
        }
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyTransientAudioFocusTransition(
                primaryGranted = true,
                competingGranted = true,
                observedEvents = emptyList(),
            )
        }
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyTransientAudioFocusTransition(
                primaryGranted = true,
                competingGranted = true,
                observedEvents = listOf("LOST", "GAINED"),
            )
        }
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyTransientAudioFocusTransition(
                primaryGranted = true,
                competingGranted = true,
                observedEvents = listOf("GAINED", "LOST_FOR_NOW"),
            )
        }
    }

    @Test
    fun precomputeBackgroundYieldTrackerIgnoresStalePauseStopAndRejectsPreHomeYield() {
        val tracker = HandsetQualificationAudit.PrecomputeBackgroundYieldTracker()

        // 1. Pre-resume worker starts and stops with YIELDED due to user Pause: must NOT satisfy Home yield.
        assertFalse(tracker.onWorkerStarted())
        assertFalse(tracker.onWorkerStopped("YIELDED"))

        // 2. Even a delayed stale stop arriving right after markResumed (before fresh post-Resume worker starts) is ignored.
        tracker.markResumed()
        assertFalse(tracker.onWorkerStopped("YIELDED"))
        assertThrows(AssertionError::class.java) { tracker.verifyYieldedAfterBackground() }

        // 3. Post-Resume worker starts and yields BEFORE KEYCODE_HOME is issued: must NOT pass.
        val preHomeYield = HandsetQualificationAudit.PrecomputeBackgroundYieldTracker()
        preHomeYield.markResumed()
        assertTrue(preHomeYield.onWorkerStarted())
        assertFalse(preHomeYield.onWorkerStopped("YIELDED"))
        preHomeYield.markHomeIssued()
        assertThrows(AssertionError::class.java) { preHomeYield.verifyYieldedAfterBackground() }

        // 4. Fresh post-Resume worker active at Home, then stops with CANCELLED (not YIELDED): must fail.
        val wrongReason = HandsetQualificationAudit.PrecomputeBackgroundYieldTracker()
        wrongReason.markResumed()
        assertTrue(wrongReason.onWorkerStarted())
        wrongReason.markHomeIssued()
        assertTrue(wrongReason.onWorkerStopped("CANCELLED"))
        assertThrows(AssertionError::class.java) { wrongReason.verifyYieldedAfterBackground() }

        // 5. Fresh post-Resume worker active at Home, then yields with YIELDED after Home: passes.
        assertTrue(tracker.onWorkerStarted())
        tracker.markHomeIssued()
        assertTrue(tracker.onWorkerStopped("YIELDED"))
        tracker.verifyYieldedAfterBackground()
    }

    @Test
    fun oomSuppressionEvaluatorReturnsNullWhenUntestedAndRequiresOutOfMemoryReason() {
        val untested = HandsetQualificationAudit.evaluateOomSuppression(
            exercised = false,
            observedStopReason = null,
        )
        assertEquals(null, untested)
        val observed = HandsetQualificationAudit.evaluateOomSuppression(
            exercised = true,
            observedStopReason = "OUT_OF_MEMORY",
        )
        assertEquals(true, observed)
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.evaluateOomSuppression(exercised = true, observedStopReason = "YIELDED")
        }
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.evaluateOomSuppression(exercised = true, observedStopReason = null)
        }
    }

    @Test
    fun resolvePairedEnginesRejectsWhistleOnlyOnIneligibleHandset() {
        assertEquals(
            HandsetQualificationAudit.PairedEnginePlan(runParakeet = true, runWhistle = false),
            HandsetQualificationAudit.resolvePairedEngines(
                runParakeet = true,
                runWhistle = true,
                whistleEligible = false,
            ),
        )
        assertEquals(
            HandsetQualificationAudit.PairedEnginePlan(runParakeet = false, runWhistle = true),
            HandsetQualificationAudit.resolvePairedEngines(
                runParakeet = false,
                runWhistle = true,
                whistleEligible = true,
            ),
        )
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.resolvePairedEngines(
                runParakeet = false,
                runWhistle = true,
                whistleEligible = false,
            )
        }
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.resolvePairedEngines(
                runParakeet = false,
                runWhistle = false,
                whistleEligible = true,
            )
        }
    }

    @Test
    fun bitExactFloatPcmPrefixRejectsSingleSampleOneLsbDifference() {
        val live = floatArrayOf(0.0f, 0.25f, -0.5f, 0.125f)
        val exact = floatArrayOf(0.0f, 0.25f, -0.5f, 0.125f, 0.75f)
        HandsetQualificationAudit.verifyBitExactFloatPcmPrefix(live, exact)

        val drifted = exact.copyOf().apply { this[2] += 1e-5f }
        assertThrows(AssertionError::class.java) {
            HandsetQualificationAudit.verifyBitExactFloatPcmPrefix(live, drifted)
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
