package org.cassini.data.notes

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.cassini.core.asr.IncrementalState
import org.cassini.core.checkpoint.CheckpointState
import org.cassini.core.checkpoint.RecognitionState
import org.cassini.core.format.DocumentState
import org.cassini.core.format.OggRepair
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.library.Origin
import org.cassini.core.library.PauseReason
import org.cassini.core.speakers.Match
import org.cassini.core.speakers.NoteVoiceCodec
import org.cassini.core.speakers.SpeakerPrint
import org.cassini.data.checkpoint.CheckpointRepository
import org.cassini.data.db.CassiniDatabase
import org.cassini.data.db.CassiniDatabaseOpener
import org.cassini.data.db.offMain
import org.cassini.data.jobs.JobRepository
import org.cassini.data.jobs.RunningJobCanceller
import org.cassini.data.voices.VoiceRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.time.ZoneOffset
import java.util.UUID

/**
 * CatalogueRepository.rebuild (architecture 4.9) over synthetic note directories with no rows: documents, captures,
 * imports, a paused checkpoint, a pending adopt, voice files. The rebuild is idempotent and never deletes audio.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogueRebuildTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness
    private val probe = FakeProbe()
    private val recordedAt = Instant.parse("2026-10-09T10:00:00Z").toEpochMilli()

    private val documentId = id(1)
    private val captureId = id(2)
    private val importId = id(3)
    private val pausedId = id(4)
    private val sealedId = id(5)
    private val badSealId = id(6)
    private val tmpOnlyId = id(7)
    private val goneId = id(8)

    private fun id(n: Int) = UUID(0x1234L, n.toLong()).toString().replaceRange(14, 15, "4")

    @Before fun setUp() {
        h = NotesHarness(folder)
    }

    @After fun tearDown() = h.close()

    private fun catalogue(db: CassiniDatabase = h.db, documents: DocumentRepository = h.documents) =
        CatalogueRepository(
            db,
            h.paths,
            h.dispatchers,
            h.locks,
            documents,
            VoiceRepository(
                db,
                h.paths,
                h.dispatchers,
                CheckpointRepository(db, h.paths, h.dispatchers, h.locks).names,
            ),
            probe,
            recoveredTitle = RecoveredTitle.english(ZoneOffset.UTC),
        ) { h.now }

    private fun file(id: String, name: String) = File(h.dir(id), name).apply { parentFile!!.mkdirs() }

    private val meetingWords = transcript("buongiorno a tutti oggi parliamo della consegna di giovedì")

    private fun checkpoint(source: String) = CheckpointState(
        source = source,
        signature = "91602:1",
        model = "parakeet-revision",
        speakers = "diarizer-sha",
        recognition = RecognitionState(
            inferenceMs = 1_200,
            chunks = IncrementalState(0, 28_000, FloatArray(4), transcript("uno due tre").words),
        ),
        encoder = null,
        diarizer = null,
        speakerPrefix = "S",
        speakerFailure = false,
        clipSamples = 0,
        voiceClips = null,
        positionSamples = 448_000,
        doneMs = 28_000,
        durationMs = 60_000,
        elapsedMs = 9_000,
    )

    private fun prints(vararg entries: Pair<String, Match?>): String = NoteVoiceCodec.encode(
        "voiceprint-sha",
        entries.associate { (key, match) -> key to SpeakerPrint(FloatArray(4) { 0.5f }, 12.5, match) },
    )

    /** The note directories and voice files of a catalogue that was lost. */
    private fun writeTree() {
        file(documentId, "document.opus").writeBytes(documentBytes(meetingWords, "Seduta del consiglio"))

        file(captureId, "capture.opus").also { truncatedCopy(fleurs("opus"), it, 0.6) }.setLastModified(recordedAt)

        fleurs("mp3").copyTo(file(importId, "source.mp3"))

        fleurs("opus").copyTo(file(pausedId, "capture.opus"))
        file(pausedId, "work/checkpoint.json").writeText(CheckpointState.encode(checkpoint("capture.opus")).toString())

        fleurs("opus").copyTo(file(sealedId, "capture.opus"))
        file(sealedId, "work/sealed.opus").writeBytes(documentBytes(transcript("la seduta è aperta"), "Verbale"))
        file(sealedId, "work/sealed").createNewFile()

        fleurs("opus").copyTo(file(badSealId, "capture.opus"))
        file(badSealId, "work/sealed.opus").writeText("not a document")
        file(badSealId, "work/sealed").createNewFile()
        file(badSealId, "work/checkpoint.json").writeText(CheckpointState.encode(checkpoint("capture.opus")).toString())

        file(tmpOnlyId, "source.tmp").writeText("half an import")
        fleurs("opus").copyTo(File(h.paths.notes, "not-a-note/capture.opus"))

        val voiceNotes = File(h.paths.voices, "notes").apply { mkdirs() }
        File(voiceNotes, "$documentId.json").writeText(
            prints(
                "words/S1" to Match("voice-anna", 0.82f, Match.State.CONFIRMED),
                "words/S2" to null,
            ),
        )
        File(voiceNotes, "$goneId.json").writeText(prints("words/S1" to Match("voice-anna", 0.7f, Match.State.AUTO)))
        File(voiceNotes, "$importId.json").writeText("{ broken")
    }

    /** Every file under notes/ with its size. */
    private fun files(): Map<String, Long> = h.paths.notes.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(h.paths.notes).path to it.length() }

    @Test fun rebuildRecreatesRowsJobsAndLinks() = runTest {
        writeTree()

        val report = catalogue().rebuild()

        assertEquals(listOf(documentId, captureId, importId, pausedId, sealedId, badSealId), report.created)
        assertEquals(listOf(sealedId), report.adopted)
        assertEquals(listOf(pausedId, badSealId), report.paused)

        val document = h.row(documentId)
        assertEquals(AudioKind.DOCUMENT, document.audioKind)
        assertEquals(Origin.RECORDED, document.origin) // The vendor is cassini-android.
        assertEquals("Seduta del consiglio", document.title)
        assertFalse(document.titleDirty)
        assertEquals(DocumentState.OK, document.documentState)
        val meeting = h.readFile(documentId).manifest!!.getJSONObject("meeting")
        assertEquals(Instant.parse(meeting.getString("createdAtUtc")).toEpochMilli(), document.createdAt)
        assertEquals(meetingWords.words.size, h.db.cache().cachedWordCount(documentId, "words"))
        assertEquals(RefreshOutcome.Fresh, h.notes.refreshIfStale(documentId))

        val capture = h.row(captureId)
        assertEquals(AudioKind.PLAIN, capture.audioKind)
        assertEquals(Origin.RECOVERED, capture.origin)
        assertEquals("Recovered recording 9 Oct", capture.title)
        assertTrue(capture.durationMs > 1_000)
        assertEquals(recordedAt - capture.durationMs, capture.createdAt)
        assertTrue(OggRepair.endsCleanly(File(h.dir(captureId), "capture.opus")))
        // The repair keeps the file's time, so a later rebuild gives the same recording time.
        assertEquals(recordedAt, File(h.dir(captureId), "capture.opus").lastModified())

        val import = h.row(importId)
        assertEquals(AudioKind.PLAIN, import.audioKind)
        assertEquals("source.mp3", import.audioFile)
        assertEquals(FLEURS_WAV_MS, import.durationMs)
        assertEquals(sha256(fleurs("mp3")), import.sourceSha256)
    }

    @Test fun rebuildTurnsWorkIntoJobsAndFinishesAdopts() = runTest {
        writeTree()

        catalogue().rebuild()

        val paused = h.db.jobs().job(pausedId)!!
        assertEquals(JobState.PAUSED, paused.state)
        assertEquals(PauseReason.INTERRUPTED, paused.pauseReason)
        assertEquals(JobKind.TRANSCRIBE, paused.kind)
        assertEquals(28_000, paused.doneMs)
        assertEquals(60_000, paused.totalMs)
        assertEquals(3, paused.words)
        assertTrue(File(h.paths.work(pausedId), "checkpoint.json").isFile)

        // The pending adopt was finished: the sealed document is the note's audio and work/ is gone.
        val sealed = h.row(sealedId)
        assertEquals(AudioKind.DOCUMENT, sealed.audioKind)
        assertEquals("Verbale", sealed.title)
        assertFalse(sealed.titleDirty)
        assertEquals(DocumentState.OK, sealed.documentState)
        assertEquals(listOf("document.opus"), h.dir(sealedId).list()!!.toList())
        assertNull(h.db.jobs().job(sealedId))

        // An unreadable sealed output is dropped with its marker; the checkpoint gives a paused job.
        assertEquals(AudioKind.PLAIN, h.row(badSealId).audioKind)
        assertFalse(File(h.paths.work(badSealId), "sealed").exists())
        assertFalse(File(h.paths.work(badSealId), "sealed.opus").exists())
        assertEquals(JobState.PAUSED, h.db.jobs().job(badSealId)!!.state)
        assertTrue(File(h.dir(badSealId), "capture.opus").isFile)
    }

    @Test fun rebuildSkipsDirectoriesWithoutAudioAndSyncsVoiceLinks() = runTest {
        writeTree()

        val report = catalogue().rebuild()

        assertEquals(listOf(goneId), report.deletedVoiceFiles)
        assertEquals(2, report.links)
        assertNull(h.db.notes().note(tmpOnlyId))
        assertTrue(File(h.dir(tmpOnlyId), "source.tmp").isFile)
        assertTrue(File(h.paths.notes, "not-a-note/capture.opus").isFile)

        val links = h.db.voiceLinks().all()
        assertEquals(listOf("S1" to "voice-anna", "S2" to null), links.map { it.speakerId to it.voiceId })
        assertTrue(links.all { it.noteId == documentId && it.variantId == "words" })
        assertEquals(Match.State.CONFIRMED, links.first().state)
        assertEquals(Match.State.NONE, links.last().state)
        assertFalse(File(h.paths.voices, "notes/$goneId.json").exists())
        // A damaged file is set aside, never deleted.
        assertFalse(File(h.paths.voices, "notes/$importId.json").exists())
        assertEquals(
            1,
            File(h.paths.voices, "notes").list()!!.count { it.startsWith("$importId.json.corrupt-") },
        )
    }

    @Test fun rebuildIsIdempotent() = runTest {
        writeTree()
        catalogue().rebuild()
        val rows = h.db.notes().all().sortedBy { it.id }
        val jobs = h.db.jobs().all()
        val links = h.db.voiceLinks().all()
        val variants = rows.associate { it.id to h.db.cache().variants(it.id) }
        val before = files()
        val reads = h.reader.reads.get()

        val report = catalogue().rebuild()

        assertEquals(emptyList<String>(), report.created + report.adopted + report.paused + report.deletedVoiceFiles)
        assertEquals(rows, h.db.notes().all().sortedBy { it.id })
        assertEquals(jobs, h.db.jobs().all())
        assertEquals(links, h.db.voiceLinks().all())
        assertEquals(variants, rows.associate { it.id to h.db.cache().variants(it.id) })
        assertEquals(before, files())
        assertEquals(reads, h.reader.reads.get())
    }

    @Test fun rebuildNeverDeletesAudio() = runTest {
        writeTree()
        val audio = files().keys.filter { NoteFiles.isPreviousAudio(File(it).name) || it.endsWith("document.opus") }

        catalogue().rebuild()

        val after = files().keys
        // The pending adopt replaced its capture with the sealed document, as adopt does; every other file stays.
        val adoptedCapture = File(sealedId, "capture.opus").path
        assertTrue(after.contains(File(sealedId, "document.opus").path))
        assertEquals(audio.filter { it != adoptedCapture }.toSet(), audio.filter { it in after }.toSet())
    }

    @Test fun aDocumentFromAnotherProducerIsAnImportedDocument() = runTest {
        val bytes = documentBytes(meetingWords, "Esterno")
        val stream = org.cassini.core.format.OggOpus.read(bytes)
        val comments = org.cassini.core.format.CassiniDocument.readComments(stream.tags)
        // The vendor string precedes the comments in OpusTags; build one with another vendor.
        val vendor = "gocassini/1.0".toByteArray()
        val tags = java.io.ByteArrayOutputStream().apply {
            write("OpusTags".toByteArray())
            write(le32(vendor.size))
            write(vendor)
            write(le32(comments.size))
            comments.forEach { comment ->
                val bytes = comment.toByteArray()
                write(le32(bytes.size))
                write(bytes)
            }
        }.toByteArray()
        file(documentId, "document.opus").writeBytes(org.cassini.core.format.OggOpus.mux(stream, tags))

        catalogue().rebuild()

        assertEquals(Origin.IMPORTED_DOCUMENT, h.row(documentId).origin)
        assertEquals("Esterno", h.row(documentId).title)
        assertEquals(DocumentState.OK, h.row(documentId).documentState)
    }

    private fun le32(value: Int) = ByteArray(4) { (value shr (8 * it)).toByte() }

    @Test fun rebuildRunsWhenTheCatalogueIsEmptyButNotesExist() = runTest {
        val repository = catalogue()
        assertNull(repository.rebuildIfNeeded(setAside = null)) // Nothing on disk.

        writeTree()
        val report = repository.rebuildIfNeeded(setAside = null)

        assertNotNull(report)
        assertEquals(6, report!!.created.size)
        assertNull(repository.rebuildIfNeeded(setAside = null)) // Rows exist now.
    }

    @Test fun aRebuildThatStoppedPartwayIsFinishedByTheNextStart() = runTest {
        // The process died after the rebuild added the first note: the catalogue is not empty any more.
        file(documentId, "document.opus").writeBytes(documentBytes(meetingWords, "Seduta del consiglio"))
        catalogue().rebuild()
        writeTree()

        val report = catalogue().rebuildIfNeeded(setAside = null)

        assertEquals(listOf(captureId, importId, pausedId, sealedId, badSealId), report?.created)
        assertEquals(JobState.PAUSED, h.db.jobs().job(pausedId)?.state)
        assertNull(catalogue().rebuildIfNeeded(setAside = null))
    }

    @Test fun aDirectoryThatFailsKeepsItsFilesAndIsCataloguedAtTheNextStart() = runTest {
        writeTree()
        val voiceFile = File(h.paths.voices, "notes/$importId.json")
        voiceFile.writeText(prints("words/S1" to Match("voice-anna", 0.9f, Match.State.CONFIRMED)))
        val checkpointFile = file(importId, "work/checkpoint.json")
        checkpointFile.writeText(CheckpointState.encode(checkpoint("source.mp3")).toString())
        probe.error = java.io.IOException("I/O error") // Reading the import fails once.

        val first = catalogue().rebuild()

        assertEquals(listOf(importId), first.failed)
        assertEquals(listOf(documentId, captureId, pausedId, sealedId, badSealId), first.created)
        assertNull(h.db.notes().note(importId))
        // The later start steps keep what the note without a row has.
        val checkpoints = CheckpointRepository(h.db, h.paths, h.dispatchers, h.locks)
        val voices = VoiceRepository(h.db, h.paths, h.dispatchers, checkpoints.names)
        val jobs = JobRepository(
            h.db,
            h.paths,
            h.dispatchers,
            h.locks,
            checkpoints,
            SealedAdopter.of(h.documents, voices),
            RunningJobCanceller {},
        ) { h.now }
        assertTrue(jobs.reconcileOnStart(null).deletedWork.isEmpty())
        assertTrue(voices.syncLinks().deleted.isEmpty())
        assertTrue(voiceFile.isFile)
        assertTrue(checkpointFile.isFile)

        probe.error = null
        val next = catalogue().rebuildIfNeeded(setAside = null)

        assertEquals(listOf(importId), next?.created)
        assertEquals(listOf(importId), next?.paused)
        assertEquals("source.mp3", h.row(importId).audioFile)
        assertEquals(JobState.PAUSED, h.db.jobs().job(importId)?.state)
        assertEquals(listOf("S1" to "voice-anna"), h.db.voiceLinks().links(importId).map { it.speakerId to it.voiceId })
    }

    @Test fun aDocumentWithAShortTagsPacketIsCatalogued() = runTest {
        val stream = org.cassini.core.format.OggOpus.read(documentBytes(meetingWords, "Seduta"))
        val document = file(documentId, "document.opus")
        // "OpusTags" and two bytes: too short for the vendor length.
        document.writeBytes(org.cassini.core.format.OggOpus.mux(stream, "OpusTags".toByteArray() + byteArrayOf(1, 0)))
        assertNull(AudioFiles.vendor(document))

        val report = catalogue().rebuild()

        assertEquals(listOf(documentId), report.created)
        assertTrue(report.failed.isEmpty())
        assertEquals(Origin.IMPORTED_DOCUMENT, h.row(documentId).origin)
    }

    @Test fun aCorruptDatabaseIsSetAsideAndRebuilt() = runTest {
        writeTree()
        catalogue().rebuild()
        h.notes.rename(captureId, "Telefonata con Marco") // Room-only state that a lost database loses.
        h.db.close()
        val database = h.paths.database
        listOf("-wal", "-shm", "-journal").forEach { File(database.path + it).delete() }
        database.writeText("this is not a database")

        val opened = offMain {
            CassiniDatabaseOpener(ApplicationProvider.getApplicationContext(), h.paths) { 4_242 }.open()
        }
        try {
            assertNotNull(opened.setAside)
            val documents = DocumentRepository(
                opened.database,
                h.paths,
                h.dispatchers,
                h.locks,
                ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver,
                h.reader,
            ) { h.now }

            val report = catalogue(opened.database, documents).rebuildIfNeeded(opened.setAside)

            assertNotNull(report)
            assertEquals(listOf(documentId, captureId, importId, pausedId, sealedId, badSealId), report!!.created)
            val rows = opened.database.notes().all().associateBy { it.id }
            assertEquals("Seduta del consiglio", rows.getValue(documentId).title)
            assertEquals("Recovered recording 9 Oct", rows.getValue(captureId).title)
            assertEquals("Verbale", rows.getValue(sealedId).title)
            assertEquals(AudioKind.DOCUMENT, rows.getValue(sealedId).audioKind)
            assertEquals(JobState.PAUSED, opened.database.jobs().job(pausedId)!!.state)
            assertEquals(2, opened.database.voiceLinks().all().size)
        } finally {
            opened.database.close()
        }
    }

    /** Makes each insert into `jobs` fail, as a storage error or a process death between the two writes would. */
    private fun failJobInserts(fail: Boolean) = offMain {
        val sql = if (fail) {
            "CREATE TRIGGER fail_job_insert BEFORE INSERT ON jobs BEGIN SELECT RAISE(ABORT, 'injected'); END"
        } else {
            "DROP TRIGGER fail_job_insert"
        }
        h.db.openHelper.writableDatabase.execSQL(sql)
    }

    @Test fun aFailedJobWriteLeavesNoRowAndTheNextStartRecoversThePausedJob() = runTest {
        fleurs("opus").copyTo(file(pausedId, "capture.opus"))
        val plainCheckpoint = file(pausedId, "work/checkpoint.json")
        plainCheckpoint.writeText(CheckpointState.encode(checkpoint("capture.opus")).toString())
        file(documentId, "document.opus").writeBytes(documentBytes(meetingWords, "Seduta del consiglio"))
        val documentCheckpoint = file(documentId, "work/checkpoint.json")
        documentCheckpoint.writeText(CheckpointState.encode(checkpoint("document.opus")).toString())
        failJobInserts(true)

        val first = catalogue().rebuild()

        assertEquals(listOf(documentId, pausedId), first.failed)
        assertTrue(first.created.isEmpty())
        assertNull("the row goes only with its job", h.db.notes().note(pausedId))
        assertNull(h.db.notes().note(documentId))

        // The next start: the rebuild, then the reconcile of the queue.
        failJobInserts(false)
        val next = catalogue().rebuildIfNeeded(setAside = null)
        assertEquals(listOf(documentId, pausedId), next?.paused)
        val plainJob = h.db.jobs().job(pausedId)
        assertEquals(JobState.PAUSED, plainJob?.state)
        assertEquals(JobKind.TRANSCRIBE, plainJob?.kind)
        val documentJob = h.db.jobs().job(documentId)
        assertEquals(JobState.PAUSED, documentJob?.state)
        assertEquals(JobKind.TRANSCRIBE_AGAIN, documentJob?.kind)
        val checkpoints = CheckpointRepository(h.db, h.paths, h.dispatchers, h.locks)
        val jobs = JobRepository(
            h.db,
            h.paths,
            h.dispatchers,
            h.locks,
            checkpoints,
            SealedAdopter.of(h.documents, VoiceRepository(h.db, h.paths, h.dispatchers, checkpoints.names)),
            RunningJobCanceller {},
        ) { h.now }

        assertTrue(jobs.reconcileOnStart(null).deletedWork.isEmpty())
        assertTrue(plainCheckpoint.isFile && documentCheckpoint.isFile)
        assertNotNull(h.db.jobs().job(pausedId))
        assertNotNull(h.db.jobs().job(documentId))
        assertEquals(AudioKind.DOCUMENT, h.row(documentId).audioKind)
    }
}
