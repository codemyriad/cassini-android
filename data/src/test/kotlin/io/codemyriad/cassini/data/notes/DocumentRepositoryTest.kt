package io.codemyriad.cassini.data.notes

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.core.export.ExportTranscript
import io.codemyriad.cassini.core.export.MarkdownCopy
import io.codemyriad.cassini.core.export.MarkdownOptions
import io.codemyriad.cassini.core.export.SrtWriter
import io.codemyriad.cassini.core.export.TextExport
import io.codemyriad.cassini.core.export.VttWriter
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.format.OggOpus
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.JobKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.core.library.VariantKind
import io.codemyriad.cassini.data.DocumentSignature
import io.codemyriad.cassini.data.db.JobEntity
import io.codemyriad.cassini.data.db.offMain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** DocumentRepository and the adopt path of docs/architecture.md, sections 3.1, 3.2 and 4.3. */
@RunWith(RobolectricTestRunner::class)
class DocumentRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness

    private val first = transcript("good morning everyone today we discuss the delivery on Thursday")
    private val second = transcript("good morning everyone today we discuss the delivery on Friday", turn = 4)

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

    @Test fun recordingTimeSurvivesSealExportAndReimport() = runTest {
        val start = java.time.Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()
        h.now = start
        h.plainNote("recorded")
        h.now = start + 540_000
        seal("recorded", File(h.dir("recorded"), "capture.opus"), first, "Recorded meeting")
        h.notes.adoptSealed("recorded")
        assertEquals(start, h.row("recorded").recordingTime()?.instant?.toEpochMilli())
        val exported = folder.newFile("export.opus")
        assertTrue(h.documents.exportCopy("recorded", Uri.fromFile(exported)) is ExportOutcome.Saved)
        h.documentNote("imported", first)
        exported.copyTo(h.document("imported"), overwrite = true)
        h.notes.refreshIfStale("imported")
        assertEquals(start, h.row("imported").recordingTime()?.instant?.toEpochMilli())
        assertEquals(start, h.row("imported").createdAt)
        val meeting = h.readFile("imported").manifest!!.getJSONObject("meeting")
        assertEquals("2026-11-11T12:09:00Z", meeting.getString("createdAtUtc"))
        h.restart()
        val imported = h.notes.library().first().single { it.id == "imported" }
        assertEquals(start, imported.recordingTime()?.instant?.toEpochMilli())
    }

    @Test fun persistedOffsetBearingLocalLabelIsNeverConvertedIntoAnInstant() = runTest {
        h.documentNote("label", first)
        val document = h.readFile("label")
        val label = "2026-11-09T10:31:48+02:00"
        document.manifest!!.getJSONObject("meeting").put("recordedAtLocal", label)
        h.document("label").writeBytes(
            CassiniDocument.create(h.document("label").readBytes(), first, "Ignored", JSONObject(), document),
        )
        h.notes.refreshIfStale("label")
        h.restart()
        val time = h.notes.library().first().single().recordingTime()!!
        assertEquals(label, time.label)
        assertNull(time.instant)
    }

    @Test fun packagingOnlyImportHasNoDisplayedRecordingTime() = runTest {
        h.documentNote("unknown", first)
        h.notes.refreshIfStale("unknown")
        assertNull(h.row("unknown").recordingTime())
        assertNull(h.notes.library().first().single().recordingTime())
        assertTrue("packaging time remains an ordering fallback", h.row("unknown").createdAt > 0)
    }

    @Test fun ownLegacyDocumentKeepsCaptureClockAndExportsItWithoutChangingTheSource() = runTest {
        val start = java.time.Instant.parse("2026-11-11T12:00:00Z")
        h.now = start.toEpochMilli()
        h.plainNote("own")
        h.now += 540_000
        val legacy = CassiniDocument.create(
            fixtureOpus.readBytes(),
            first,
            "Own meeting",
            JSONObject(),
            createdAtUtc = java.time.Instant.ofEpochMilli(h.now),
        )
        work("own").mkdirs()
        File(work("own"), "sealed.opus").writeBytes(legacy)
        File(work("own"), "sealed").createNewFile()
        assertEquals(AdoptOutcome.Adopted(DocumentState.OK), h.notes.adoptSealed("own"))
        assertEquals(start.toEpochMilli(), h.row("own").captureStartedAt)
        h.db.notes().update(h.row("own").copy(documentSignature = null))
        h.restart()
        h.notes.refreshIfStale("own")
        assertEquals(start, h.row("own").recordingTime()?.instant)
        val before = h.document("own").readBytes()
        val old = h.readFile("own")
        insertJob("own", JobState.PAUSED)
        val exported = folder.newFile("own-export.opus")
        assertTrue(h.documents.exportCopy("own", Uri.fromFile(exported)) is ExportOutcome.Saved)
        val written = CassiniDocument.read(exported)
        assertEquals(start, written.meetingTime?.instant)
        assertEquals(start.toString(), written.manifest!!.getJSONObject("meeting").getString("recordedAtUtc"))
        assertEquals(
            old.manifest!!.getJSONObject("meeting").getString("id"),
            written.manifest!!.getJSONObject("meeting").getString("id"),
        )
        assertEquals(old.variants.map { it.body }, written.variants.map { it.body })
        assertEquals(OggOpus.read(before).digest(), OggOpus.scan(exported).digest())
        assertTrue(written.comments.contains("DATE=$start"))
        assertTrue(written.comments.contains("CASSINI_RECORDED_AT_UTC=$start"))
        assertArrayEquals(before, h.document("own").readBytes())
        assertEquals(JobState.PAUSED, h.db.jobs().job("own")?.state)
        assertTrue(h.paths.exports.listFiles().isNullOrEmpty())
        h.documentNote("copy", first)
        exported.copyTo(h.document("copy"), overwrite = true)
        h.notes.refreshIfStale("copy")
        assertEquals(start, h.row("copy").recordingTime()?.instant)
    }

    @Test fun staleExportPreservesTheOriginalBytesAndTimeClaimsWithTheWarning() = runTest {
        h.documentNote("stale", first)
        val original = h.readFile("stale")
        original.manifest!!.getJSONObject("meeting").put("recordedAtLocal", "2026-11-11T13:00:00")
        val labelled = CassiniDocument.create(h.document("stale").readBytes(), first, "Ignored", JSONObject(), original)
        val stream = OggOpus.read(labelled)
        val packets = stream.audio.toMutableList()
        packets[0] = packets[0].clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val stale = OggOpus.mux(stream.copy(audio = packets), stream.tags)
        h.document("stale").writeBytes(stale)
        h.notes.refreshIfStale("stale")
        assertEquals(DocumentState.STALE_AUDIO, h.row("stale").documentState)
        val dest = folder.newFile("stale-export.opus")
        assertTrue(h.documents.exportCopy("stale", Uri.fromFile(dest)) is ExportOutcome.Saved)
        val exported = CassiniDocument.read(dest)
        assertEquals(DocumentState.STALE_AUDIO, exported.documentState)
        assertEquals("2026-11-11T13:00:00", exported.meetingTime?.label)
        assertArrayEquals(stale, dest.readBytes())
        assertEquals(OggOpus.read(stale).digest(), OggOpus.scan(dest).digest())
        assertArrayEquals(stale, h.document("stale").readBytes())
        // A malformed final granule cannot be hashed; Save a copy still preserves the source for recovery.
        val damaged = OggOpus.mux(stream.copy(audio = packets, finalGranule = 0), stream.tags)
        h.document("stale").writeBytes(damaged)
        h.notes.refreshIfStale("stale")
        assertEquals(DocumentState.STALE_AUDIO, h.row("stale").documentState)
        val recovery = folder.newFile("damaged-copy.opus")
        assertTrue(h.documents.exportCopy("stale", Uri.fromFile(recovery)) is ExportOutcome.Saved)
        assertArrayEquals(damaged, recovery.readBytes())
    }

    @Test fun staleReplacementAdoptAndExportCannotResurrectTheDiscardedCaptureClock() = runTest {
        for (sameId in listOf(false, true)) {
            val id = if (sameId) "same-id" else "new-id"
            h.plainNote(id)
            seal(id, File(h.dir(id), "capture.opus"), first, "Original recording")
            h.notes.adoptSealed(id)
            val original = h.readFile(id)
            assertTrue(h.row(id).captureAudioSha256 != null)
            val stream = OggOpus.read(h.document(id).readBytes())
            val packets = stream.audio.toMutableList()
            packets[0] = packets[0].clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            h.document(id).writeBytes(OggOpus.mux(stream.copy(audio = packets), stream.tags))
            // Deliberately do not open/refresh: adoption must catch changed audio even with the old OK cache.
            assertEquals(DocumentState.OK, h.row(id).documentState)
            h.now += 60_000
            val cleaned = folder.newFile("fresh-$id.opus")
            cleaned.writeBytes(OggOpus.mux(OggOpus.read(h.document(id).readBytes()), CassiniDocument.tagsPacket(emptyList())))
            val fresh = seal(id, cleaned, second, "Replacement audio")
            if (sameId) {
                // A matching meeting ID alone is not proof of the same compressed audio.
                val meetingId = original.manifest!!.getJSONObject("meeting").getString("id")
                fresh.manifest!!.getJSONObject("meeting").put("id", meetingId)
                val target = File(work(id), "same-id.opus")
                val rewritten = offMain {
                    CassiniDocument.create(
                        File(work(id), "sealed.opus"),
                        second,
                        "Ignored",
                        JSONObject(),
                        fresh,
                        emptyMap(),
                        target,
                    )
                }
                Files.move(target.toPath(), File(work(id), "sealed.opus").toPath(), StandardCopyOption.REPLACE_EXISTING)
                offMain { h.documents.markSealedBlocking(id, rewritten) }
            }
            h.notes.adoptSealed(id)
            val row = h.row(id)
            assertNull(row.captureStartedAt)
            assertNull(row.captureAudioSha256)
            assertNull(row.recordingTime())
            val destination = folder.newFile("replacement-$id.opus")
            assertTrue(h.documents.exportCopy(id, Uri.fromFile(destination)) is ExportOutcome.Saved)
            val exported = CassiniDocument.read(destination)
            assertFalse(exported.manifest!!.getJSONObject("meeting").has("recordedAtUtc"))
            assertNull(exported.meetingTime)
        }
    }

    @Test fun mergingAnOwnLegacyDocumentWritesItsPreservedCaptureClock() = runTest {
        val start = java.time.Instant.parse("2026-11-11T12:00:00Z")
        h.now = start.toEpochMilli()
        h.plainNote("legacy")
        h.now += 540_000
        val bytes = CassiniDocument.create(
            fixtureOpus.readBytes(), first, "Legacy recording", JSONObject(),
            createdAtUtc = java.time.Instant.ofEpochMilli(h.now),
        )
        work("legacy").mkdirs()
        File(work("legacy"), "sealed.opus").writeBytes(bytes)
        File(work("legacy"), "sealed").createNewFile()
        h.notes.adoptSealed("legacy")
        val before = h.readFile("legacy")
        assertFalse(before.manifest!!.getJSONObject("meeting").has("recordedAtUtc"))
        assertTrue(h.documents.mergeSpeakers("legacy", "words", "S2", "S1") is EditOutcome.Done)
        val merged = h.readFile("legacy")
        assertEquals(start.toString(), merged.manifest!!.getJSONObject("meeting").getString("recordedAtUtc"))
        assertEquals(before.variants.first().body, merged.variants.first().body)
        assertTrue(merged.comments.contains("CASSINI_RECORDED_AT_UTC=$start"))
    }

    // Adopt.

    @Test fun adoptSealedReplacesTheCaptureAndDeletesIt() = runTest {
        h.plainNote("a", title = "Morning recording")
        val capture = File(h.dir("a"), "capture.opus")
        seal("a", capture, first, "Morning recording")
        assertTrue(File(work("a"), "sealed").isFile)
        assertTrue(File(work("a"), "sealed.opus").isFile)
        assertFalse("seal never touches document.opus", h.document("a").exists())

        assertEquals(AdoptOutcome.Adopted(DocumentState.OK), h.notes.adoptSealed("a"))

        assertFalse(capture.exists())
        assertTrue("the marker stays until the voices are adopted", File(work("a"), "sealed").isFile)
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

        h.documents.deleteAdoptedWork("a")
        assertFalse(work("a").exists())
        assertEquals(AdoptOutcome.NothingToAdopt, h.notes.adoptSealed("a"))
    }

    @Test fun theFirstAdoptWritesTheNoteTitleIntoTheTitleTag() = runTest {
        // A plain Ogg Opus import keeps its own TITLE tag in source.opus; the note is named by the display name.
        h.plainNote("a", title = "registrazione", audioFile = "source.opus")
        val source = File(h.dir("a"), "source.opus")
        val stream = OggOpus.read(source.readBytes())
        source.writeBytes(OggOpus.mux(stream, CassiniDocument.tagsPacket(listOf("TITLE=Altro titolo"))))
        seal("a", source, first, "registrazione")

        assertEquals(AdoptOutcome.Adopted(DocumentState.OK), h.notes.adoptSealed("a"))

        val written = h.readFile("a")
        assertEquals("registrazione", written.title)
        val titles = written.comments.filter { it.startsWith("TITLE=", ignoreCase = true) }
        assertEquals(listOf("TITLE=registrazione"), titles)
        assertTrue(h.isOk("a"))
        assertFalse(h.row("a").titleDirty)
        assertEquals(DocumentSignature.of(h.document("a")), h.row("a").documentSignature)
    }

    @Test fun adoptRemovesTheJobRowBeforeWorkGoes() = runTest {
        h.plainNote("a")
        insertJob("a", JobState.RUNNING)
        seal("a", File(h.dir("a"), "capture.opus"), first, "Recording")

        assertEquals(AdoptOutcome.Adopted(DocumentState.OK), h.notes.adoptSealed("a"))

        // A crash here leaves a marker without a job: the next start adopts again and never queues the note.
        assertNull(h.db.jobs().job("a"))
        assertTrue(File(work("a"), "sealed").isFile)
        assertEquals(AdoptOutcome.Adopted(DocumentState.OK), h.notes.adoptSealed("a"))
        assertNull(h.db.jobs().job("a"))
    }

    @Test fun deleteAdoptedWorkLeavesTheWorkOfANewerJob() = runTest {
        h.plainNote("a")
        File(work("a").apply { mkdirs() }, "checkpoint.json").writeText("{}")

        h.documents.deleteAdoptedWork("a")

        assertTrue(File(work("a"), "checkpoint.json").isFile)
    }

    @Test fun aCrashAfterTheRenameIsRepairedByRunningAdoptAgain() = runTest {
        h.plainNote("a")
        seal("a", File(h.dir("a"), "capture.opus"), first, "Recording")
        Files.move(File(work("a"), "sealed.opus").toPath(), h.document("a").toPath(), StandardCopyOption.ATOMIC_MOVE)
        assertEquals(AudioKind.PLAIN, h.row("a").audioKind)

        h.restart()
        assertEquals(AdoptOutcome.Adopted(DocumentState.OK), h.notes.adoptSealed("a"))

        assertEquals(AudioKind.DOCUMENT, h.row("a").audioKind)
        assertEquals(DocumentSignature.of(h.document("a")), h.row("a").documentSignature)
        assertFalse(File(h.dir("a"), "capture.opus").exists())
        h.documents.deleteAdoptedWork("a")
        assertFalse(work("a").exists())
        assertEquals(AdoptOutcome.NothingToAdopt, h.notes.adoptSealed("a"))
    }

    @Test fun aCrashAfterTheRenameIsRepairedByRefreshIfStale() = runTest {
        h.plainNote("a")
        seal("a", File(h.dir("a"), "capture.opus"), first, "Recording")
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
        h.documents.deleteAdoptedWork("a")
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
        seal("a", File(h.dir("a"), "capture.opus"), first, "Recording")
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
        h.documentNote("a", first, title = "Meeting")
        h.notes.refreshIfStale("a")
        insertJob("a", JobState.RUNNING)

        assertEquals(RenameOutcome.Renamed, h.notes.rename("a", "  Monday meeting  "))
        h.awaitBackground()

        assertEquals("Monday meeting", h.row("a").title)
        assertTrue(h.row("a").titleDirty)
        assertEquals("the document is the job's source", "Meeting", h.readFile("a").title)
        assertEquals(EditOutcome.JobPending, h.documents.retitle("a"))

        val title = offMain { h.notes.titleBlocking("a") }!!
        seal("a", h.document("a"), second, title, existing = h.documents.read("a"))
        assertEquals(AdoptOutcome.Adopted(DocumentState.OK), h.notes.adoptSealed("a"))

        val written = h.readFile("a")
        assertEquals("Monday meeting", written.title)
        assertTrue(written.comments.contains("TITLE=Monday meeting"))
        assertEquals(2, written.variants.size)
        assertFalse(h.row("a").titleDirty)
        assertEquals(DocumentSignature.of(h.document("a")), h.row("a").documentSignature)
    }

    @Test fun aTitleThatNoBackgroundWriteWroteIsWrittenBeforeACopyAndAtStart() = runTest {
        h.documentNote("a", first, title = "Meeting")
        h.documentNote("b", first, title = "Phone call")
        h.documentNote("c", first, title = "Intervista")
        listOf("a", "b", "c").forEach { h.notes.refreshIfStale(it) }
        // A job row stops the background write; then the job goes without an adopt (removed from the queue).
        listOf("a", "b", "c").forEach { insertJob(it, JobState.QUEUED) }
        h.notes.rename("a", "Monday meeting")
        h.notes.rename("b", "Phone call with Marco")
        h.notes.rename("c", "Intervista a Bea")
        h.awaitBackground()
        h.db.jobs().delete("a")
        h.db.jobs().delete("b")
        assertTrue(h.row("a").titleDirty)

        val copy = File(folder.newFolder("out"), "copy.opus")
        assertTrue(h.documents.exportCopy("a", Uri.fromFile(copy)) is ExportOutcome.Saved)

        assertEquals("Monday meeting", CassiniDocument.read(copy).title)
        assertArrayEquals(h.document("a").readBytes(), copy.readBytes())
        assertFalse(h.row("a").titleDirty)

        // At the next start: "b" is written; "c" still has a job, whose adopt writes the title.
        h.restart()
        assertEquals(listOf("b"), h.documents.retitleDirty())
        assertEquals("Phone call with Marco", h.readFile("b").title)
        assertFalse(h.row("b").titleDirty)
        assertEquals("Intervista", h.readFile("c").title)
        assertTrue(h.row("c").titleDirty)
        assertEquals(emptyList<String>(), h.documents.retitleDirty())
    }

    @Test fun aRenameOfAnUnverifiedDocumentStaysInRoomAndSurvivesRefreshIfStale() = runTest {
        h.documentNote("a", first, title = "Meeting")
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
        h.documentNote("a", first, title = "Delivery: Thursday?")
        h.notes.refreshIfStale("a")
        val labels = mapOf("S1" to "Giulia", "S2" to "Speaker 2")
        val expected = ExportTranscript("Delivery: Thursday?", h.row("a").durationMs, first.words, labels)
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

    @Test fun markdownExportsPrivateNotesWithoutChangingThePortableFile() = runTest {
        h.documentNote("a", first, title = "Review")
        val privateData = JSONObject(
            """
            {
                "gocassini.com":{"notes":[{"atMs":5000,"text":"Keep *this*"},{"atMs":1000,"text":"Earlier"}],"unknown":"kept"},
                "example.org":{"secret":"not exported"}
            }
            """.trimIndent(),
        )
        CassiniDocument.create(
            fixtureOpus,
            first,
            "Review",
            JSONObject().put("engine", "test"),
            null,
            mapOf("S1" to "Ada", "S2" to "Ben"),
            h.document("a"),
            extensions = privateData,
        )
        val original = h.document("a").readBytes()
        val copy = MarkdownCopy("Speakers: {names}", "Transcript", "Notes", "(over {names})") {
            it.joinToString(" and ")
        }
        val request = TranscriptExport(
            TranscriptFormat.MARKDOWN,
            mapOf("S1" to "Ada", "S2" to "Ben"),
            markdown = MarkdownOptions("Thursday 8 October 2026, 15:10", copy),
        )
        val dest = folder.newFile("review.md")
        assertTrue(h.documents.exportTranscript("a", request, Uri.fromFile(dest)) is ExportOutcome.Saved)
        val exported = dest.readText()
        assertTrue(exported.startsWith("# Review\n\nThursday 8 October 2026, 15:10\n"))
        assertTrue(exported.contains("**Ada**"))
        assertTrue(exported.endsWith("## Notes\n\n- 00:00:01 Earlier\n- 00:00:05 Keep \\*this\\*\n"))
        assertFalse(exported.contains("secret"))
        assertFalse(exported.contains("not exported"))
        assertArrayEquals("export never mutates private metadata or audio", original, h.document("a").readBytes())
        assertFalse(h.paths.exports.exists())
        assertEquals(
            ExportOutcome.NotAvailable,
            h.documents.exportTranscript("a", request.copy(markdown = null), Uri.fromFile(dest)),
        )
    }

    @Test fun transcriptStagingNeverUsesTheLongMultibyteTitleAsAFilename() = runTest {
        val title = "会".repeat(100)
        h.documentNote("a", first, title = title)
        val original = h.document("a").readBytes()
        val copy = MarkdownCopy("Speakers: {names}", "Transcript", "Notes", "(over {names})") {
            it.joinToString(" and ")
        }
        // Save and Share both use exportTranscript; the destination name is owned by the provider/caller.
        for (format in TranscriptFormat.entries) {
            val request = TranscriptExport(
                format,
                mapOf("S1" to "Ada", "S2" to "Ben"),
                markdown = MarkdownOptions("Thursday 8 October 2026, 15:10", copy),
            )
            val dest = folder.newFile("long-title.${format.extension}")
            val uri = Uri.parse("content://export-test/${format.extension}")
            val context = ApplicationProvider.getApplicationContext<Context>()
            var stagedNames = emptyList<String>()
            shadowOf(context.contentResolver).registerOutputStreamSupplier(uri) {
                stagedNames = h.paths.exports.walkTopDown().filter { it.isFile }.map { it.name }.toList()
                dest.outputStream()
            }
            val outcome = h.documents.exportTranscript("a", request, uri)
            assertTrue("$format: $outcome", outcome is ExportOutcome.Saved)
            // APFS can accept names that Android refuses: assert the real byte limit, not just host I/O success.
            val stagedName = stagedNames.single()
            assertTrue(stagedName, stagedName.toByteArray(Charsets.UTF_8).size <= 255)
            assertTrue(dest.length() > 0)
            if (format == TranscriptFormat.MARKDOWN) assertTrue(dest.readText().startsWith("# $title\n"))
            assertFalse("$format staging is cleaned", h.paths.exports.exists())
        }
        assertArrayEquals(original, h.document("a").readBytes())
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
