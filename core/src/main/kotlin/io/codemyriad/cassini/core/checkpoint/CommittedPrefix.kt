package io.codemyriad.cassini.core.checkpoint

import io.codemyriad.cassini.core.audio.Limits
import org.json.JSONObject

/** Whether a checkpoint was produced by recording-time precomputation or post-Stop batch transcription. */
enum class CheckpointOrigin(val token: String) {
    BATCH("batch"),
    PRECOMPUTE("precompute"),
    ;

    companion object {
        /** Absent or unrecognized origin resolves conservatively to [BATCH]. */
        fun of(raw: Any?): CheckpointOrigin = if (raw == PRECOMPUTE.token) PRECOMPUTE else BATCH
    }
}

/**
 * Committed-prefix integrity descriptor for recording-time precompute checkpoints
 * (`contract/HEAD-START-TRANSCRIPTION.md` §5.1).
 */
data class CommittedPrefix(
    val kind: String,
    val committedUnitCount: Long,
    val prefixSha256: String,
    val sourceSampleRateHz: Int? = null,
    val decodedPcmSha256: String? = null,
) {
    fun json(): JSONObject = JSONObject()
        .put("kind", kind)
        .put("committedUnitCount", committedUnitCount)
        .put("prefixSha256", prefixSha256)
        .apply {
            if (sourceSampleRateHz != null) put("sourceSampleRateHz", sourceSampleRateHz)
            if (decodedPcmSha256 != null) put("decodedPcmSha256", decodedPcmSha256)
        }

    companion object {
        const val KIND_CAF_PCM16_LE_FRAMES = "cafPcm16LeFrames"
        const val KIND_OGG_OPUS_BYTES = "oggOpusCommittedBytes"
        const val KIND_AAC_M4A = "aacM4a"

        val VALID_KINDS: Set<String> = setOf(KIND_CAF_PCM16_LE_FRAMES, KIND_OGG_OPUS_BYTES)

        fun fromJson(json: JSONObject): CommittedPrefix {
            val kind = requireNotNull(json.opt("kind") as? String) { "Missing committedPrefix.kind" }
            val units = requireNotNull(exactLong(json.opt("committedUnitCount"))) {
                "Invalid committedPrefix.committedUnitCount"
            }
            val sha = requireNotNull(json.opt("prefixSha256") as? String) { "Missing committedPrefix.prefixSha256" }
            val rate = optionalField(json, "sourceSampleRateHz") { raw ->
                requireNotNull(exactInt(raw)) { "Invalid committedPrefix.sourceSampleRateHz" }
            }
            val decodedSha = optionalField(json, "decodedPcmSha256") { raw ->
                requireNotNull(raw as? String) { "Invalid committedPrefix.decodedPcmSha256" }
            }
            return CommittedPrefix(
                kind = kind,
                committedUnitCount = units,
                prefixSha256 = sha,
                sourceSampleRateHz = rate,
                decodedPcmSha256 = decodedSha,
            )
        }

        private inline fun <T> optionalField(json: JSONObject, key: String, parse: (Any?) -> T): T? =
            if (json.has(key) && !json.isNull(key)) parse(json.opt(key)) else null
    }
}

enum class PrefixMatchMode(val token: String) {
    WHOLE_FILE("wholeFile"),
    COMMITTED_PREFIX("committedPrefix"),
}

enum class PrefixDecision(val token: String) {
    RESUME("resume"),
    DISCARD_AND_FULL_DECODE("discardAndFullDecode"),
    REJECT_BATCH_CHECKPOINT("rejectBatchCheckpoint"),
}

enum class PrefixReason(val token: String) {
    VERSION_MISMATCH("versionMismatch"),
    SOURCE_MISMATCH("sourceMismatch"),
    MODEL_MISMATCH("modelMismatch"),
    SPEAKERS_MISMATCH("speakersMismatch"),
    SIGNATURE_MISMATCH("signatureMismatch"),
    INVALID_PREFIX_DESCRIPTOR("invalidPrefixDescriptor"),
    PREFIX_KIND_MISMATCH("prefixKindMismatch"),
    SOURCE_SAMPLE_RATE_MISMATCH("sourceSampleRateMismatch"),
    PREFIX_COVERAGE_INSUFFICIENT("prefixCoverageInsufficient"),
    PREFIX_TRUNCATED("prefixTruncated"),
    PREFIX_DIGEST_MISMATCH("prefixDigestMismatch"),
    DECODED_PREFIX_MISMATCH("decodedPrefixMismatch"),
    INVALID_COUNTERS("invalidCounters"),
    WORK_FILE_TRUNCATED("workFileTruncated"),
}

