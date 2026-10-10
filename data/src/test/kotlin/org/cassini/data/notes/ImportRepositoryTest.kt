package org.cassini.data.notes

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.cassini.core.Failure
import org.cassini.core.audio.Limits
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.DocumentState
import org.cassini.core.format.OggOpus
import org.cassini.core.library.AudioKind
import org.cassini.core.library.Origin
import org.cassini.data.DocumentSignature
import org.cassini.data.db.NoteEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

/**
 * ImportRepository over file:// Uris of the FLEURS fixtures (architecture 4.2): each format is classified, unsupported
 * WAVs and non-audio are rejected, duplicates of live notes are found, and the size and space limits hold. Nothing is
 * left in notes/ after a rejected or duplicate import.
 */
@RunWith(RobolectricTestRunner::class)
class ImportRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness
    private lateinit var inputs: File
    private val probe = FakeProbe()

    @Before fun setUp() {
        h = NotesHarness(folder)
        inputs = folder.newFolder("inputs")
    }

    @After fun tearDown() = h.close()

    private val resolverSource: ImportSource
        get() = ImportSource.of(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver)

    private fun importer(source: ImportSource = resolverSource, limits: ImportLimits = ImportLimits()) =
        ImportRepository(h.db, h.paths, h.dispatchers, h.locks, h.documents, source, probe, limits) { h.now }

    /** [file] copied into the inputs folder as [name], as a file:// Uri. */
    private fun input(file: File, name: String): Uri = Uri.fromFile(file.copyTo(File(inputs, name), overwrite = true))

    private fun noteDirs(): List<String> = h.paths.notes.listFiles().orEmpty().map { it.name }.sorted()

    private fun created(outcome: ImportOutcome): String {
        assertTrue("expected Created, got $outcome", outcome is ImportOutcome.Created)
        return (outcome as ImportOutcome.Created).noteId
    }

    private suspend fun assertPlain(id: String, file: String, durationMs: Long, title: String): NoteEntity {
        val row = h.row(id)
        assertEquals(AudioKind.PLAIN, row.audioKind)
        assertEquals(Origin.IMPORTED_AUDIO, row.origin)
        assertEquals(file, row.audioFile)
        assertEquals(durationMs, row.durationMs)
        assertEquals(title, row.title)
        assertEquals(listOf(file), h.dir(id).list()!!.toList())
        return row
    }

    @Test fun aWavBecomesSourceWavWithItsHashAndDuration() = runTest {
        val uri = input(fleurs("wav"), "Riunione di lunedì.wav")
        val id = created(importer().import(uri))

        val row = assertPlain(id, "source.wav", FLEURS_WAV_MS, "Riunione di lunedì")
        assertEquals(sha256(fleurs("wav")), row.sourceSha256)
        assertEquals(h.now, row.createdAt)
        assertEquals(sha256(fleurs("wav")), sha256(File(h.dir(id), "source.wav")))
        assertEquals("", h.db.notes().text(id)!!.body)
    }

    @Test fun aPlainOggOpusBecomesSourceOpusTitledByItsDisplayName() = runTest {
        val id = created(importer().import(input(fleurs("opus"), "intervista.opus")))

        assertPlain(id, "source.opus", OggOpus.scan(fleurs("opus")).durationMs, "intervista")
        assertTrue(h.row(id).durationMs > 15_000)
        assertEquals(0, probe.calls.get())
    }

    @Test fun m4aAndMp3GoThroughTheProbe() = runTest {
        val m4a = created(importer().import(input(fleurs("m4a"), "memo.m4a")))
        val mp3 = created(importer().import(input(fleurs("mp3"), "podcast.MP3")))

        assertPlain(m4a, "source.m4a", FLEURS_WAV_MS, "memo")
        assertPlain(mp3, "source.mp3", FLEURS_WAV_MS, "podcast")
        assertEquals(2, probe.calls.get())
    }

    @Test fun aMislabelledFileGetsTheExtensionOfWhatItIs() = runTest {
        // An MP3 named .opus must never become source.opus, which the pipeline reads as Ogg Opus.
        val id = created(importer().import(input(fleurs("mp3"), "song.opus")))
        assertPlain(id, "source.mp3", FLEURS_WAV_MS, "song")
    }

    @Test fun aCassiniDocumentIsVerifiedOnceAndOpensFromTheCache() = runTest {
        val words = transcript("buongiorno a tutti oggi parliamo della consegna di giovedì")
        val file = File(inputs, "export.opus").apply { writeBytes(documentBytes(words, "Seduta del consiglio")) }

        val id = created(importer().import(Uri.fromFile(file)))

        val row = h.row(id)
        assertEquals(AudioKind.DOCUMENT, row.audioKind)
        assertEquals(Origin.IMPORTED_DOCUMENT, row.origin)
        assertEquals("document.opus", row.audioFile)
        assertEquals("Seduta del consiglio", row.title)
        assertFalse(row.titleDirty)
        assertEquals(DocumentState.OK, row.documentState)
        assertEquals(sha256(file), row.sourceSha256)
        assertEquals(DocumentSignature.of(h.document(id)), row.documentSignature)
        val meeting = h.readFile(id).manifest!!.getJSONObject("meeting")
        assertEquals(Instant.parse(meeting.getString("createdAtUtc")).toEpochMilli(), row.createdAt)
        assertEquals(meeting.getLong("durationMs"), row.durationMs)
        assertEquals(words.words.joinToString(" ") { it.text }, h.db.notes().text(id)!!.body)
        assertEquals(1, h.reader.reads.get())

        // The import recorded the verification: the first open reads nothing.
        h.restart()
        assertEquals(RefreshOutcome.Fresh, h.notes.refreshIfStale(id))
        assertEquals(1, h.reader.reads.get())
        assertEquals(words.words.size, h.notes.words(id, h.db.cache().variants(id).single().variantId).size)
    }

    @Test fun aDocumentWithBrokenMetadataIsStillADocument() = runTest {
        val words = transcript("una prova")
        val bytes = documentBytes(words, "Prova")
        val stream = OggOpus.read(bytes)
        val comments = CassiniDocument.readComments(stream.tags)
            .map { if (it.startsWith("CASSINI_PROFILE=")) "CASSINI_PROFILE=other" else it }
        val broken = OggOpus.mux(stream, CassiniDocument.tagsPacket(comments))
        val file = File(inputs, "rotto.opus").apply { writeBytes(broken) }

        val id = created(importer().import(Uri.fromFile(file)))

        val row = h.row(id)
        assertEquals(AudioKind.DOCUMENT, row.audioKind)
        assertEquals(DocumentState.INVALID_METADATA, row.documentState)
        assertEquals("rotto", row.title)
        assertTrue(row.durationMs > 15_000)
    }

    @Test fun twentyFourBitAndFloatWavsAreRejectedAsWav() = runTest {
        val pcm24 = File(inputs, "24bit.wav").also { writeWav(it, format = 1, bits = 24) }
        val float = File(inputs, "float.wav").also { writeWav(it, format = 3, bits = 32) }
        val pcm16 = File(inputs, "16bit.wav").also { writeWav(it, format = 1, bits = 16) }

        assertEquals(ImportOutcome.Rejected(Failure.WAV), importer().import(Uri.fromFile(pcm24)))
        assertEquals(ImportOutcome.Rejected(Failure.WAV), importer().import(Uri.fromFile(float)))
        assertEquals(emptyList<String>(), noteDirs())
        assertPlain(created(importer().import(Uri.fromFile(pcm16))), "source.wav", 100, "16bit")
    }

    @Test fun somethingThatIsNotAudioIsRejectedAsOpen() = runTest {
        val text = File(inputs, "notes.txt").apply { writeText("not audio at all") }
        val missing = Uri.fromFile(File(inputs, "missing.mp3"))

        assertEquals(ImportOutcome.Rejected(Failure.OPEN), importer().import(Uri.fromFile(text)))
        assertTrue(importer().import(missing).let { it is ImportOutcome.Rejected && it.failure == Failure.OPEN })
        assertEquals(emptyList<String>(), noteDirs())
        assertEquals(0, h.db.notes().noteCount())
    }

    @Test fun theSameBytesTwiceGiveTheExistingLiveNote() = runTest {
        val first = created(importer().import(input(fleurs("wav"), "a.wav")))
        val again = importer().import(input(fleurs("wav"), "another name.wav"))

        assertEquals(ImportOutcome.Existing(first), again)
        assertEquals(listOf(first), noteDirs())
        assertEquals(1, h.db.notes().noteCount())
    }

    @Test fun aTrashedNoteWithTheSameBytesIsIgnored() = runTest {
        val first = created(importer().import(input(fleurs("mp3"), "a.mp3")))
        assertTrue(h.notes.moveToTrash(first))

        val second = created(importer().import(input(fleurs("mp3"), "a.mp3")))

        assertNotEquals(first, second)
        assertEquals(listOf(first, second).sorted(), noteDirs())
        assertEquals(h.row(first).sourceSha256, h.row(second).sourceSha256)
    }

    @Test fun twoImportsOfOneFileAtOnceAddOneNote() = runTest {
        val uri = input(fleurs("opus"), "doppio.opus")
        val outcomes = withContext(Dispatchers.IO) {
            (1..4).map { async { importer().import(uri) } }.awaitAll()
        }

        assertEquals(1, outcomes.count { it is ImportOutcome.Created })
        val id = created(outcomes.first { it is ImportOutcome.Created })
        assertTrue(outcomes.all { it is ImportOutcome.Created || it == ImportOutcome.Existing(id) })
        assertEquals(listOf(id), noteDirs())
    }

    @Test fun aFileOverTwoGibibytesIsRejectedBeforeTheCopy() = runTest {
        val source = FakeSizeSource(resolverSource, Limits.MAX_IMPORT_BYTES + 1)

        val outcome = importer(source).import(input(fleurs("wav"), "huge.wav"))

        assertTrue(outcome is ImportOutcome.Rejected && outcome.failure == Failure.LARGE)
        assertEquals(emptyList<String>(), noteDirs())
    }

    @Test fun theCapHoldsDuringTheCopyWhenTheSizeIsUnknown() = runTest {
        val source = FakeSizeSource(resolverSource, null)
        val limits = ImportLimits(maxBytes = 100_000)

        val outcome = importer(source, limits).import(input(fleurs("wav"), "huge.wav"))

        assertTrue(outcome is ImportOutcome.Rejected && outcome.failure == Failure.LARGE)
        assertEquals(emptyList<String>(), noteDirs())
    }

    @Test fun notEnoughSpaceIsRejectedWithTheBytesNeeded() = runTest {
        val size = fleurs("wav").length()
        val limits = ImportLimits(usableSpace = { size + Limits.MIN_FREE_BYTES - 1 })

        val outcome = importer(limits = limits).import(input(fleurs("wav"), "a.wav"))

        assertEquals(ImportOutcome.Rejected(Failure.SPACE, neededBytes = size + Limits.MIN_FREE_BYTES), outcome)
        assertEquals(emptyList<String>(), noteDirs())
        val enough = ImportLimits(usableSpace = { size + Limits.MIN_FREE_BYTES })
        created(importer(limits = enough).import(input(fleurs("wav"), "a.wav")))
    }

    @Test fun spaceRunningOutDuringTheCopyIsRejected() = runTest {
        // An unknown size skips the check before the copy; the copy checks every 8 MiB.
        val big = File(inputs, "long.mp3")
        big.outputStream().use { out ->
            out.write(fleurs("mp3").readBytes())
            out.write(ByteArray(9 shl 20))
        }
        val limits = ImportLimits(usableSpace = { Limits.MIN_FREE_BYTES - 1 })

        val outcome = importer(FakeSizeSource(resolverSource, null), limits).import(Uri.fromFile(big))

        assertTrue("got $outcome", outcome is ImportOutcome.Rejected && outcome.failure == Failure.SPACE)
        assertEquals(emptyList<String>(), noteDirs())
    }

    @Test fun spaceRunningOutDuringTheCopyNeedsTheWholeFile() = runTest {
        val big = File(inputs, "long.mp3")
        big.outputStream().use { out ->
            out.write(fleurs("mp3").readBytes())
            out.write(ByteArray(9 shl 20))
        }
        // Enough space before the copy; somebody else uses it while the copy runs.
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val limits = ImportLimits(
            usableSpace = { if (reads.getAndIncrement() == 0) Long.MAX_VALUE else Limits.MIN_FREE_BYTES - 1 },
        )

        val outcome = importer(FakeSizeSource(resolverSource, big.length()), limits).import(Uri.fromFile(big))

        // The copied part is deleted, so a retry needs the whole file plus the margin.
        assertEquals(ImportOutcome.Rejected(Failure.SPACE, neededBytes = big.length() + Limits.MIN_FREE_BYTES), outcome)
        assertEquals(emptyList<String>(), noteDirs())
    }

    @Test fun aContentUriWithoutADisplayNameGetsTheFallbackTitle() = runTest {
        val unnamed = object : ImportSource {
            override fun describe(uri: Uri) = ImportSource.Description(null, null)

            override fun open(uri: Uri) = fleurs("opus").inputStream()
        }
        val uri = Uri.parse("content://com.android.providers.downloads.documents/document/msf%3A24")

        val id = created(importer(unnamed).import(uri))

        assertEquals(ImportRepository.FALLBACK_TITLE, h.row(id).title)
    }

    @Test fun aCancelledImportLeavesNothingBehind() = runTest {
        lateinit var job: Job
        // The cancel arrives while the file is classified, so the import stops just after the rename to source.mp3.
        val cancelling = AudioProbe { file ->
            job.cancel()
            probe.probe(file)
        }
        val importer = ImportRepository(
            h.db,
            h.paths,
            h.dispatchers,
            h.locks,
            h.documents,
            resolverSource,
            cancelling,
        ) { h.now }
        val uri = input(fleurs("mp3"), "podcast.mp3")
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { importer.import(uri) }

        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(emptyList<String>(), noteDirs())
        assertEquals(0, h.db.notes().noteCount())
    }

    @Test fun titlesAndExtensionsFollowTheDisplayName() {
        assertEquals("Meeting.final", ImportRepository.titleOf("Meeting.final.MP3"))
        assertEquals(".opus", ImportRepository.titleOf(".opus"))
        assertEquals("riunione", ImportRepository.titleOf("  riunione  "))
        assertEquals(ImportRepository.FALLBACK_TITLE, ImportRepository.titleOf(null))
        assertEquals(ImportRepository.FALLBACK_TITLE, ImportRepository.titleOf("   "))
        assertEquals("Audio importato", ImportRepository.titleOf(null, "Audio importato"))
        assertEquals(200, ImportRepository.titleOf("x".repeat(300) + ".mp3").length)
        assertEquals("mp3", ImportRepository.extensionOf("Meeting.final.MP3", "audio/mpeg"))
        assertEquals("m4a", ImportRepository.extensionOf("voice", "audio/mp4a-latm"))
        assertEquals("mp3", ImportRepository.extensionOf("a.opus", "audio/mpeg"))
        assertEquals("mp3", ImportRepository.extensionOf("a.tmp", "audio/mpeg"))
        assertEquals("bin", ImportRepository.extensionOf("a.toolong", "audio/x-unknown"))
        assertEquals("bin", ImportRepository.extensionOf(null, null))
    }

    @Test fun aTitleTagInPlainOpusDoesNotNameTheNote() = runTest {
        // One source decides: the display name. The first seal writes the note title into the TITLE tag.
        val stream = OggOpus.read(fleurs("opus").readBytes())
        val tagged = OggOpus.mux(stream, CassiniDocument.tagsPacket(listOf("TITLE=Altro titolo")))
        val file = File(inputs, "registrazione.opus").apply { writeBytes(tagged) }

        val id = created(importer().import(Uri.fromFile(file)))

        assertPlain(id, "source.opus", OggOpus.scan(file).durationMs, "registrazione")
    }
}
