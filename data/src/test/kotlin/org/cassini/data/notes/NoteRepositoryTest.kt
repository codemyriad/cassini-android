package org.cassini.data.notes

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.library.Origin
import org.cassini.core.library.PreviewText
import org.cassini.data.db.JobEntity
import org.cassini.data.db.TrashedJob
import org.cassini.data.db.offMain
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
import java.time.ZoneId

/** NoteRepository (docs/architecture.md, section 4.1; features.md sections 3.3, 3.4, 6 and 8). */
@RunWith(RobolectricTestRunner::class)
class NoteRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness

    private val words = transcript(
        "allora iniziamo con la consegna di giovedì poi passiamo al budget e chiudiamo con le domande aperte di tutti",
        turn = 5,
    )

    @Before fun setUp() {
        h = NotesHarness(folder)
    }

    @After fun tearDown() = h.close()

    private suspend fun insertJob(id: String, state: JobState, kind: JobKind = JobKind.TRANSCRIBE) =
        h.db.jobs().insert(JobEntity(id, 1, kind, state, enqueuedAt = 1, updatedAt = 1))

    @Test fun aRecordingStartsLiveAndEndsPlain() = runTest {
        val id = h.notes.createRecording(" Registrazione 9 ott ", createdAt = 42, format = "opus")

        assertTrue(h.dir(id).isDirectory)
        val row = h.row(id)
        assertEquals("Registrazione 9 ott", row.title)
        assertEquals(AudioKind.LIVE_CAPTURE, row.audioKind)
        assertEquals("capture.opus", row.audioFile)
        assertEquals(Origin.RECORDED, row.origin)
        assertEquals(42L, row.createdAt)
        assertEquals(listOf(id), h.notes.library().first().map { it.id })

        assertTrue(h.notes.finishRecording(id, 61_000))
        assertEquals(AudioKind.PLAIN, h.row(id).audioKind)
        assertEquals(61_000L, h.row(id).durationMs)
        assertFalse("only a live capture finishes", h.notes.finishRecording(id, 1))
        assertTrue(h.notes.markUnplayable(id))
        assertEquals(AudioKind.UNPLAYABLE, h.row(id).audioKind)
    }

    @Test fun aRenameOfAnOkDocumentIsWrittenIntoItInTheBackground() = runTest {
        h.documentNote("a", words, title = "Riunione")
        h.notes.refreshIfStale("a")

        assertEquals(RenameOutcome.Renamed, h.notes.rename("a", "Riunione di budget"))
        h.awaitBackground()

        assertEquals("Riunione di budget", h.readFile("a").title)
        assertFalse(h.row("a").titleDirty)
        assertEquals(RefreshOutcome.Fresh, h.notes.refreshIfStale("a"))
        assertEquals(listOf("a"), h.notes.search("budget").first().map { it.row.id })
    }

    @Test fun aRenameOfAPlainNoteOnlyChangesTheRow() = runTest {
        h.plainNote("a")
        val bytes = File(h.dir("a"), "capture.opus").readBytes()

        assertEquals(RenameOutcome.Renamed, h.notes.rename("a", "Nuovo"))
        h.awaitBackground()

        assertEquals("Nuovo", h.row("a").title)
        assertFalse(h.row("a").titleDirty)
        assertTrue(bytes.contentEquals(File(h.dir("a"), "capture.opus").readBytes()))
    }

    @Test fun invalidTitlesAreRefused() = runTest {
        h.plainNote("a", title = "Prima")
        assertEquals(RenameOutcome.Invalid, h.notes.rename("a", "   "))
        assertEquals(RenameOutcome.Invalid, h.notes.rename("a", "x".repeat(NoteTitles.MAX_LENGTH + 1)))
        assertEquals(RenameOutcome.Renamed, h.notes.rename("a", "x".repeat(NoteTitles.MAX_LENGTH)))
        assertEquals(RenameOutcome.NoNote, h.notes.rename("missing", "Titolo"))
    }

    @Test fun theFinalPositionStoresThePreviewExcerpt() = runTest {
        h.documentNote("a", words)
        h.notes.refreshIfStale("a")

        h.notes.setPosition("a", 2_600)
        assertEquals(2_600L, h.row("a").positionMs)
        val start = PreviewText.of(words.words, 0)?.display
        assertEquals("a plain save keeps the preview", start, h.row("a").preview)

        h.notes.setPosition("a", 2_600, final = true)
        val expected = PreviewText.of(words.words, 2_600)!!.display
        assertTrue(expected, expected.startsWith("…di giovedì"))
        assertEquals(expected, h.row("a").preview)
        assertEquals(expected, h.notes.library().first().single().preview)
    }

    @Test fun selectingAVariantRewritesTheBodyAndThePreview() = runTest {
        h.documentNote("a", words)
        h.notes.refreshIfStale("a")
        val merged = (h.documents.mergeSpeakers("a", "words", "S2", "S1") as EditOutcome.Done).variantId!!
        h.notes.setPosition("a", 2_600, final = true)

        assertTrue(h.notes.selectVariant("a", "words"))

        assertEquals("words", h.row("a").selectedVariant)
        assertEquals(PreviewText.of(words.words, 2_600)!!.display, h.row("a").preview)
        assertEquals(words.words.joinToString(" ") { it.text }, h.db.notes().text("a")!!.body)
        assertEquals("words", h.notes.library().first().single().selectedVariant)
        assertFalse(h.notes.selectVariant("a", "nope"))
        assertTrue(h.notes.selectVariant("a", merged))
        assertEquals(merged, h.row("a").selectedVariant)
    }

    @Test fun aBlankSearchYieldsTheLibrary() = runTest {
        h.documentNote("a", words)
        h.plainNote("b")
        h.notes.refreshIfStale("a")

        assertEquals(setOf("a", "b"), h.notes.search("  ").first().map { it.row.id }.toSet())
        assertEquals(listOf("a"), h.notes.search("GIOVEDI").first().map { it.row.id })
    }

    @Test fun trashRecordsTheJobAndRestoreRequeuesAQueuedOne() = runTest {
        h.plainNote("q")
        h.plainNote("p")
        insertJob("q", JobState.QUEUED, JobKind.TRANSCRIBE)
        insertJob("p", JobState.PAUSED, JobKind.TRANSCRIBE_AGAIN)

        assertTrue(h.notes.moveToTrash("q"))
        h.now += 1_000
        assertTrue(h.notes.moveToTrash("p"))
        assertFalse("already trashed", h.notes.moveToTrash("q"))

        assertEquals(TrashedJob(JobState.QUEUED, JobKind.TRANSCRIBE), h.row("q").trashedJob)
        assertEquals(TrashedJob(JobState.PAUSED, JobKind.TRANSCRIBE_AGAIN), h.row("p").trashedJob)
        assertNull("a queued job row is deleted", h.db.jobs().job("q"))
        assertEquals("a paused job row stays", JobState.PAUSED, h.db.jobs().job("p")!!.state)
        assertTrue(h.notes.library().first().isEmpty())
        assertEquals(listOf("q", "p"), h.notes.trash().first().map { it.id })

        assertTrue(h.notes.restore("q"))
        assertTrue(h.notes.restore("p"))
        assertFalse("not trashed", h.notes.restore("q"))

        assertEquals(listOf("requeue q TRANSCRIBE"), h.ports.calls)
        assertNull(h.row("q").trashedAt)
        assertNull(h.row("q").trashedJob)
        assertEquals("a paused job was kept", JobState.PAUSED, h.db.jobs().job("p")!!.state)
    }

    @Test fun deleteNowRemovesTheJobVoicesFilesThenTheRow() = runTest {
        h.documentNote("a", words)
        h.notes.refreshIfStale("a")
        h.ports.onDeleteForNote = { id ->
            // The voice files go while the note directory and the row still exist.
            check(h.dir(id).isDirectory)
            check(offMain { h.db.notes().noteBlocking(id) } != null)
        }

        assertFalse("a live note is not deleted", h.notes.deleteNow("a"))
        assertTrue(h.dir("a").isDirectory)

        h.notes.moveToTrash("a")
        assertTrue(h.notes.deleteNow("a"))

        assertEquals(listOf("cancel a", "voices a"), h.ports.calls)
        assertFalse(h.dir("a").exists())
        assertNull(h.db.notes().note("a"))
        assertEquals(0, h.db.cache().variants("a").size)
    }

    @Test fun purgeExpiredDeletesOnlyNotesWhoseDaysAreOver() = runTest {
        val zone = ZoneId.of("Europe/Rome")
        val now = Instant.parse("2026-11-01T09:00:00Z")
        h.plainNote("old")
        h.plainNote("recent")
        h.now = Instant.parse("2026-10-02T10:00:00Z").toEpochMilli()
        h.notes.moveToTrash("old")
        h.now = Instant.parse("2026-10-09T10:00:00Z").toEpochMilli()
        h.notes.moveToTrash("recent")

        assertEquals(1, h.notes.purgeExpired(now, zone))

        assertNull(h.db.notes().note("old"))
        assertFalse(h.dir("old").exists())
        assertTrue(h.dir("recent").isDirectory)
        assertEquals(1, h.notes.emptyTrash())
        assertFalse(h.dir("recent").exists())
        assertEquals(0, h.notes.counts().first().trashed)
    }

    @Test fun theInferenceThreadReadsTheTitle() = runTest {
        h.plainNote("a", title = "Titolo")
        assertEquals("Titolo", offMain { h.notes.titleBlocking("a") })
        assertNull(offMain { h.notes.rowBlocking("missing") })
    }
}
