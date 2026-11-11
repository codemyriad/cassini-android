package io.codemyriad.cassini.data.notes

import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** Android's JSONObject.NULL differs from Maven org.json: exercise the actual mobile implementation. */
@RunWith(RobolectricTestRunner::class)
class RecordedTimeMirrorsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun aJsonNullLocalTimeNeverBecomesALiteralNullMirrorOnAppendOrUtcBackfill() {
        val transcript = Transcript(listOf(Word("S1", 0, 100, "Welcome")), "en")
        val bytes = CassiniDocument.create(fixtureOpus.readBytes(), transcript, "Meeting", JSONObject())
        val original = CassiniDocument.read(bytes)
        original.manifest!!.getJSONObject("meeting").put("recordedAtLocal", JSONObject.NULL)
        // Demonstrate the Android-specific precondition; a host org.json test would miss this regression.
        assertEquals("null", original.manifest.getJSONObject("meeting").optString("recordedAtLocal"))
        val appendedBytes = CassiniDocument.create(bytes, transcript, "Ignored", JSONObject(), original)
        val appended = CassiniDocument.read(appendedBytes)
        assertFalse(appended.comments.any { it.startsWith("CASSINI_RECORDED_AT_LOCAL=") })
        val source = folder.newFile("source.opus").also { it.writeBytes(appendedBytes) }
        val target = folder.newFile("backfilled.opus")
        val at = Instant.parse("2026-11-11T12:00:00Z")
        val backfilled = CassiniDocument.withRecordedAtUtc(source, appended, at, target)
        assertFalse(backfilled.comments.any { it.startsWith("CASSINI_RECORDED_AT_LOCAL=") })
        assertEquals(at, backfilled.meetingTime?.instant)
    }
}