data class PrefixValidationResult(
    val decision: PrefixDecision,
    val matchMode: PrefixMatchMode?,
    val reason: PrefixReason?,
) {
    val failUserJob: Boolean
        get() = decision == PrefixDecision.REJECT_BATCH_CHECKPOINT
}

/**
 * Pure validator implementing `contract/HEAD-START-TRANSCRIPTION.md` §5.2 in strict rule order,
 * verified against `vectors/head-start/mobile-prefix-checkpoint-vectors.json`.
 */
@Suppress("TooManyFunctions") // One small helper per D109 §5.2 validation rule.
object PrefixCheckpointValidator {
    private const val DEFAULT_MAX_TRANSCRIBE_MS = 12L * 3600L * 1000L
    private const val SAMPLES_PER_MS = Limits.ASR_RATE / 1000L
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /**
     * Minimum CAF source-rate frames `[0 ..< N)` required to produce [consumed16kSamples] at 16 kHz
     * with [resamplerLookaheadFrames] right-halo lookahead.
     */
    fun minRequiredCafSourceFrames(
        consumed16kSamples: Long,
        sourceSampleRateHz: Int,
        resamplerLookaheadFrames: Int = 0,
    ): Long {
        require(consumed16kSamples >= 0L) { "consumed16kSamples must be non-negative" }
        require(sourceSampleRateHz > 0) { "sourceSampleRateHz must be positive" }
        require(resamplerLookaheadFrames >= 0) { "resamplerLookaheadFrames must be non-negative" }
        if (consumed16kSamples == 0L) return 0L
        return ((consumed16kSamples - 1L) * sourceSampleRateHz + Limits.ASR_RATE - 1L) / Limits.ASR_RATE +
            resamplerLookaheadFrames + 1L
    }

    /** Validates raw contract-shape JSON (`mobile-prefix-checkpoint-vectors.json`). */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun validate(checkpoint: JSONObject, expected: JSONObject): PrefixValidationResult {
        val origin = CheckpointOrigin.of(checkpoint.opt("origin"))
        fun fail(reason: PrefixReason): PrefixValidationResult {
            val decision = if (origin == CheckpointOrigin.PRECOMPUTE) {
                PrefixDecision.DISCARD_AND_FULL_DECODE
            } else {
                PrefixDecision.REJECT_BATCH_CHECKPOINT
            }
            return PrefixValidationResult(decision, matchMode = null, reason = reason)
        }

        if (exactLong(checkpoint.opt("version")) != exactLong(expected.opt("version"))) {
            return fail(PrefixReason.VERSION_MISMATCH)
        }
        if (checkpoint.opt("source") != expected.opt("source")) return fail(PrefixReason.SOURCE_MISMATCH)
        if (checkpoint.opt("model") != expected.opt("model")) return fail(PrefixReason.MODEL_MISMATCH)
        if (checkpoint.opt("speakers") != expected.opt("speakers")) return fail(PrefixReason.SPEAKERS_MISMATCH)

        val maxMs = exactLong(expected.opt("maxTranscribeMs")) ?: DEFAULT_MAX_TRANSCRIBE_MS
        val matchMode = if (checkpoint.opt("signature") == expected.opt("signature")) {
            PrefixMatchMode.WHOLE_FILE
        } else {
            val prefixFailure = validatePrefixObject(checkpoint, expected, maxMs)
            if (prefixFailure != null) return fail(prefixFailure)
            PrefixMatchMode.COMMITTED_PREFIX
        }

        if (!validCounters(checkpoint, maxMs)) return fail(PrefixReason.INVALID_COUNTERS)
        if (!validWorkFiles(checkpoint.optJSONObject("workFiles"), expected.optJSONObject("workFiles"))) {
            return fail(PrefixReason.WORK_FILE_TRUNCATED)
        }
        return PrefixValidationResult(PrefixDecision.RESUME, matchMode = matchMode, reason = null)
    }

