package io.codemyriad.cassini.core.format

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * D112 (L-87, D-1164) "Set date and time": the start a person sets is RFC 3339 with their offset, wins over every
 * other start (LIBRARY-RULES R6.2), travels in the `.opus` file (MAPPING.md) and is read back from the shared
 * round-trip vectors of cassini-ux D-1171.
 */
class StartedAtTest {
    @get:Rule val folder = TemporaryFolder()

    private val conformance = File(
        System.getProperty("cassini.conformance", "../third_party/cassini-format/spec/conformance")!!,
    )
    private val vendor = File(System.getProperty("cassini.uxVendor", "../third_party/cassini-ux")!!)

    private fun roundTrip(name: String) = File(vendor, "vectors/library-vectors/round-trip/$name")

    /** Conformance vector 016: an OK file with a recordedAtUtc-less meeting and two transcripts. */
    private fun okDocument(): File {
        val vector = File(conformance, "opus/016-two-transcripts.opus")
        assertTrue("Missing $vector: run git submodule update --init", vector.isFile)
        return vector.copyTo(File(folder.root, "016.opus"))
    }

    // The value.

    @Test fun parsesOffsetsInTheContractRangeAndFloorsSeconds() {
        assertEquals("2026-11-23T14:36:00+01:00", StartedAt.parse("2026-11-23T14:36:00+01:00")?.let(StartedAt::format))
        assertEquals("2026-11-23T14:36:09+00:00", StartedAt.parse("2026-11-23T14:36:09.987Z")?.let(StartedAt::format))
        assertEquals("2026-11-23T14:36:00-12:00", StartedAt.parse("2026-11-23T14:36:00-12:00")?.let(StartedAt::format))
        assertEquals("2026-11-23T14:36:00+14:00", StartedAt.parse("2026-11-23T14:36:00+14:00")?.let(StartedAt::format))
        assertEquals("2026-11-23T14:36:00+05:45", StartedAt.parse("2026-11-23T14:36:00+05:45")?.let(StartedAt::format))
    }

    @Test fun refusesAnUnknownOrOutOfRangeOffsetAndOtherShapes() {
        listOf(
            "2026-11-23T14:36:00-00:00",
            "2026-11-23T14:36:00+14:30",
            "2026-11-23T14:36:00-12:30",
            "2026-11-23T14:36:00",
            "2026-11-23 14:36:00+01:00",
            "2026-11-23T14:36+01:00",
            "2026-02-30T14:36:00+01:00",
            "",
        ).forEach { assertNull(it, StartedAt.parse(it)) }
    }

    @Test fun aPickedDayAndMinuteTakeTheZonesOffsetOnThatDay() {
        val rome = ZoneId.of("Europe/Rome")
        assertEquals(
            "2026-11-23T14:36:00+01:00",
            StartedAt.format(StartedAt.of(LocalDate.of(2026, 11, 23), LocalTime.of(14, 36, 59), rome))
        )
        assertEquals(
            "2026-07-01T09:05:00+02:00",
            StartedAt.format(StartedAt.of(LocalDate.of(2026, 7, 1), LocalTime.of(9, 5), rome))
        )
        assertEquals("2026-11-23T14:36:00", StartedAt.localPart(StartedAt.parse("2026-11-23T14:36:00+01:00")!!))
    }

    // The reader.

    private fun manifest(meeting: JSONObject, ux: JSONObject? = null) = JSONObject().put("meeting", meeting).apply {
        if (ux != null) put("x", JSONObject().put(MeetingTime.UX_NAMESPACE, ux))
    }

    @Test fun aStartThePersonSetWinsOverEveryOtherStart() {
        val meeting = JSONObject()
            .put("createdAtUtc", "2026-11-24T08:15:00Z")
            .put("recordedAtUtc", "2026-11-20T10:00:00Z")
            .put("startedAt", "2026-11-20T11:00:00+01:00")
            .put("recordedAtLocal", "2026-11-20T11:00:00")
        val ux = JSONObject().put("namePrefixSource", "import-time").put(
            "meeting",
            JSONObject().put("startedAt", "2026-11-23T14:36:00+01:00").put("startedAtSource", "user"),
        )
        val time = MeetingTime.resolve(manifest(meeting, ux), listOf("DATE=2026-11-21T09:00:00+01:00"))
        assertEquals(MeetingTime(MeetingTime.STARTED, "2026-11-23T14:36:00+01:00"), time)
        assertEquals(java.time.Instant.parse("2026-11-23T13:36:00Z"), time?.instant)
    }

