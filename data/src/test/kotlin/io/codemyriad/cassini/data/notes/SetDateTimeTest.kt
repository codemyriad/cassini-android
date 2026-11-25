package io.codemyriad.cassini.data.notes

import android.net.Uri
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.format.MeetingTime
import io.codemyriad.cassini.core.library.JobKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.core.library.Origin
import io.codemyriad.cassini.core.library.SearchDate
import io.codemyriad.cassini.core.library.SearchFilters
import io.codemyriad.cassini.data.db.JobEntity
import io.codemyriad.cassini.data.db.offMain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * D112 (L-87, D-1164) "Set date and time": the start a person sets is stored in Room at once (Library day groups,
 * order and Search follow it, "Date unknown" empties) and written into the meeting's Cassini file when the file can
 * take an edit, never with the import or copy time.
 */
@RunWith(RobolectricTestRunner::class)
class SetDateTimeTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness

    private val words = transcript("good morning everyone today we discuss the delivery on Thursday")
    private val rome = ZoneId.of("Europe/Rome")
    private val start = "2026-11-23T14:36:00+01:00"
    private val startDay = LocalDate.of(2026, 11, 23)
    private val startMs = Instant.parse("2026-11-23T13:36:00Z").toEpochMilli()

    @Before fun setUp() {
        h = NotesHarness(folder)
    }

    @After fun tearDown() = h.close()

    private suspend fun insertJob(id: String, state: JobState = JobState.PAUSED) =
        h.db.jobs().insert(JobEntity(id, 1, JobKind.TRANSCRIBE_AGAIN, state, enqueuedAt = 1, updatedAt = 1))

    private fun seal(id: String, audio: File, existing: CassiniDocument?) = offMain {
        h.documents.sealBlocking(id, audio, words, "Meeting", JSONObject().put("engine", "test"), existing, emptyMap())
    }

    private suspend fun search(date: SearchDate): List<String> =
        h.notes.search("", SearchFilters(date = date), rome, LocalDate.of(2026, 11, 25)).first().map { it.row.id }

    private suspend fun libraryRow(id: String) = h.notes.library().first().single { it.id == id }

    private suspend fun importedPlain(id: String) {
        h.plainNote(id, title = "Recorder clip", audioFile = "source.opus")
        h.db.notes().update(h.row(id).copy(origin = Origin.IMPORTED_AUDIO))
    }

    @Test fun anImportedMeetingWithoutADateMovesToItsDay() = runTest {
        importedPlain("a")
        assertNull("Date unknown", libraryRow("a").recordingTime())
        assertEquals("an unknown date matches no date filter", emptyList<String>(), search(SearchDate.WEEK))

        assertTrue(h.notes.setStartedAt("a", start))

        val row = libraryRow("a")
        assertEquals(MeetingTime(MeetingTime.STARTED, start), row.recordingTime())
        assertEquals(startDay, row.recordingTime()?.day(rome))
        assertEquals("the Library orders it by its start", startMs, row.createdAt)
        assertEquals(listOf("a"), search(SearchDate.WEEK))
        assertEquals(emptyList<String>(), search(SearchDate.TODAY))
        assertEquals(start, h.row("a").startedAt)
    }

    @Test fun aStartWinsOverTheRecordingClockAndCanBeChangedAgain() = runTest {
        h.plainNote("a") // An own recording: its created_at is the capture clock.
        assertEquals(MeetingTime.UTC, h.row("a").recordingTime()?.source)
        assertTrue(h.notes.setStartedAt("a", start))
        assertEquals(MeetingTime(MeetingTime.STARTED, start), h.row("a").recordingTime())
        assertTrue(h.notes.setStartedAt("a", "2026-11-20T09:00:00-05:00"))
        assertEquals(MeetingTime(MeetingTime.STARTED, "2026-11-20T09:00:00-05:00"), h.row("a").recordingTime())
    }

    @Test fun anInvalidStartOrAMissingNoteIsRefused() = runTest {
        importedPlain("a")
        assertFalse(h.notes.setStartedAt("a", "2026-11-23T14:36:00-00:00"))
        assertFalse(h.notes.setStartedAt("a", "yesterday"))
        assertFalse(h.notes.setStartedAt("ghost", start))
        assertNull(h.row("a").startedAt)
    }

    @Test fun anOkDocumentGetsTheStartInItsFile() = runTest {
        h.documentNote("a", words)
        h.notes.refreshIfStale("a")
        val before = h.readFile("a")

        assertTrue(h.notes.setStartedAt("a", start))
        h.awaitBackground()

        val written = h.readFile("a")
        assertEquals(DocumentState.OK, written.documentState)
        assertEquals(MeetingTime(MeetingTime.STARTED, start), written.meetingTime)
        assertEquals(before.variants.map { it.id to it.body }, written.variants.map { it.id to it.body })
        assertEquals(
            "never the copy time",
            before.manifest!!.getJSONObject("meeting").getString("createdAtUtc"),
            written.manifest!!.getJSONObject("meeting").getString("createdAtUtc"),
        )
        val row = h.row("a")
        assertFalse(row.startNotInDocument())
        assertEquals(MeetingTime(MeetingTime.STARTED, start).cacheValue, row.recordedAt)
        assertEquals(startMs, row.createdAt)
        val audio = h.dir("a").list()!!.filter { it.endsWith(".opus") }
        assertEquals("the file is never renamed", listOf(NoteFiles.DOCUMENT), audio)

        // A copy carries the start, so another Cassini app reads it.
        val copy = File(folder.newFolder("out"), "copy.opus")
        assertTrue(h.documents.exportCopy("a", Uri.fromFile(copy)) is ExportOutcome.Saved)
        assertEquals(MeetingTime(MeetingTime.STARTED, start), CassiniDocument.read(copy).meetingTime)
    }

    @Test fun exportCopyWithAPendingJobWritesTheStartToTheCopyWithoutChangingTheSource() = runTest {
        for (state in listOf(JobState.PAUSED, JobState.QUEUED, JobState.RUNNING)) {
            val id = "job-${state.name.lowercase()}"
            h.documentNote(id, words)
            h.notes.refreshIfStale(id)
            insertJob(id, state)

            assertTrue(h.notes.setStartedAt(id, start))
            h.awaitBackground()
            val source = h.document(id)
            val originalBytes = source.readBytes()
            assertNull("the job's canonical source stays untouched", h.readFile(id).meetingTime)
            assertTrue(h.row(id).startNotInDocument())

            val copy = File(folder.newFolder("export-$id"), "copy.opus")
            assertTrue(h.documents.exportCopy(id, Uri.fromFile(copy)) is ExportOutcome.Saved)

            val exported = CassiniDocument.read(copy)
            assertEquals(DocumentState.OK, exported.documentState)
            assertEquals(MeetingTime(MeetingTime.STARTED, start), exported.meetingTime)
            assertArrayEquals(originalBytes, source.readBytes())
            assertNull("canonical source still has no start", h.readFile(id).meetingTime)
            assertTrue("pending start remains for adopt", h.row(id).startNotInDocument())
            assertFalse("staging directory is cleaned up", h.paths.exports.exists())
        }
    }

    @Test fun aStartSetWhileAJobRunsIsWrittenByTheAdopt() = runTest {
        h.documentNote("a", words)
        h.notes.refreshIfStale("a")
        insertJob("a", JobState.RUNNING)

        assertTrue(h.notes.setStartedAt("a", start))
        h.awaitBackground()
        assertNull("the document is the job's source", h.readFile("a").meetingTime)
        assertEquals(EditOutcome.JobPending, h.documents.writeStart("a"))
        assertEquals("Room shows it at once", MeetingTime(MeetingTime.STARTED, start), h.row("a").recordingTime())

        seal("a", h.document("a"), existing = h.documents.read("a"))
        assertEquals(AdoptOutcome.Adopted(DocumentState.OK), h.notes.adoptSealed("a"))

        val written = h.readFile("a")
        assertEquals(MeetingTime(MeetingTime.STARTED, start), written.meetingTime)
        assertEquals(2, written.variants.size)
        assertFalse(h.row("a").startNotInDocument())
    }

    @Test fun theFirstSealOfAPlainImportCarriesTheStart() = runTest {
        importedPlain("a")
        assertTrue(h.notes.setStartedAt("a", start))
        seal("a", File(h.dir("a"), "source.opus"), existing = null)
        assertEquals(AdoptOutcome.Adopted(DocumentState.OK), h.notes.adoptSealed("a"))
        assertEquals(MeetingTime(MeetingTime.STARTED, start), h.readFile("a").meetingTime)
        assertFalse(h.row("a").startNotInDocument())
        assertEquals(startMs, h.row("a").createdAt)
    }

    @Test fun aStartNoWriteReachedIsWrittenAtTheNextStart() = runTest {
        h.documentNote("a", words)
        h.documentNote("b", words)
        listOf("a", "b").forEach { h.notes.refreshIfStale(it) }
        listOf("a", "b").forEach { insertJob(it, JobState.QUEUED) }
        h.notes.setStartedAt("a", start)
        h.notes.setStartedAt("b", start)
        h.awaitBackground()
        h.db.jobs().delete("a") // Removed from the queue: no adopt.

        h.restart()
        assertEquals(listOf("a"), h.documents.writeDirtyStarts())
        assertEquals(MeetingTime(MeetingTime.STARTED, start), h.readFile("a").meetingTime)
        assertTrue("b still has a job, whose adopt writes it", h.row("b").startNotInDocument())
        assertEquals(emptyList<String>(), h.documents.writeDirtyStarts())
    }

    @Test fun anUnverifiedDocumentKeepsTheStartInRoom() = runTest {
        h.documentNote("a", words)
        val file = h.document("a")
        // One flipped audio byte near the end: the manifest still reads, the audio digest no longer matches.
        val bytes = file.readBytes()
        bytes[bytes.size - 10] = (bytes[bytes.size - 10].toInt() xor 0x55).toByte()
        file.writeBytes(bytes)
        h.notes.refreshIfStale("a")
        val state = h.row("a").documentState
        assertTrue(state.toString(), state != DocumentState.OK)

        assertTrue(h.notes.setStartedAt("a", start))
        h.awaitBackground()
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(MeetingTime(MeetingTime.STARTED, start), h.row("a").recordingTime())
        h.notes.refreshIfStale("a")
        assertEquals(start, h.row("a").startedAt)
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        org.junit.Assert.assertArrayEquals(expected, actual)
}