    /**
     * Validates a committed prefix descriptor against finalized file expectations.
     * Returns null when valid, or the specific [PrefixReason] on failure.
     */
    @Suppress("CyclomaticComplexMethod", "LongParameterList", "ReturnCount")
    fun validatePrefixDescriptor(
        prefix: JSONObject?,
        expectedPrefixKind: String?,
        expectedFinalizedUnitCount: Long?,
        expectedPrefixSha256: String?,
        expectedDecodedPcmSha256: String?,
        deferDecodedPcmCheck: Boolean = false,
        expectedSourceSampleRateHz: Int? = null,
        expectedResamplerLookaheadFrames: Int = 0,
        doneMs: Long? = null,
        recognitionSampleCount: Long? = null,
        maxTranscribeMs: Long = DEFAULT_MAX_TRANSCRIBE_MS,
    ): PrefixReason? {
        if (prefix == null) return PrefixReason.SIGNATURE_MISMATCH
        val kind = prefix.opt("kind") as? String
        val unitCount = exactLong(prefix.opt("committedUnitCount"))
        val prefixSha = prefix.opt("prefixSha256") as? String
        val decodedRaw = nonNullField(prefix, "decodedPcmSha256")
        if (!hasValidCoreDescriptor(kind, unitCount, prefixSha) || !isValidOptionalHex64(decodedRaw)) {
            return PrefixReason.INVALID_PREFIX_DESCRIPTOR
        }
        if (kind != expectedPrefixKind) return PrefixReason.PREFIX_KIND_MISMATCH
        if (kind == CommittedPrefix.KIND_CAF_PCM16_LE_FRAMES) {
            val cafFailure = validateCafCoverage(
                prefix,
                unitCount!!,
                expectedSourceSampleRateHz,
                expectedResamplerLookaheadFrames,
                doneMs,
                recognitionSampleCount,
                maxTranscribeMs,
            )
            if (cafFailure != null) return cafFailure
        }
        if (expectedFinalizedUnitCount == null || expectedFinalizedUnitCount < unitCount!!) {
            return PrefixReason.PREFIX_TRUNCATED
        }
        if (expectedPrefixSha256 != prefixSha) return PrefixReason.PREFIX_DIGEST_MISMATCH
        if (decodedRaw != null && !deferDecodedPcmCheck && expectedDecodedPcmSha256 != decodedRaw) {
            return PrefixReason.DECODED_PREFIX_MISMATCH
        }
        return null
    }

    private fun hasValidCoreDescriptor(kind: String?, unitCount: Long?, prefixSha: String?): Boolean =
        kind in CommittedPrefix.VALID_KINDS &&
            unitCount != null &&
            unitCount > 0L &&
            isHex64(prefixSha)

    private fun isHex64(value: Any?): Boolean = value is String && HEX64.matches(value)

    private fun isValidOptionalHex64(value: Any?): Boolean = value == null || isHex64(value)

    private fun nonNullField(json: JSONObject, key: String): Any? =
        if (json.has(key) && !json.isNull(key)) json.opt(key) else null

    @Suppress("LongParameterList", "ReturnCount")
    private fun validateCafCoverage(
        prefix: JSONObject,
        unitCount: Long,
        expectedRate: Int?,
        expectedLookahead: Int,
        doneMs: Long?,
        recognitionSampleCount: Long?,
        maxTranscribeMs: Long,
    ): PrefixReason? {
        val prefixRate = exactInt(prefix.opt("sourceSampleRateHz"))
        if (prefixRate == null || prefixRate <= 0) return PrefixReason.INVALID_PREFIX_DESCRIPTOR
        if (expectedRate == null || expectedRate <= 0 || prefixRate != expectedRate) {
            return PrefixReason.SOURCE_SAMPLE_RATE_MISMATCH
        }
        if (expectedLookahead < 0) return PrefixReason.INVALID_PREFIX_DESCRIPTOR
        if (!validSampleBounds(doneMs, recognitionSampleCount, maxTranscribeMs)) return PrefixReason.INVALID_COUNTERS
        val consumed16k = maxOf(doneMs!! * SAMPLES_PER_MS, recognitionSampleCount ?: 0L)
        val minRequired = minRequiredCafSourceFrames(consumed16k, prefixRate, expectedLookahead)
        return if (unitCount < minRequired) PrefixReason.PREFIX_COVERAGE_INSUFFICIENT else null
    }

