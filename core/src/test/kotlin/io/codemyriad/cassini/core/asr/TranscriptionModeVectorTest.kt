package io.codemyriad.cassini.core.asr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Verifies [TranscriptionMode] against every Wave 0 vector in `vectors/head-start/transcription-mode-vectors.json`. */
class TranscriptionModeVectorTest {
    @Test fun matchesAllSharedTranscriptionModeVectors() {
        val uxVendor = File(System.getProperty("cassini.uxVendor"))
        val vectorFile = File(uxVendor, "vectors/head-start/transcription-mode-vectors.json")
        assertTrue("Missing vendored vectors at $vectorFile", vectorFile.isFile)
        val root = JSONObject(vectorFile.readText(Charsets.UTF_8))
        assertEquals("cassini.head-start.transcription-mode-vectors.v1", root.getString("schema"))
        assertEquals(TranscriptionMode.DEFAULT.canonical, root.getString("defaultMode"))

        val cases = root.getJSONArray("cases")
        assertTrue(cases.length() >= 18)
        for (i in 0 until cases.length()) {
            val testCase = cases.getJSONObject(i)
            val id = testCase.getString("id")
            val input = testCase.getJSONObject("input")
            val expected = testCase.getJSONObject("expected")
            val rawMode = if (input.isNull("rawMode")) null else input.get("rawMode")

            val actual = TranscriptionMode.evaluate(
                rawMode = rawMode,
                hasForegroundSavedJob = input.getBoolean("hasForegroundSavedJob"),
                manualTranscribeRequested = input.getBoolean("manualTranscribeRequested"),
                platformSupportsPrecompute = input.getBoolean("platformSupportsPrecompute"),
            )

            assertEquals("$id: resolvedMode", expected.getString("resolvedMode"), actual.resolvedMode.canonical)
            assertEquals("$id: fellBackToDefault", expected.getBoolean("fellBackToDefault"), actual.fellBackToDefault)
            assertEquals(
                "$id: spawnWorkerDuringRecording",
                expected.getBoolean("spawnWorkerDuringRecording"),
                actual.spawnWorkerDuringRecording,
            )
            assertEquals("$id: autoEnqueueOnStop", expected.getBoolean("autoEnqueueOnStop"), actual.autoEnqueueOnStop)
            assertEquals(
                "$id: savedTranscriptStateOnStop",
                expected.getString("savedTranscriptStateOnStop"),
                actual.savedTranscriptStateOnStop,
            )
            assertEquals(
                "$id: enqueueOnManualRequest",
                expected.getBoolean("enqueueOnManualRequest"),
                actual.enqueueOnManualRequest,
            )
            assertEquals(
                "$id: preemptActiveFollower",
                expected.getBoolean("preemptActiveFollower"),
                actual.preemptActiveFollower,
            )
        }
    }
}
