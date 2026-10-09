package org.cassini.data.notes

import android.net.Uri
import kotlinx.coroutines.test.runTest
import org.cassini.core.export.ExportTranscript
import org.cassini.core.export.SrtWriter
import org.cassini.core.export.TextExport
import org.cassini.core.export.VttWriter
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.DocumentState
import org.cassini.core.format.Transcript
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.library.VariantKind
import org.cassini.data.DocumentSignature
import org.cassini.data.db.JobEntity
import org.cassini.data.db.offMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** DocumentRepository and the adopt path of docs/architecture.md, sections 3.1, 3.2 and 4.3. */
@RunWith(RobolectricTestRunner::class)
class DocumentRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness

    private val first = transcript("buongiorno a tutti oggi parliamo della consegna di giovedì")
    private val second = transcript("buongiorno a tutti oggi parliamo della consegna di venerdì", turn = 4)

    @Before fun setUp() {
        h = NotesHarness(folder)
    }

    @After fun tearDown() = h.close()

    private fun work(id: String) = h.paths.work(id)

    private fun seal(id: String, audio: File, words: Transcript, title: String, existing: CassiniDocument? = null) = offMain {
        h.documents.sealBlocking(id, audio, words, title, JSONObject().put("engine", "test"), existing, emptyMap())
    }

    private suspend fun insertJob(id: String, state: JobState = JobState.PAUSED) =
        h.db.jobs().insert(JobEntity(id, 1, JobKind.TRANSCRIBE_AGAIN, state, enqueuedAt = 1, updatedAt = 1))

    // Adopt.

    @Test fun adoptSealedReplacesTheCaptureAndDeletesIt() = runTest {
        h.plainNote("a", title = "Registrazione del mattino")
        val capture = File(h.dir("a"), "capture.opus")
        seal("a", capture, first, "Registrazione del mattino")
        assertTrue(File(work("a"), "sealed").isFile)
        assertTrue(File(work("a"), "sealed.opus").isFile)
        assertFalse("seal never touches document.opus", h.document("a").exists())

        assertEquals(AdoptOutcome.Adopted(DocumentState.OK), h.notes.adoptSealed("a"))

        assertFalse(capture.exists())
        assertFalse(work("a").exists())
        assertTrue(h.isOk("a"))
        val row = h.row("a")
        assertEquals(AudioKind.DOCUMENT, row.audioKind)
        assertEquals(NoteFiles.DOCUMENT, row.audioFile)
        assertEquals(DocumentState.OK, row.documentState)
        assertEquals(DocumentSignature.of(h.document("a")), row.documentSignature)
        assertEquals("words", row.selectedVariant)
        assertFalse(row.titleDirty)
        assertEquals(first.words.size, h.notes.words("a", "words").size)
        assertEquals(0, h.reader.reads.get()) // The seal's verified read is reused: the adopt reads nothing.
        assertEquals(RefreshOutcome.Fresh, h.notes.refreshIfStale("a"))

        assertEquals(AdoptOutcome.NothingToAdopt, h.notes.adoptSealed("a"))
    }

    @Test fun aCrashAfterTheRenameIsRepairedByRunningAdoptAgain() = runTest {
        h.plainNote("a")
        seal("a", File(h.dir("a"), "capture.opus"), first, "Registrazione")
        Files.move(File(work("a"), "sealed.opus").toPath(), h.document("a").toPath(), StandardCopyOption.ATOMIC_MOVE)
        assertEquals(AudioKind.PLAIN, h.row("a").audioKind)

        h.restart()
        assertEquals(AdoptOutcome.Adopted(DocumentState.OK), h.notes.adoptSealed("a"))

        assertEquals(AudioKind.DOCUMENT, h.row("a").audioKind)
        assertEquals(DocumentSignature.of(h.document("a")), h.row("a").documentSignature)
        assertFalse(File(h.dir("a"), "capture.opus").exists())
        assertFalse(work("a").exists())
        assertEquals(AdoptOutcome.NothingToAdopt, h.notes.adoptSealed("a"))
    }

    @Test fun aCrashAfterTheRenameIsRepairedByRefreshIfStale() = runTest {
        h.plainNote("a")
        seal("a", File(h.dir("a"), "capture.opus"), first, "Registrazione")
        Files.move(File(work("a"), "sealed.opus").toPath(), h.document("a").toPath(), StandardCopyOption.ATOMIC_MOVE)

        h.restart()
        assertEquals(RefreshOutcome.Refreshed(DocumentState.OK), h.notes.refreshIfStale("a"))

        val row = h.row("a")
        assertEquals(AudioKind.DOCUMENT, row.audioKind)
        assertEquals(NoteFiles.DOCUMENT, row.audioFile)
        assertEquals(first.words.size, h.notes.words("a", "words").size)
        // The adopt that the next start runs still finishes the clean-up.
        assertEquals(AdoptOutcome.Adopted(DocumentState.OK), h.notes.adoptSealed("a"))
        assertFalse(File(h.dir("a"), "capture.opus").exists())
        assertFalse(work("a").exists())
    }

    @Test fun aCrashAfterTheRenameOfTranscribeAgainIsRepairedByRefreshIfStale() = runTest {
        h.documentNote("a", first)
        h.notes.refreshIfStale("a")
        seal("a", h.document("a"), second, "ignored", existing = h.documents.read("a"))
        Files.move(File(work("a"), "sealed.opus").toPath(), h.document("a").toPath(), StandardCopyOption.ATOMIC_MOVE)

        h.restart()
        assertTrue(h.notes.refreshIfStale("a") is RefreshOutcome.Refreshed)
        assertEquals(2, h.db.cache().variants("a").size)
    }

    @Test fun anUnreadableSealedOutputIsDroppedWithItsMarker() = runTest {
        h.plainNote("a")
        seal("a", File(h.dir("a"), "capture.opus"), first, "Registrazione")
        val sealed = File(work("a"), "sealed.opus")
        sealed.writeBytes(sealed.readBytes().copyOf(sealed.length().toInt() - 1)) // Unverified: the last page is cut.

        h.restart()
        assertEquals(AdoptOutcome.Unreadable, h.notes.adoptSealed("a"))

        assertFalse(sealed.exists())
        assertFalse(File(work("a"), "sealed").exists())
        assertTrue("the capture stays", File(h.dir("a"), "capture.opus").isFile)
        assertEquals(AudioKind.PLAIN, h.row("a").audioKind)
    }

    // Titles.

    @Test fun aRenameWhileAJobRowExistsSetsTitleDirtyAndAdoptWritesIt() = runTest {
        h.documentNote("a", first, title = "Riunione")
        h.notes.refreshIfStale("a")
        insertJob("a", JobState.RUNNING)

        assertEquals(RenameOutcome.Renamed, h.notes.rename("a", "  Riunione di lunedì  "))
        h.awaitBackground()

        assertEquals("Riunione di lunedì", h.row("a").title)
        assertTrue(h.row("a").titleDirty)
        assertEquals("the document is the job's source", "Riunione", h.readFile("a").title)
        assertEquals(EditOutcome.JobPending, h.documents.retitle("a"))

        val title = offMain { h.notes.titleBlocking("a") }!!
        seal("a", h.document("a"), second, title, existing = h.documents.read("a"))
        assertEquals(AdoptOutcome.Adopted(DocumentState.OK), h.notes.adoptSealed("a"))

        val written = h.readFile("a")
        assertEquals("Riunione di lunedì", written.title)
        assertTrue(written.comments.contains("TITLE=Riunione di lunedì"))
        assertEquals(2, written.variants.size)
        assertFalse(h.row("a").titleDirty)
        assertEquals(DocumentSignature.of(h.document("a")), h.row("a").documentSignature)
    }

    @Test fun aRenameOfAnUnverifiedDocumentStaysInRoomAndSurvivesRefreshIfStale() = runTest {
        h.documentNote("a", first, title = "Riunione")
        val file = h.document("a")
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 1))
        assertEquals(RefreshOutcome.Refreshed(DocumentState.UNVERIFIED), h.notes.refreshIfStale("a"))
        val bytes = file.readBytes()

        assertEquals(RenameOutcome.Renamed, h.notes.rename("a", "Nuovo titolo"))
        h.awaitBackground()
        assertTrue(file.setLastModified(file.lastModified() + 5_000)) // Forces a fresh derivation.
        h.restart()
        assertTrue(h.notes.refreshIfStale("a") is RefreshOutcome.Refreshed)

        assertEquals("Nuovo titolo", h.row("a").title)
        assertTrue(h.row("a").titleDirty)
        assertArrayEquals("a document that is not OK is never sealed again", bytes, file.readBytes())
        assertEquals(EditOutcome.NotEditable(DocumentState.UNVERIFIED), h.documents.retitle("a"))
        val text = h.db.notes().text("a")!!
        assertEquals("Nuovo titolo", text.title)
    }

    // Edits.

    @Test fun aMergeCreatesVersionNPlusOneAndSelectsIt() = runTest {
        h.documentNote("a", first)
        h.notes.refreshIfStale("a")

        val outcome = h.documents.mergeSpeakers("a", "words", source = "S2", target = "S1")

        assertTrue(outcome.toString(), outcome is EditOutcome.Done)
        val merged = (outcome as EditOutcome.Done).variantId!!
        val variants = h.db.cache().variants("a")
        assertEquals(listOf("words", merged), variants.map { it.variantId })
        val created = variants.last()
        assertEquals(VariantKind.MERGED, created.kind)
        assertEquals("words", created.derivedFrom)
        assertTrue(created.isDefault)
        assertFalse(variants.first().isDefault)
        assertEquals(1, created.speakerCount)
        assertEquals(merged, h.row("a").selectedVariant)
        assertEquals(listOf("S1"), h.db.cache().speakers("a", merged).map { it.speakerId })
        val document = h.readFile("a")
        assertEquals(DocumentState.OK, document.documentState)
        assertEquals(merged, document.defaultId)
        assertEquals(setOf("S1"), document.selected(merged)!!.transcript!!.words.map { it.speaker }.toSet())
        assertFalse(File(h.dir("a"), NoteFiles.DOCUMENT_TMP).exists())

        val again = h.documents.mergeSpeakers("a", merged, source = "S1", target = "S1")
        assertTrue(again is EditOutcome.Invalid)
    }

    @Test fun relabelAndMergeAreRefusedWhileAJobRowExists() = runTest {
        h.documentNote("a", first)
        h.notes.refreshIfStale("a")
        insertJob("a")
        val bytes = h.document("a").readBytes()

        assertEquals(EditOutcome.JobPending, h.documents.relabel("a", mapOf("S1" to "Giulia")))
        assertEquals(EditOutcome.JobPending, h.documents.mergeSpeakers("a", "words", "S2", "S1"))
        assertArrayEquals(bytes, h.document("a").readBytes())
    }

    @Test fun relabelWritesTheDocumentAndTheSpeakerRows() = runTest {
        h.documentNote("a", first)
        h.notes.refreshIfStale("a")

        assertEquals(EditOutcome.Done(), h.documents.relabel("a", mapOf("S1" to "Giulia")))

        assertEquals("Giulia", h.readFile("a").speakerLabel("S1"))
        val speakers = h.db.cache().speakers("a", "words")
        assertEquals(listOf("Giulia", "S2"), speakers.map { it.label })
        assertEquals(listOf(false, true), speakers.map { it.isPlaceholder })
        assertNull(speakers.first().placeholderNumber)
        assertEquals(DocumentSignature.of(h.document("a")), h.row("a").documentSignature)
        assertTrue(h.documents.relabel("a", mapOf("S9" to "Luca")) is EditOutcome.Invalid)
    }

    @Test fun editsOfAPlainNoteAreRefused() = runTest {
        h.plainNote("a")
        assertEquals(EditOutcome.NotEditable(null), h.documents.relabel("a", mapOf("S1" to "Giulia")))
        assertEquals(EditOutcome.NoNote, h.documents.retitle("missing"))
    }

    // Exports.

    @Test fun exportsEqualTheExpectedBytes() = runTest {
        h.documentNote("a", first, title = "Consegna: giovedì?")
        h.notes.refreshIfStale("a")
        val labels = mapOf("S1" to "Giulia", "S2" to "Speaker 2")
        val expected = ExportTranscript("Consegna: giovedì?", h.row("a").durationMs, first.words, labels)
        val out = folder.newFolder("out")

        val copy = File(out, "copy.opus")
        assertTrue(h.documents.exportCopy("a", Uri.fromFile(copy)) is ExportOutcome.Saved)
        assertArrayEquals(h.document("a").readBytes(), copy.readBytes())

        val cases = mapOf(
            TranscriptFormat.TXT to TextExport.write(expected, "8 Oct 2026, 15:10", "No words"),
            TranscriptFormat.SRT to SrtWriter.write(expected),
            TranscriptFormat.VTT to VttWriter.write(expected),
        )
        for ((format, text) in cases) {
            val dest = File(out, "export.${format.extension}")
            dest.writeText("stale content that is longer than any export ".repeat(20))
            val request = TranscriptExport(format, labels, subtitle = "8 Oct 2026, 15:10", emptyText = "No words")
            val outcome = h.documents.exportTranscript("a", request, Uri.fromFile(dest))
            assertEquals(ExportOutcome.Saved(text.toByteArray().size.toLong()), outcome)
            assertArrayEquals(format.name, text.toByteArray(Charsets.UTF_8), dest.readBytes())
        }
        assertFalse("cacheDir/exports is cleaned", h.paths.exports.exists())
    }

    @Test fun aPlainNoteExportsItsAudioButNoTranscript() = runTest {
        h.plainNote("a", audioFile = "source.mp3")
        val out = folder.newFolder("out")
        val copy = File(out, "copy.mp3")

        assertTrue(h.documents.exportCopy("a", Uri.fromFile(copy)) is ExportOutcome.Saved)
        assertArrayEquals(fixtureOpus.readBytes(), copy.readBytes())
        val request = TranscriptExport(TranscriptFormat.TXT, emptyMap())
        val dest = Uri.fromFile(File(out, "t.txt"))
        assertEquals(ExportOutcome.NotAvailable, h.documents.exportTranscript("a", request, dest))
    }

    // Sweep.

    /** Writes [file] (and its directory) with an mtime of [at]. */
    private fun file(file: File, at: Long): File {
        file.parentFile!!.mkdirs()
        if (!file.exists()) file.writeText("x")
        file.setLastModified(at)
        return file
    }

    @Test fun theSweepKeepsAudioAndRemovesStaleTmpFiles() = runTest {
        h.plainNote("a")
        val old = h.now - 2 * 60 * 60 * 1000L
        val capture = file(File(h.dir("a"), "capture.opus"), old)
        val staleTmp = file(File(h.dir("a"), NoteFiles.DOCUMENT_TMP), old)
        val freshTmp = file(File(h.dir("a"), "source.tmp"), h.now)
        val sealed = file(File(work("a"), "sealed.opus"), old)
        val workTmp = file(File(work("a"), "names.json.tmp"), old)
        val orphanTmp = file(File(h.paths.note("orphan"), "source.tmp"), old)
        orphanTmp.parentFile!!.setLastModified(old)
        val orphanAudio = file(File(h.paths.note("lost"), "capture.m4a"), old)
        orphanAudio.parentFile!!.setLastModified(old)
        file(File(h.paths.exports, "left.txt"), old)

        val report = h.documents.sweep()

        assertTrue(capture.isFile)
        assertFalse(staleTmp.exists())
        assertTrue(freshTmp.isFile)
        assertTrue(sealed.isFile)
        assertTrue(workTmp.isFile)
        assertFalse(h.paths.note("orphan").exists())
        assertTrue(orphanAudio.isFile)
        assertFalse(h.paths.exports.exists())
        assertEquals(3, report.deleted.size)
    }
}