    @Test fun theA4StartComesBeforeTheUtcInstantAndDate() {
        val meeting = JSONObject()
            .put("createdAtUtc", "2026-11-24T08:15:00Z")
            .put("recordedAtUtc", "2026-11-20T10:00:00Z")
            .put("startedAt", "2026-11-23T14:36:00+01:00")
        assertEquals(
            MeetingTime(MeetingTime.STARTED, "2026-11-23T14:36:00+01:00"),
            MeetingTime.resolve(manifest(meeting), listOf("DATE=2026-11-21T09:00:00+01:00")),
        )
        // An invalid A4 value is ignored, never guessed.
        meeting.put("startedAt", "2026-11-23T14:36:00-00:00")
        assertEquals(
            MeetingTime(MeetingTime.UTC, "2026-11-20T10:00:00Z"),
            MeetingTime.resolve(manifest(meeting), emptyList())
        )
    }

    @Test fun anImportTimePrefixMakesLegacyDatesUnknown() {
        val meeting = JSONObject()
            .put("createdAtUtc", "2026-11-24T08:15:00Z")
            .put("startedAt", "2026-11-24T09:15:00+01:00")
            .put("recordedAtLocal", "2026-11-24T09:15:00")
        val ux = JSONObject().put("namePrefixSource", "import-time").put("meeting", JSONObject())
        assertNull(MeetingTime.resolve(manifest(meeting, ux), listOf("DATE=2026-11-24T09:15:00+01:00")))
        // Without the import-time prefix, a DATE that is not the packaging instant is the start (S-12 Q8, D95).
        val packed = JSONObject().put("createdAtUtc", "2026-11-25T08:15:00Z")
        assertEquals(
            MeetingTime(MeetingTime.DATE, "2026-11-24T09:15:00+01:00"),
            MeetingTime.resolve(manifest(packed), listOf("DATE=2026-11-24T09:15:00+01:00")),
        )
        assertNull(MeetingTime.resolve(manifest(packed, ux), listOf("DATE=2026-11-24T09:15:00+01:00")))
    }

    @Test fun theCacheKeepsTheSource() {
        val time = MeetingTime.started("2026-11-23T14:36:00+01:00")!!
        assertEquals("startedAt=2026-11-23T14:36:00+01:00", time.cacheValue)
        assertEquals(time, MeetingTime.cached(time.cacheValue))
        assertNull(time.label)
        assertEquals(LocalDate.of(2026, 11, 23), time.day(ZoneId.of("Europe/Rome")))
    }

    // The shared round-trip vectors (cassini-ux D-1171, pinned).

    private fun sha256(file: File) =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun theVendoredVectorsMatchTheirPins() {
        val pins = JSONObject(File(vendor, "UPSTREAM.json").readText()).getJSONObject("files")
        listOf(
            "user-started-at.opus",
            "user-started-at.expected.json",
            "legacy-import-time-date.opus",
            "legacy-import-time-date.expected.json"
        ).forEach { name ->
            val pin = pins.getJSONObject("vectors/library-vectors/round-trip/$name")
            assertEquals(name, pin.getString("sha256"), sha256(roundTrip(name)))
        }
    }

    @Test fun readsTheStartAnotherAppSetFromItsFile() {
        val expected = JSONObject(roundTrip("user-started-at.expected.json").readText())
        val document = CassiniDocument.read(roundTrip("user-started-at.opus"))
        assertTrue(document.state, document.manifest != null)
        val time = MeetingTime.resolve(document.manifest, document.comments)
        assertEquals(MeetingTime(MeetingTime.STARTED, expected.getString("manifestStartedAt")), time)
        assertEquals(
            expected.getString("dateTag"),
            document.comments.first { it.startsWith("DATE=") }.substringAfter('=')
        )
        assertFalse(expected.getBoolean("inDateUnknownGroup"))
    }

    @Test fun aLegacyImportTimeDateIsNotTheStart() {
        val expected = JSONObject(roundTrip("legacy-import-time-date.expected.json").readText())
        val document = CassiniDocument.read(roundTrip("legacy-import-time-date.opus"))
        assertTrue(document.comments.any { it == "DATE=" + expected.getString("legacyDateTagPresent") })
        assertNull(MeetingTime.resolve(document.manifest, document.comments))
        assertTrue(expected.getBoolean("inDateUnknownGroup"))
    }

    // The writer.