    private fun validSampleBounds(doneMs: Long?, recognitionSampleCount: Long?, maxMs: Long): Boolean {
        if (doneMs == null || doneMs !in 0L..maxMs) return false
        return recognitionSampleCount == null ||
            recognitionSampleCount in (doneMs * SAMPLES_PER_MS)..(maxMs * SAMPLES_PER_MS)
    }

    private fun validatePrefixObject(checkpoint: JSONObject, expected: JSONObject, maxMs: Long): PrefixReason? {
        val prefix = checkpoint.opt("committedPrefix") as? JSONObject
        val lookaheadRaw = nonNullField(expected, "resamplerLookaheadFrames") ?: 0
        val lookahead = exactInt(lookaheadRaw)
        val hasRecSamples = nonNullField(checkpoint, "recognitionSampleCount") != null
        val recSamples = nonNullField(checkpoint, "recognitionSampleCount")?.let(::exactLong)
        return when {
            lookahead == null -> PrefixReason.INVALID_PREFIX_DESCRIPTOR
            hasRecSamples && recSamples == null -> PrefixReason.INVALID_COUNTERS
            else -> validatePrefixDescriptor(
                prefix = prefix,
                expectedPrefixKind = expected.opt("prefixKind") as? String,
                expectedFinalizedUnitCount = exactLong(expected.opt("finalizedUnitCount")),
                expectedPrefixSha256 = expected.opt("prefixSha256") as? String,
                expectedDecodedPcmSha256 = expected.opt("decodedPcmSha256") as? String,
                expectedSourceSampleRateHz = exactInt(expected.opt("sourceSampleRateHz")),
                expectedResamplerLookaheadFrames = lookahead,
                doneMs = exactLong(checkpoint.opt("doneMs")),
                recognitionSampleCount = recSamples,
                maxTranscribeMs = maxMs,
            )
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun validCounters(checkpoint: JSONObject, maxMs: Long): Boolean {
        val doneMs = exactLong(checkpoint.opt("doneMs")) ?: return false
        val totalMs = nonNullField(checkpoint, "totalMs")?.let(::exactLong)
            ?: if (nonNullField(checkpoint, "totalMs") == null) 0L else return false
        val resumedFromMs = nonNullField(checkpoint, "resumedFromMs")?.let(::exactLong)
            ?: if (nonNullField(checkpoint, "resumedFromMs") == null) 0L else return false
        val inBounds = doneMs in 0L..maxMs &&
            totalMs in 0L..maxMs &&
            resumedFromMs in 0L..doneMs &&
            (totalMs == 0L || doneMs <= totalMs)
        if (!inBounds) return false
        val recField = nonNullField(checkpoint, "recognitionSampleCount")
        val recSamples = recField?.let(::exactLong)
        return recField == null || (recSamples != null && validSampleBounds(doneMs, recSamples, maxMs))
    }

    private fun validWorkFiles(required: JSONObject?, actual: JSONObject?): Boolean {
        if (required == null) return true
        return WORK_FILE_KEYS.all { key ->
            if (!required.has(key)) {
                true
            } else {
                val need = exactLong(required.opt(key))
                val have = actual?.let { exactLong(it.opt(key)) }
                need != null && have != null && need >= 0L && have >= need
            }
        }
    }

    private val WORK_FILE_KEYS = listOf("clipsBytes", "wordLogBytes", "audioBytes")
}

internal fun exactLong(value: Any?): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    else -> null
}

internal fun exactInt(value: Any?): Int? = when (value) {
    is Int -> value
    is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
    else -> null
}
