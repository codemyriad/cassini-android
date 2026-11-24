package io.codemyriad.cassini.core.asr

/**
 * Non-UI transcription lifecycle policy (D-1117 / D109 / D111 `contract/HEAD-START-TRANSCRIPTION.md`).
 *
 * Defaults to [TRANSCRIBE_WHILE_RECORDING] across all platforms (D111).
 */
enum class TranscriptionMode(val canonical: String) {
    /** Run zero native models during recording and leave stopped notes audio-only until manual Transcribe. */
    RECORD_ONLY("recordOnly"),

    /** Run zero native models during recording; auto-enqueue standard batch transcription on Stop. */
    AFTER_STOP("afterStop"),

    /** Follow committed audio on disk at background priority during recording; finalize tail on Stop. */
    TRANSCRIBE_WHILE_RECORDING("transcribeWhileRecording"),
    ;

    /** Whether stopping a recording automatically enqueues transcription. */
    val autoEnqueueOnStop: Boolean
        get() = this != RECORD_ONLY

    /** Result of normalizing a persisted token. */
    data class ParseResult(val mode: TranscriptionMode, val fellBackToDefault: Boolean)

    /** Result of evaluating recording-time and Stop-time policy for one mode and runtime context. */
    data class PolicyDecision(
        val resolvedMode: TranscriptionMode,
        val fellBackToDefault: Boolean,
        val spawnWorkerDuringRecording: Boolean,
        val autoEnqueueOnStop: Boolean,
        val savedTranscriptStateOnStop: String,
        val enqueueOnManualRequest: Boolean,
        val preemptActiveFollower: Boolean,
    )

    companion object {
        val DEFAULT: TranscriptionMode = TRANSCRIBE_WHILE_RECORDING

        private const val STATE_QUEUED = "queued"
        private const val STATE_NONE = "none"

        private val ALIASES: Map<String, TranscriptionMode> = mapOf(
            "recordOnly" to RECORD_ONLY,
            "record_only" to RECORD_ONLY,
            "RECORD_ONLY" to RECORD_ONLY,
            "afterStop" to AFTER_STOP,
            "after_stop" to AFTER_STOP,
            "AFTER_STOP" to AFTER_STOP,
            "transcribeWhileRecording" to TRANSCRIBE_WHILE_RECORDING,
            "transcribe_while_recording" to TRANSCRIBE_WHILE_RECORDING,
            "TRANSCRIBE_WHILE_RECORDING" to TRANSCRIBE_WHILE_RECORDING,
        )

        /**
         * Resolves a persisted token (canonical, Python snake_case, or Kotlin UPPER_SNAKE) case-sensitively.
         * Null, empty, non-string, or unrecognized values deterministically fall back to [DEFAULT].
         */
        fun parse(raw: Any?): ParseResult {
            val matched = (raw as? String)?.let(ALIASES::get)
            return if (matched != null) {
                ParseResult(matched, fellBackToDefault = false)
            } else {
                ParseResult(DEFAULT, fellBackToDefault = true)
            }
        }

        /** Evaluates the shared D109 recording-time and Stop-time policy. */
        fun evaluate(
            rawMode: Any?,
            hasForegroundSavedJob: Boolean = false,
            manualTranscribeRequested: Boolean = false,
            platformSupportsPrecompute: Boolean = true,
        ): PolicyDecision {
            val parsed = parse(rawMode)
            val mode = parsed.mode
            val spawn = mode == TRANSCRIBE_WHILE_RECORDING &&
                !hasForegroundSavedJob &&
                !manualTranscribeRequested &&
                platformSupportsPrecompute
            val autoEnqueue = mode.autoEnqueueOnStop
            val preempt = manualTranscribeRequested || hasForegroundSavedJob
            return PolicyDecision(
                resolvedMode = mode,
                fellBackToDefault = parsed.fellBackToDefault,
                spawnWorkerDuringRecording = spawn,
                autoEnqueueOnStop = autoEnqueue,
                savedTranscriptStateOnStop = if (autoEnqueue) STATE_QUEUED else STATE_NONE,
                enqueueOnManualRequest = manualTranscribeRequested,
                preemptActiveFollower = preempt,
            )
        }
    }
}