    @Test fun writesTheStartIntoTheFileAndKeepsEverythingElse() {
        val source = okDocument()
        val before = CassiniDocument.read(source)
        assertEquals("ok", before.state)
        val written = CassiniDocument.withStartedAt(
            source,
            before,
            "2026-11-23T14:36:00+01:00",
            File(folder.root, "dated.opus")
        )

        assertEquals("ok", written.state)
        assertEquals(MeetingTime(MeetingTime.STARTED, "2026-11-23T14:36:00+01:00"), written.meetingTime)
        val meeting = written.manifest!!.getJSONObject("meeting")
        assertEquals("2026-11-23T14:36:00+01:00", meeting.getString("startedAt"))
        assertEquals("2026-11-23T13:36:00Z", meeting.getString("recordedAtUtc"))
        assertEquals("2026-11-23T14:36:00", meeting.getString("recordedAtLocal"))
        val beforeMeeting = before.manifest!!.getJSONObject("meeting")
        assertEquals("never the copy time", beforeMeeting.getString("createdAtUtc"), meeting.getString("createdAtUtc"))
        assertEquals(beforeMeeting.getString("id"), meeting.getString("id"))
        assertEquals(beforeMeeting.getString("title"), meeting.getString("title"))
        assertEquals(before.variants.map { it.id to it.body }, written.variants.map { it.id to it.body })
        assertEquals(listOf("DATE=2026-11-23T14:36:00+01:00"), written.comments.filter { it.startsWith("DATE=") })
        assertTrue(written.comments.contains("CASSINI_RECORDED_AT_UTC=2026-11-23T13:36:00Z"))
        assertTrue(written.comments.contains("CASSINI_RECORDED_AT_LOCAL=2026-11-23T14:36:00"))
        assertFalse(
            "Android keeps no Library v2 session",
            written.manifest!!.optJSONObject("x")?.has(MeetingTime.UX_NAMESPACE) == true
        )

        // Setting another start replaces it; the tags are not duplicated.
        val dated = File(folder.root, "dated.opus")
        val again = CassiniDocument.withStartedAt(
            dated,
            written,
            "2026-11-22T08:00:00-05:00",
            File(folder.root, "again.opus")
        )
        assertEquals(MeetingTime(MeetingTime.STARTED, "2026-11-22T08:00:00-05:00"), again.meetingTime)
        assertEquals(1, again.comments.count { it.startsWith("DATE=") })
    }

    @Test fun updatesALibraryV2SessionAlreadyInTheFile() {
        val vector = roundTrip("legacy-import-time-date.opus").copyTo(File(folder.root, "legacy.opus"))
        val before = CassiniDocument.read(vector)
        if (before.documentState != DocumentState.OK) return // The vector's audio digest decides whether it can take an edit.
        val written = CassiniDocument.withStartedAt(
            vector,
            before,
            "2026-11-23T14:36:00+01:00",
            File(folder.root, "set.opus")
        )
        val ux = written.manifest!!.getJSONObject("x").getJSONObject(MeetingTime.UX_NAMESPACE)
        assertEquals("2026-11-23T14:36:00+01:00", ux.getJSONObject("meeting").getString("startedAt"))
        assertEquals("user", ux.getJSONObject("meeting").getString("startedAtSource"))
        assertEquals("the file is never renamed", "import-time", ux.getString("namePrefixSource"))
        assertEquals(MeetingTime(MeetingTime.STARTED, "2026-11-23T14:36:00+01:00"), written.meetingTime)
    }

    @Test fun aNewVersionKeepsTheStartThePersonSet() {
        val source = okDocument()
        val dated = File(folder.root, "dated.opus")
        val written = CassiniDocument.withStartedAt(
            source,
            CassiniDocument.read(source),
            "2026-11-23T14:36:00+01:00",
            dated
        )
        val transcript = written.selected(null)!!.transcript!!
        val resealed = CassiniDocument.create(
            dated,
            transcript,
            "T",
            JSONObject(),
            written,
            emptyMap(),
            File(folder.root, "v3.opus"),
            recordedAtUtc = java.time.Instant.parse("2026-01-01T00:00:00Z"),
        )
        assertEquals(MeetingTime(MeetingTime.STARTED, "2026-11-23T14:36:00+01:00"), resealed.meetingTime)
        assertEquals(
            "the capture clock never replaces it",
            "2026-11-23T13:36:00Z",
            resealed.manifest!!.getJSONObject("meeting").getString("recordedAtUtc")
        )
        assertEquals(listOf("DATE=2026-11-23T14:36:00+01:00"), resealed.comments.filter { it.startsWith("DATE=") })
    }
}
