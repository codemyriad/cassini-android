package io.codemyriad.cassini.qualification

/**
 * Deterministic pure-Kotlin qualification validators for D-1126 physical handset checks.
 *
 * Lives in `src/instrumented/java` so both JVM unit tests (`testDebugUnitTest`) and device tests
 * (`debugAndroidTest` / `releaseTestAndroidTest`) exercise the exact same assertions without shipping in release APKs.
 */
object HandsetQualificationAudit {
    private const val MIN_HAL_SAMPLES_FOR_DELTA = 2
    private const val LATE_PRECOMPUTE_RATIO = 0.75
    private const val TAIL_WORD_RATIO = 0.90
    private const val MATCHED_VOICE_NAME_INDEX = 1
    private const val FOCUS_LOST_PERMANENT = "LOST"
    private const val FOCUS_LOST_TRANSIENT = "LOST_FOR_NOW"
    private const val FOCUS_GAINED = "GAINED"

    data class HalDropSummary(
        val halTimestampSamples: Int,
        val halTimestampAvailable: Boolean,
        val halDropEvents: Int,
        val maxHalFrameDeficit: Long,
        val timingDropEvents: Int,
        val droppedBuffers: Int?,
    )

    /**
     * Tracks HAL `AudioTimestamp.framePosition` continuity against the application sample counter.
     *
     * Saves both counters at the first valid HAL timestamp and compares deltas (`halDelta - appDelta`) on subsequent
     * samples so initial capture latency before the first valid timestamp cannot hide later buffer overruns.
     */
    class HalTimestampDropTracker {
        private var baseHalFrame: Long? = null
        private var baseAppSamples: Long = 0L
        private var validSamples: Int = 0
        private var halDrops: Int = 0
        private var maxDeficit: Long = 0L

        @Synchronized
        fun record(halFramePosition: Long, appSamples: Long, ringSamples: Int): Boolean {
            validSamples++
            val firstHal = baseHalFrame
            if (firstHal == null) {
                baseHalFrame = halFramePosition
                baseAppSamples = appSamples
                return false
            }
            val halDelta = halFramePosition - firstHal
            val appDelta = appSamples - baseAppSamples
            val deficit = halDelta - appDelta
            if (deficit > maxDeficit) maxDeficit = deficit
            if (deficit > ringSamples) {
                halDrops++
                return true
            }
            return false
        }

        @Synchronized
        fun snapshot(timingDropEvents: Int): HalDropSummary {
            val available = validSamples >= MIN_HAL_SAMPLES_FOR_DELTA
            return HalDropSummary(
                halTimestampSamples = validSamples,
                halTimestampAvailable = available,
                halDropEvents = halDrops,
                maxHalFrameDeficit = maxDeficit,
                timingDropEvents = timingDropEvents,
                droppedBuffers = if (available) timingDropEvents + halDrops else null,
            )
        }
    }

    data class ComparableWord(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val speaker: String,
    )

    data class EquivalenceSnapshot(
        val captureOpusSha256: String,
        val decodedPcmSha256: String,
        val words: List<ComparableWord>,
        val turnCount: Int,
        val speakerCount: Int,
        val speakerLabels: Map<String, String>,
        val matchedVoices: List<String>,
    )

    data class LongLaneProgress(
        val expectedDurationMs: Long,
        val precomputeCheckpoints: Int,
        val resumedFromMs: Long,
        val wordCount: Int,
        val lastWordEndMs: Long,
    )

    fun verifyCaptureHealth(summary: HalDropSummary, maxFrameIntervalMs: Long, ringBufferMs: Long) {
        if (!summary.halTimestampAvailable) {
            throw AssertionError(
                "HAL AudioTimestamp continuity evidence unavailable " +
                    "(validSamples=${summary.halTimestampSamples}, need >= $MIN_HAL_SAMPLES_FOR_DELTA)",
            )
        }
        val dropped = summary.droppedBuffers ?: throw AssertionError("Dropped buffer count unavailable")
        if (dropped != 0) {
            throw AssertionError(
                "Expected zero dropped AudioRecord buffers, got $dropped " +
                    "(halDrops=${summary.halDropEvents}, timingDrops=${summary.timingDropEvents}, " +
                    "maxHalFrameDeficit=${summary.maxHalFrameDeficit})",
            )
        }
        if (maxFrameIntervalMs >= ringBufferMs) {
            throw AssertionError(
                "Max OpusCapture frame interval ($maxFrameIntervalMs ms) must be < ring ($ringBufferMs ms)",
            )
        }
    }

    fun verifyNonEmptyBaseline(
        label: String,
        baseline: EquivalenceSnapshot,
        expectedEnrolledVoices: List<String>,
    ) {
        val failure = when {
            baseline.captureOpusSha256.isBlank() -> "$label baseline capture.opus SHA-256 is blank"
            baseline.decodedPcmSha256.isBlank() -> "$label baseline decoded PCM SHA-256 is blank"
            baseline.words.isEmpty() -> "$label baseline must contain non-empty transcript words"
            baseline.turnCount <= 0 -> "$label baseline must contain positive diarization turns"
            baseline.speakerCount < expectedEnrolledVoices.size ->
                "$label baseline speakerCount (${baseline.speakerCount}) < expected (${expectedEnrolledVoices.size})"
            baseline.speakerLabels.isEmpty() -> "$label baseline must contain non-empty speaker labels"
            else -> findMissingVoicesError(label, baseline.matchedVoices, expectedEnrolledVoices)
        }
        if (failure != null) throw AssertionError(failure)
    }

