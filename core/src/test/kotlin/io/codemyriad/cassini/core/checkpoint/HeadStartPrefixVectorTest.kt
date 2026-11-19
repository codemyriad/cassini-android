package io.codemyriad.cassini.core.checkpoint

import io.codemyriad.cassini.core.asr.IncrementalState
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.models.ModelCatalog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies [PrefixCheckpointValidator] and [CheckpointState] against every Wave 0 vector in
 * `vectors/head-start/mobile-prefix-checkpoint-vectors.json`.
 */
class HeadStartPrefixVectorTest {
    private val samplePrefix = CommittedPrefix(
        kind = CommittedPrefix.KIND_OGG_OPUS_BYTES,
        committedUnitCount = 318_400L,
        prefixSha256 = "8effb37a40fe690d9a5ffc373bf0e03aa7781c44a91c6e426cbfcae8d7046fed",
        decodedPcmSha256 = "6b7e882caf582eabb4cc7d0c0647aab1bc724325cb43b7cc8e4e72997b92837c",
    )

    private fun samplePrecomputeState(): CheckpointState {
        val word = Word("s1", 100, 300, "hello")
        val chunks = IncrementalState(416_000L, 448_000L, FloatArray(32_000), listOf(word))
        return CheckpointState(
            source = "capture.opus",
            signature = "320000:1000",
            model = ModelCatalog.PARAKEET_REVISION,
            speakers = ModelCatalog.diarizationModel.sha256,
            recognition = RecognitionState(inferenceMs = 1234L, chunks = chunks),
            encoder = null,
            diarizer = null,
            speakerPrefix = "diar_test_",
            speakerFailure = false,
            clipSamples = 0,
            voiceClips = null,
            positionSamples = 448_000L,
            doneMs = 28_000L,
            durationMs = 0L,
            elapsedMs = 2000L,
            origin = CheckpointOrigin.PRECOMPUTE,
            committedPrefix = samplePrefix,
        )
    }

    private fun expectedOgg(
        prefixKind: String = CommittedPrefix.KIND_OGG_OPUS_BYTES,
        prefixSha: String = samplePrefix.prefixSha256,
    ) = CheckpointState.Expected(
        source = "capture.opus",
        signature = "412512:2000",
        modelRevision = ModelCatalog.PARAKEET_REVISION,
        speakersSha256 = ModelCatalog.diarizationModel.sha256,
        clipsBytes = 0L,
        audioBytes = 0L,
        wordLogBytes = 0L,
        prefixKind = prefixKind,
        finalizedUnitCount = 412_512L,
        prefixSha256 = prefixSha,
        decodedPcmSha256 = samplePrefix.decodedPcmSha256,
    )

    @Test fun matchesAllSharedMobilePrefixCheckpointVectors() {
        val uxVendor = File(System.getProperty("cassini.uxVendor"))
        val vectorFile = File(uxVendor, "vectors/head-start/mobile-prefix-checkpoint-vectors.json")
        assertTrue("Missing vendored vectors at $vectorFile", vectorFile.isFile)
        val root = JSONObject(vectorFile.readText(Charsets.UTF_8))
        assertEquals("cassini.head-start.mobile-prefix-checkpoint-vectors.v1", root.getString("schema"))

        val cases = root.getJSONArray("cases")
        assertTrue(cases.length() >= 15)
        for (i in 0 until cases.length()) {
            val testCase = cases.getJSONObject(i)
            val id = testCase.getString("id")
            val checkpoint = testCase.getJSONObject("checkpoint")
            val expectedFile = testCase.getJSONObject("expectedFile")
            val expectedDecision = testCase.getJSONObject("expectedDecision")

            val actual = PrefixCheckpointValidator.validate(checkpoint, expectedFile)
            assertEquals("$id: decision", expectedDecision.getString("decision"), actual.decision.token)
            val wantMatchMode = if (expectedDecision.isNull("matchMode")) {
                null
            } else {
                expectedDecision.getString("matchMode")
            }
            assertEquals("$id: matchMode", wantMatchMode, actual.matchMode?.token)
            val wantReason = if (expectedDecision.isNull("reason")) null else expectedDecision.getString("reason")
            assertEquals("$id: reason", wantReason, actual.reason?.token)
            assertEquals("$id: failUserJob", expectedDecision.getBoolean("failUserJob"), actual.failUserJob)
        }
    }

    @Test fun checkpointStateRoundTripsPrecomputeOriginAndValidatesCommittedPrefix() {
        val json = JSONObject(CheckpointState.encode(samplePrecomputeState()).toString())
        assertEquals("precompute", json.getString("origin"))
        assertTrue(json.has("committedPrefix"))
        val decoded = CheckpointState.decode(json)
        assertEquals(CheckpointOrigin.PRECOMPUTE, decoded.origin)
        assertEquals(samplePrefix, decoded.committedPrefix)

        val valid = CheckpointState.validateWithResult(json, expectedOgg())
        assertEquals(PrefixDecision.RESUME, valid.decision)
        assertEquals(PrefixMatchMode.COMMITTED_PREFIX, valid.matchMode)
        assertNull(valid.reason)
    }

    @Test fun precomputeMismatchesDiscardCleanlyForAlteredPrefixAndAac() {
        val json = JSONObject(CheckpointState.encode(samplePrecomputeState()).toString())

        val altered = CheckpointState.validateWithResult(json, expectedOgg(prefixSha = "0".repeat(64)))
        assertEquals(PrefixDecision.DISCARD_AND_FULL_DECODE, altered.decision)
        assertEquals(PrefixReason.PREFIX_DIGEST_MISMATCH, altered.reason)
        assertFalse(altered.failUserJob)

        val aac = CheckpointState.validateWithResult(json, expectedOgg(prefixKind = CommittedPrefix.KIND_AAC_M4A))
        assertEquals(PrefixDecision.DISCARD_AND_FULL_DECODE, aac.decision)
        assertEquals(PrefixReason.PREFIX_KIND_MISMATCH, aac.reason)
        assertFalse(aac.failUserJob)
    }
}