    private fun findMissingVoicesError(
        label: String,
        matchedVoices: List<String>,
        expectedEnrolledVoices: List<String>,
    ): String? {
        val matchedNames = matchedVoices.mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size > MATCHED_VOICE_NAME_INDEX) parts[MATCHED_VOICE_NAME_INDEX] else null
        }.toSet()
        val missing = expectedEnrolledVoices.filter { it !in matchedNames }
        return if (missing.isNotEmpty()) {
            "$label baseline missing CAM++ matches for enrolled voices $missing (observed=$matchedVoices)"
        } else {
            null
        }
    }

    fun verifyLaneEquivalence(
        label: String,
        baseline: EquivalenceSnapshot,
        candidate: EquivalenceSnapshot,
        expectedEnrolledVoices: List<String>,
    ) {
        verifyNonEmptyBaseline(label, baseline, expectedEnrolledVoices)
        val failure = when {
            baseline.captureOpusSha256 != candidate.captureOpusSha256 ->
                "$label capture.opus SHA-256 mismatch: ${baseline.captureOpusSha256} != ${candidate.captureOpusSha256}"
            baseline.decodedPcmSha256 != candidate.decodedPcmSha256 ->
                "$label decoded .opus PCM SHA-256 mismatch: ${baseline.decodedPcmSha256} != ${candidate.decodedPcmSha256}"
            baseline.words != candidate.words ->
                "$label words & timings mismatch (baseline=${baseline.words.size}, candidate=${candidate.words.size})"
            baseline.turnCount != candidate.turnCount ->
                "$label diarization turnCount mismatch: ${baseline.turnCount} != ${candidate.turnCount}"
            baseline.speakerCount != candidate.speakerCount ->
                "$label diarization speakerCount mismatch: ${baseline.speakerCount} != ${candidate.speakerCount}"
            baseline.speakerLabels != candidate.speakerLabels ->
                "$label speaker labels mismatch: ${baseline.speakerLabels} != ${candidate.speakerLabels}"
            baseline.matchedVoices != candidate.matchedVoices ->
                "$label CAM++ saved-voice matches mismatch: ${baseline.matchedVoices} != ${candidate.matchedVoices}"
            else -> null
        }
        if (failure != null) throw AssertionError(failure)
    }

    fun verifyLongLaneProgress(progress: LongLaneProgress) {
        val minLateResumedMs = (progress.expectedDurationMs * LATE_PRECOMPUTE_RATIO).toLong()
        val minTailWordEndMs = (progress.expectedDurationMs * TAIL_WORD_RATIO).toLong()
        val failure = when {
            progress.wordCount <= 0 -> "30-min transcript must contain words"
            progress.precomputeCheckpoints <= 0 ->
                "30-min lane must save recording-time precompute checkpoints (got ${progress.precomputeCheckpoints})"
            progress.resumedFromMs <= 0L ->
                "30-min lane must resume from a positive recording-time checkpoint (resumedFromMs=${progress.resumedFromMs})"
            progress.resumedFromMs < minLateResumedMs ->
                "30-min lane precomputation did not progress late into the recording " +
                    "(resumedFromMs=${progress.resumedFromMs} < $minLateResumedMs)"
            progress.lastWordEndMs < minTailWordEndMs ->
                "30-min transcript does not cover the recording tail " +
                    "(lastWordEndMs=${progress.lastWordEndMs} < $minTailWordEndMs)"
            else -> null
        }
        if (failure != null) throw AssertionError(failure)
    }

    fun verifyTransientAudioFocusTransition(
        primaryGranted: Boolean,
        competingGranted: Boolean,
        observedEvents: List<String>,
    ) {
        val lostIdx = observedEvents.indexOf(FOCUS_LOST_TRANSIENT)
        val gainedAfterLostIdx = if (lostIdx >= 0) {
            observedEvents.subList(lostIdx + 1, observedEvents.size).indexOf(FOCUS_GAINED)
        } else {
            -1
        }
        val failure = when {
            !primaryGranted -> "Primary audio-focus request was not granted"
            !competingGranted -> "Competing transient audio-focus request was not granted"
            observedEvents.isEmpty() -> "Expected ordered $FOCUS_LOST_TRANSIENT -> $FOCUS_GAINED callbacks, got none"
            FOCUS_LOST_PERMANENT in observedEvents ->
                "Unexpected permanent audio-focus $FOCUS_LOST_PERMANENT callback in transient check: $observedEvents"
            lostIdx < 0 || gainedAfterLostIdx < 0 ->
                "Expected ordered $FOCUS_LOST_TRANSIENT -> $FOCUS_GAINED callbacks, got $observedEvents"
            else -> null
        }
        if (failure != null) throw AssertionError(failure)
    }
}
