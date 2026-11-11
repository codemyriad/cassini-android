package io.codemyriad.cassini.documents

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.CassiniApplication
import io.codemyriad.cassini.GraphFactory
import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.audio.Limits
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.format.OggOpus
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.library.JobKind
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.jobs.ClaimOutcome
import io.codemyriad.cassini.data.models.ModelFetcher
import io.codemyriad.cassini.data.notes.ImportLimits
import io.codemyriad.cassini.data.notes.ImportOutcome
import io.codemyriad.cassini.nav.DeepLinks
import io.codemyriad.cassini.testing.NoUncaughtCoroutines
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Tests for [CassiniDocumentsProvider] (D97):
 * - manifest protection (`MANAGE_DOCUMENTS`, URI grants, `DOCUMENTS_PROVIDER`, no self-URI deep-link loop);
 * - read-only exposure of sealed `.opus` documents and plain-audio notes, dirty-title flush, collision numbering;
 * - exclusion of trashed notes, active recordings and notes currently being transcribed or sealed;
 * - inbound `createDocument` + `"w"`/`"wt"` pipe stream write through `ImportRepository` with SHA-256 deduplication;
 * - rejection of `"rw"`/`"rwt"`/`"wa"` modes without consuming the write-once `pending:<uuid>` grant;
 * - byte isolation and note-lifecycle binding (trash, restore, permanent purge across restarts) on creation grants;
 * - startup reconciliation of interrupted `IMPORTING` grants without querying or reopening the creation URI;
 * - streaming enforcement of `ImportLimits.maxBytes` and `ImportLimits.minFreeBytes` before writer close;
 * - persistent stable `pending:<uuid>` identities across provider recreation and cache eviction thresholds;
 * - native `detachFd()` lifecycle safety;
 * - refusal of write/delete/rename/move, main-thread calls and path traversal;
 * - linear thread-CPU scaling on duplicate titles and large cached word tables (D87, D88).
 */
@Suppress("TooManyFunctions", "LargeClass") // one test per D97 contract and D87/review bug class
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [FifoPipeParcelFileDescriptorShadow::class])
class CassiniDocumentsProviderTest {
    @get:Rule(order = Int.MIN_VALUE)
    val uncaught = NoUncaughtCoroutines()

    @get:Rule
    val folder = TemporaryFolder()

    private val app: CassiniApplication = ApplicationProvider.getApplicationContext()
    private val resolver: ContentResolver get() = app.contentResolver
    private val authority: String get() = "${app.packageName}${CassiniDocumentsProvider.AUTHORITY_SUFFIX}"
    private val nowMs = AtomicLong(1_760_000_000_000L)
    private lateinit var paths: AppPaths
    private lateinit var currentGraph: AppGraph
    private lateinit var observerThread: HandlerThread

    @Before
    fun setUp() {
        CassiniDocumentsProvider.limitsForTesting = null
        PendingImports.writerClosedProbeForTesting = FifoPipeParcelFileDescriptorShadow::isWriterClosed
        FifoPipeParcelFileDescriptorShadow.resetDetached()
        observerThread = HandlerThread("test-content-observer").apply { start() }
        paths = AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder())
        currentGraph = buildGraph()
        CassiniDocumentsProvider.graphOverrideForTesting = ::currentGraph
        CassiniApplication.graphFactory = GraphFactory { _, _ -> currentGraph }
        recreateProvider()
        runBlocking { withTimeout(EVENT_BOUND_MS) { currentGraph.ready.await() } }
    }

    @After
    fun tearDown() {
        CassiniDocumentsProvider.limitsForTesting = null
        CassiniDocumentsProvider.graphOverrideForTesting = null
        PendingImports.writerClosedProbeForTesting = null
        FifoPipeParcelFileDescriptorShadow.resetDetached()
        observerThread.quitSafely()
        currentGraph.close { shadowOf(Looper.getMainLooper()).idle() }
        CassiniApplication.graphFactory = null
    }

    @Test
    fun manifestDeclaresProtectedDocumentsProviderAndBlocksConfusedDeputyDeepLinks() {
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PROVIDERS)
        val provider = packageInfo.providers.orEmpty().single { it.name == CassiniDocumentsProvider::class.java.name }
        assertEquals(authority, provider.authority)
        assertTrue(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertEquals(Manifest.permission.MANAGE_DOCUMENTS, provider.readPermission)
        assertEquals(Manifest.permission.MANAGE_DOCUMENTS, provider.writePermission)

        val intent = Intent(DocumentsContract.PROVIDER_INTERFACE).setPackage(app.packageName)
        val resolved = app.packageManager.queryIntentContentProviders(intent, 0).map { it.providerInfo.name }
        assertEquals(listOf(CassiniDocumentsProvider::class.java.name), resolved)

        val ownDocUri = DocumentsContract.buildDocumentUri(authority, "note:00000000-0000-4000-8000-000000000001")
        assertNull(
            "MainActivity must never treat Cassini's own DocumentsProvider URI as an external deep-link import",
            DeepLinks.external(ownDocUri.toString(), ownPackage = app.packageName),
        )
    }

    @Test
    fun queryRootsRunsWithoutMainThreadIoAndExposesCreateRoot() {
        val rootsUri = DocumentsContract.buildRootsUri(authority)
        resolver.query(rootsUri, null, Bundle.EMPTY, null).use { cursor ->
            requireNotNull(cursor)
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(DocumentsVault.ROOT_ID, cursor.string(Root.COLUMN_ROOT_ID))
            assertEquals("Cassini talk", cursor.string(Root.COLUMN_TITLE))
            assertEquals(DocumentsVault.ROOT_DOCUMENT_ID, cursor.string(Root.COLUMN_DOCUMENT_ID))
            val flags = cursor.int(Root.COLUMN_FLAGS)
            assertTrue((flags and Root.FLAG_LOCAL_ONLY) != 0)
            assertTrue((flags and Root.FLAG_SUPPORTS_CREATE) != 0)
            assertTrue((flags and Root.FLAG_SUPPORTS_IS_CHILD) != 0)
            assertTrue(cursor.string(Root.COLUMN_MIME_TYPES).contains("audio/*"))
        }

        val rootDocUri = DocumentsContract.buildDocumentUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)
        resolver.query(rootDocUri, null, Bundle.EMPTY, null).use { cursor ->
            requireNotNull(cursor)
            assertTrue(cursor.moveToFirst())
            assertEquals(Document.MIME_TYPE_DIR, cursor.string(Document.COLUMN_MIME_TYPE))
            assertEquals(Document.FLAG_DIR_SUPPORTS_CREATE, cursor.int(Document.COLUMN_FLAGS))
        }
    }

    @Test
    fun listsSealedOpusAndPlainAudioNotesReadOnlyAndFlushesDirtyTitles(): Unit = runBlocking {
        val catalogue = currentGraph.awaitCatalogue()
        nowMs.set(1_760_000_010_000L)
        val wavId = importPlainWav("Morning standup", frames = 3_200)
        nowMs.set(1_760_000_020_000L)
        val opusId = createSealedNote("Architecture review", listOf("Good", "morning", "team"))

        // Simulate a rename whose background retitle has not run yet (title_dirty = 1 on disk).
        catalogue.database.notes().rename(opusId, "Architecture review final", titleDirty = true, now = nowMs.get())

        val listed = withContext(Dispatchers.IO) { queryChildren() }
        assertEquals(listOf("note:$opusId", "note:$wavId"), listed.map { it.documentId })
        val opusRow = listed[0]
        val wavRow = listed[1]
        assertEquals("Architecture review final.opus", opusRow.displayName)
        assertEquals("audio/ogg", opusRow.mimeType)
        assertEquals(0, opusRow.flags)
        assertEquals("Morning standup.wav", wavRow.displayName)
        assertEquals("audio/wav", wavRow.mimeType)
        assertEquals(0, wavRow.flags)

        val opusBytes = withContext(Dispatchers.IO) { readDocumentBytes(opusRow.documentId) }
        assertEquals(
            "COLUMN_SIZE must match streamed bytes after dirty-title flush",
            opusRow.size,
            opusBytes.size.toLong(),
        )
        val parsed = withContext(Dispatchers.IO) {
            val checkFile = folder.newFile("exported.opus").also { it.writeBytes(opusBytes) }
            CassiniDocument.readChecked(checkFile, verify = true)
        }
        assertEquals(DocumentState.OK, parsed.documentState)
        assertEquals("Architecture review final", parsed.title)
    }

    @Test
    fun disambiguatesCollidingTitlesDeterministicallyAcrossListingAndQueryDocument(): Unit = runBlocking {
        nowMs.set(1_760_000_100_000L)
        val first = createSealedNote("Weekly sync", listOf("First", "note"))
        nowMs.set(1_760_000_200_000L)
        val second = createSealedNote("Weekly sync", listOf("Second", "note"))
        nowMs.set(1_760_000_300_000L)
        val third = createSealedNote("Weekly sync", listOf("Third", "note"))

        val children = withContext(Dispatchers.IO) { queryChildren() }
        assertEquals(
            listOf("Weekly sync (3).opus", "Weekly sync (2).opus", "Weekly sync.opus"),
            children.map { it.displayName },
        )
        for (expected in listOf(
            "note:$first" to "Weekly sync.opus",
            "note:$second" to "Weekly sync (2).opus",
            "note:$third" to "Weekly sync (3).opus",
        )) {
            val single = withContext(Dispatchers.IO) { querySingle(expected.first) }
            assertEquals(expected.second, single.displayName)
        }
    }

    @Test
    fun hidesLiveRecordingsTrashedNotesAndNotesBeingSealedUntilAdopted(): Unit = runBlocking {
        val catalogue = currentGraph.awaitCatalogue()
        val liveId = catalogue.notes.createRecording("Ongoing capture", nowMs.incrementAndGet(), "opus")
        writeSyntheticOpus(File(paths.note(liveId), "capture.opus"), packetCount = 5, seed = 1)

        val sealingId = createPlainOpusNote("Design walkthrough", seed = 2)
        catalogue.jobs.enqueue(sealingId, JobKind.TRANSCRIBE)
        assertTrue(catalogue.jobs.claimNext() is ClaimOutcome.Claimed)

        // Neither the active capture nor the running transcription job may appear in the Files listing.
        assertEquals(emptyList<String>(), withContext(Dispatchers.IO) { queryChildren().map { it.documentId } })
        withContext(Dispatchers.IO) {
            assertThrows(FileNotFoundException::class.java) { readDocumentBytes("note:$liveId") }
            assertThrows(FileNotFoundException::class.java) { readDocumentBytes("note:$sealingId") }
        }

        // Write work/sealed.opus + marker: still hidden until adopt finishes installing document.opus.
        withContext(Dispatchers.IO) {
            catalogue.documents.sealBlocking(
                noteId = sealingId,
                audio = File(paths.note(sealingId), "capture.opus"),
                transcript = Transcript(listOf(Word("S1", 0, 400, "Ready"), Word("S1", 450, 900, "now"))),
                title = "Design walkthrough",
                processing = JSONObject().put("engine", "test"),
                existing = null,
                labels = mapOf("S1" to "Alex"),
            )
        }
        assertEquals(emptyList<String>(), withContext(Dispatchers.IO) { queryChildren().map { it.documentId } })

        val changed = watchNextChildrenChange()
        catalogue.adopter.adopt(sealingId)
        withTimeout(EVENT_BOUND_MS) { changed.await() }

        assertEquals(listOf("note:$sealingId"), withContext(Dispatchers.IO) { queryChildren().map { it.documentId } })

        // Trashing hides the note immediately and notifies observers.
        val trashedGate = watchNextChildrenChange()
        assertTrue(catalogue.notes.moveToTrash(sealingId))
        withTimeout(EVENT_BOUND_MS) { trashedGate.await() }
        assertEquals(emptyList<String>(), withContext(Dispatchers.IO) { queryChildren().map { it.documentId } })
        withContext(Dispatchers.IO) {
            assertThrows(FileNotFoundException::class.java) { readDocumentBytes("note:$sealingId") }
        }
    }

    /**
     * Review #169 r1 finding 1 (blocker): a creation grant whose bytes match an existing note deduplicates internally
     * in Cassini's library while exposing ONLY the caller's submitted bytes — never an existing note's edited title,
     * transcript, or speaker names.
     */
    @Test
    fun duplicateCreationGrantExposesOnlySubmittedBytesNotEditedPrivateNote(): Unit = runBlocking {
        val catalogue = currentGraph.awaitCatalogue()
        val submittedBytes = buildCustomerInterviewOpusBytes()
        val rootUri = DocumentsContract.buildDocumentUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)

        // First import through createDocument.
        val firstChange = watchNextChildrenChange()
        val firstUri = writeNewChildDocument(rootUri, "audio/ogg", "Submitted copy.opus", submittedBytes)
        withTimeout(EVENT_BOUND_MS) { firstChange.await() }
        awaitNoRunningImports()

        val noteId = withContext(Dispatchers.IO) {
            queryChildren().single().documentId.removePrefix(DocumentsVault.NOTE_PREFIX)
        }

        // Now the user privately edits the imported note inside Cassini: new title, private transcript and speaker name.
        editNoteWithPrivateMetadata(catalogue, noteId)

        // The Files picker listing (MANAGE_DOCUMENTS) reflects the user's edited note, still only 1 note in library.
        val listedBeforeDup = withContext(Dispatchers.IO) { queryChildren() }
        assertEquals(1, listedBeforeDup.size)
        assertEquals("Private HR review.opus", listedBeforeDup.single().displayName)

        // External caller submits the original unedited bytes again via createDocument (triggers ImportOutcome.Existing).
        val dupUri = writeNewChildDocument(rootUri, "audio/ogg", "Duplicate upload.opus", submittedBytes)
        awaitNoRunningImports()

        // Internal deduplication holds: still 1 note in Cassini's library.
        assertEquals(1, withContext(Dispatchers.IO) { queryChildren().size })

        // Neither the duplicate grant nor the original creation grant may leak the edited note's title or bytes.
        assertGrantExposesOnlySubmittedBytes(dupUri, "Duplicate upload.opus", submittedBytes)
        assertGrantExposesOnlySubmittedBytes(firstUri, "Submitted copy.opus", submittedBytes)
    }

    /**
     * Review #169 r2 finding 1 (major): creation grants follow the lifecycle of their imported note across restarts —
     * hidden while trashed, restored when untrashed (preserving strict byte isolation), and permanently deleted from
     * `provider-grants/` when the note is purged (`deleteNow` / `emptyTrash` / `purgeExpired`) without requiring any
     * subsequent access to the creation URI.
     */
    @Test
    fun creationGrantsFollowNoteTrashRestoreAndPermanentPurgeAcrossRestart(): Unit = runBlocking {
        val submittedBytes = buildCustomerInterviewOpusBytes()
        val rootUri = DocumentsContract.buildDocumentUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)
        val grantsDir = File(paths.root, PendingImports.GRANTS_DIR)

        val firstUri = writeNewChildDocument(rootUri, "audio/ogg", "Submitted copy.opus", submittedBytes)
        awaitNoRunningImports()
        val noteId = withContext(Dispatchers.IO) {
            queryChildren().single().documentId.removePrefix(DocumentsVault.NOTE_PREFIX)
        }
        editNoteWithPrivateMetadata(currentGraph.awaitCatalogue(), noteId)
        val dupUri = writeNewChildDocument(rootUri, "audio/ogg", "Duplicate upload.opus", submittedBytes)
        awaitNoRunningImports()

        // 1. Move to Trash: both grants hide immediately, including across restart, while payloads remain for Restore.
        assertTrue(currentGraph.awaitCatalogue().notes.moveToTrash(noteId))
        assertGrantUnavailable(rootUri, firstUri)
        assertGrantUnavailable(rootUri, dupUri)
        assertEquals(2, grantsDir.listFiles().orEmpty().size)

        restartAppAndProvider(awaitReady = true)
        assertGrantUnavailable(rootUri, firstUri)
        assertGrantUnavailable(rootUri, dupUri)
        assertEquals(2, grantsDir.listFiles().orEmpty().size)

        // 2. Restore from Trash: both grants become readable again across restart and still serve only submitted bytes.
        assertTrue(currentGraph.awaitCatalogue().notes.restore(noteId))
        assertGrantExposesOnlySubmittedBytes(firstUri, "Submitted copy.opus", submittedBytes)
        assertGrantExposesOnlySubmittedBytes(dupUri, "Duplicate upload.opus", submittedBytes)

        restartAppAndProvider(awaitReady = true)
        assertGrantExposesOnlySubmittedBytes(firstUri, "Submitted copy.opus", submittedBytes)
        assertGrantExposesOnlySubmittedBytes(dupUri, "Duplicate upload.opus", submittedBytes)

        // 3. Permanent purge (without touching either creation URI): payloads in provider-grants/ are deleted at once.
        assertTrue(currentGraph.awaitCatalogue().notes.moveToTrash(noteId))
        assertTrue(currentGraph.awaitCatalogue().notes.deleteNow(noteId))
        assertEquals(
            "Permanent note deletion must reclaim provider-grants/ immediately",
            emptyList<String>(),
            grantsDir.listFiles().orEmpty().map { it.name },
        )
        assertGrantUnavailable(rootUri, firstUri)
        assertGrantUnavailable(rootUri, dupUri)

        restartAppAndProvider(awaitReady = true)
        assertEquals(emptyList<String>(), grantsDir.listFiles().orEmpty().map { it.name })
        assertGrantUnavailable(rootUri, firstUri)
        assertGrantUnavailable(rootUri, dupUri)
    }

    /**
     * Review #169 r2 finding 2 (major): unsupported read-write and append modes (`"rw"`, `"rwt"`, `"wa"`) are rejected
     * immediately before consuming the write-once `pending:<uuid>` grant, so a subsequent `"w"` or `"wt"` open works.
     */
    @Test
    fun rejectsReadWriteAndAppendModesWithoutConsumingWriteOnceGrant(): Unit = runBlocking {
        val rootUri = DocumentsContract.buildDocumentUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)
        val wavFile = folder.newFile("valid-stream.wav").also { writePcm16Wav(it, frames = 1_800) }
        val wavBytes = wavFile.readBytes()

        val uri = withContext(Dispatchers.IO) {
            val created = createChildDocument(rootUri, "audio/wav", "Exporter output.wav")
            for (unsupported in listOf("rw", "rwt", "wa")) {
                assertThrows(
                    "Mode $unsupported must be rejected before consuming grant",
                    UnsupportedOperationException::class.java,
                ) {
                    resolver.openFileDescriptor(created, unsupported)
                }
            }
            // Grant is still in AWAITING_WRITE (unconsumed, FLAG_SUPPORTS_WRITE intact).
            val beforeWrite = querySingle(DocumentsContract.getDocumentId(created))
            assertEquals(Document.FLAG_SUPPORTS_WRITE, beforeWrite.flags)
            assertEquals(0L, beforeWrite.size)

            // Subsequent "wt" write succeeds cleanly; querySingle awaits pipe EOF + ImportRepository completion.
            resolver.openOutputStream(created, "wt").use { out ->
                requireNotNull(out).write(wavBytes)
            }
            querySingle(DocumentsContract.getDocumentId(created))
            created
        }
        awaitNoRunningImports()

        withContext(Dispatchers.IO) {
            assertEquals(listOf("Exporter output.wav"), queryChildren().map { it.displayName })
            val readBack = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
            assertArrayEquals(wavBytes, readBack)

            // Once accepted, write modes throw SecurityException and rw/rwt still throw UnsupportedOperationException.
            assertThrows(SecurityException::class.java) { resolver.openFileDescriptor(uri, "w") }
            assertThrows(SecurityException::class.java) { resolver.openFileDescriptor(uri, "wt") }
            assertThrows(UnsupportedOperationException::class.java) { resolver.openFileDescriptor(uri, "rw") }
            assertThrows(UnsupportedOperationException::class.java) { resolver.openFileDescriptor(uri, "rwt") }
        }
    }

    /**
     * Review #169 r2 finding 3 (major): an import interrupted after persisting `state = IMPORTING` is reconciled at
     * `AppGraph` startup idempotently so the note appears in Cassini's library even if the sender never queries or
     * reopens the creation URI.
     */
    @Test
    fun startupReconcilesInterruptedImportingGrantWithoutQueryingCreationUri(): Unit = runBlocking {
        val wavFile = folder.newFile("interrupted-source.wav").also { writePcm16Wav(it, frames = 2_000) }
        val wavBytes = wavFile.readBytes()
        val token = UUID.randomUUID().toString()
        val deadWritingToken = UUID.randomUUID().toString()

        // Shut down the active graph to simulate process death before seeding persisted grant states on disk.
        currentGraph.close { shadowOf(Looper.getMainLooper()).idle() }

        val grantsRoot = File(paths.root, PendingImports.GRANTS_DIR)
        val importingDir = File(grantsRoot, token).apply { mkdirs() }
        File(importingDir, "Interrupted capture.wav").writeBytes(wavBytes)
        File(importingDir, "meta.json").writeText(
            JSONObject()
                .put("version", 1)
                .put("token", token)
                .put("displayName", "Interrupted capture.wav")
                .put("mimeType", "audio/wav")
                .put("createdAt", nowMs.get())
                .put("updatedAt", nowMs.get())
                .put("state", "IMPORTING")
                .put("size", wavBytes.size.toLong())
                .toString(),
        )
        val deadWritingDir = File(grantsRoot, deadWritingToken).apply { mkdirs() }
        File(deadWritingDir, "payload.tmp").writeBytes(byteArrayOf(1, 2, 3))
        File(deadWritingDir, "meta.json").writeText(
            JSONObject()
                .put("version", 1)
                .put("token", deadWritingToken)
                .put("displayName", "Dead stream.wav")
                .put("mimeType", "audio/wav")
                .put("createdAt", nowMs.get())
                .put("updatedAt", nowMs.get())
                .put("state", "WRITING")
                .put("size", 0L)
                .toString(),
        )

        // Start a brand-new AppGraph and await ready WITHOUT calling query/open/isChildDocument on any provider URI.
        currentGraph = buildGraph()
        CassiniDocumentsProvider.graphOverrideForTesting = ::currentGraph
        CassiniApplication.graphFactory = GraphFactory { _, _ -> currentGraph }
        withTimeout(EVENT_BOUND_MS) { currentGraph.ready.await() }

        val notesAfterStartup = currentGraph.awaitCatalogue().database.notes().all()
        assertEquals("Interrupted IMPORTING grant must be imported at startup", 1, notesAfterStartup.size)
        val importedNote = notesAfterStartup.single()
        assertEquals("Interrupted capture", importedNote.title)
        assertFalse("Crashed WRITING grant must be swept at startup", deadWritingDir.exists())

        val persistedMeta = JSONObject(File(importingDir, "meta.json").readText())
        assertEquals("ACCEPTED", persistedMeta.getString("state"))
        assertEquals(importedNote.id, persistedMeta.getString("noteId"))

        // Second restart verifies startup reconciliation is idempotent (still 1 note) and creation URI stays readable.
        restartAppAndProvider(awaitReady = true)
        assertEquals(1, currentGraph.awaitCatalogue().database.notes().all().size)
        val grantUri = DocumentsContract.buildDocumentUri(authority, "${PendingImports.PREFIX}$token")
        withContext(Dispatchers.IO) {
            val queried = querySingle("${PendingImports.PREFIX}$token")
            assertEquals("${PendingImports.PREFIX}$token", queried.documentId)
            assertEquals("Interrupted capture.wav", queried.displayName)
            assertArrayEquals(wavBytes, requireNotNull(resolver.openInputStream(grantUri)).use { it.readBytes() })
        }
    }

    /**
     * Review #169 r1 finding 2 (major): incoming writes enforce both the byte cap and the free-space reserve during
     * streaming, aborting and deleting staging files before the writer closes its descriptor.
     */
    @Test
    fun enforcesByteCapAndFreeSpaceReserveDuringStreamingBeforeWriterClose(): Unit = runBlocking {
        val rootUri = DocumentsContract.buildDocumentUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)
        val grantsDir = File(paths.root, PendingImports.GRANTS_DIR)

        // 1. Byte cap exceeded mid-stream while writer descriptor stays open.
        CassiniDocumentsProvider.limitsForTesting = ImportLimits(maxBytes = 4_096L, minFreeBytes = 0L)
        withContext(Dispatchers.IO) {
            val overCapUri = createChildDocument(rootUri, "audio/wav", "huge.wav")
            val pfd = requireNotNull(resolver.openFileDescriptor(overCapUri, "w"))
            val out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
            try {
                val chunk = ByteArray(4_096) { 0x5A }
                assertThrows("Must fail with EPIPE before writer close", IOException::class.java) {
                    // Kernel FIFO buffer is 64 KiB; writing past it after pump closes read end delivers EPIPE.
                    repeat(64) {
                        out.write(chunk)
                        out.flush()
                    }
                }
                // Staging directory is ALREADY cleaned up while `out` is still open!
                assertEquals(emptyList<String>(), grantsDir.listFiles().orEmpty().map { it.name })
            } finally {
                runCatching { out.close() }
            }
            assertNull(resolver.query(overCapUri, null, Bundle.EMPTY, null))
            assertThrows(FileNotFoundException::class.java) { resolver.openInputStream(overCapUri) }
        }
        assertEquals(0, paths.notes.listFiles().orEmpty().size)

        // 2. Free-space reserve breached mid-stream while writer descriptor stays open.
        val usableBytes = AtomicLong(100_000L + 2_048L)
        CassiniDocumentsProvider.limitsForTesting = ImportLimits(
            maxBytes = Limits.MAX_IMPORT_BYTES,
            minFreeBytes = 100_000L,
            usableSpace = { usableBytes.get() },
        )
        withContext(Dispatchers.IO) {
            val lowSpaceUri = createChildDocument(rootUri, "audio/wav", "lowspace.wav")
            val pfd = requireNotNull(resolver.openFileDescriptor(lowSpaceUri, "w"))
            val out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
            try {
                out.write(ByteArray(1_024) { 0x11 })
                out.flush()
                // Simulate disk filling below the free-space floor while the writer keeps streaming.
                usableBytes.set(90_000L)
                val chunk = ByteArray(4_096) { 0x22 }
                assertThrows("Must fail before writer close when reserve breached", IOException::class.java) {
                    repeat(64) {
                        out.write(chunk)
                        out.flush()
                    }
                }
                assertEquals(emptyList<String>(), grantsDir.listFiles().orEmpty().map { it.name })
            } finally {
                runCatching { out.close() }
            }
            assertNull(resolver.query(lowSpaceUri, null, Bundle.EMPTY, null))
            assertThrows(FileNotFoundException::class.java) { resolver.openInputStream(lowSpaceUri) }
        }
    }

    /**
     * Review #169 r1 finding 3 (major): created document URIs keep their exact `pending:<uuid>` ID on query and remain
     * readable across >128 creations, >1 hour clock jumps, and full provider recreation.
     */
    @Test
    fun retainsStableCreatedDocumentIdsAcrossProviderRecreationAndEvictionThresholds(): Unit = runBlocking {
        val rootUri = DocumentsContract.buildDocumentUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)
        val totalCreated = PendingImports.MAX_CACHED_COMPLETED + 12
        val payloads = ArrayList<Pair<Uri, ByteArray>>(totalCreated)

        withContext(Dispatchers.IO) {
            for (idx in 0 until totalCreated) {
                val wavFile = folder.newFile("clip-$idx.wav")
                writePcm16Wav(wavFile, frames = 400 + idx)
                val bytes = wavFile.readBytes()
                val uri = createChildDocument(rootUri, "audio/wav", "Clip $idx.wav")
                resolver.openOutputStream(uri).use { out ->
                    requireNotNull(out).write(bytes)
                }
                querySingle(DocumentsContract.getDocumentId(uri))
                payloads += uri to bytes
            }
        }
        awaitNoRunningImports()

        // An unwritten placeholder created before the clock jump must expire cleanly, while completed grants survive.
        val abandonedUnwritten = withContext(Dispatchers.IO) {
            createChildDocument(rootUri, "audio/wav", "Abandoned placeholder.wav")
        }

        // Advance past the 1-hour stale-placeholder sweep window and allocate one more unwritten URI before restart.
        nowMs.addAndGet(PendingImports.STALE_UNWRITTEN_MS * 2)
        val unwrittenAcrossRestart = withContext(Dispatchers.IO) {
            createChildDocument(rootUri, "audio/wav", "Written after restart.wav")
        }

        // Evict in-memory provider state and recreate CassiniDocumentsProvider from scratch.
        recreateProvider(evictInMemoryGrants = true)

        val restartWav = folder.newFile("after-restart.wav").also { writePcm16Wav(it, frames = 900) }.readBytes()
        withContext(Dispatchers.IO) {
            assertNull(resolver.query(abandonedUnwritten, null, Bundle.EMPTY, null))
            resolver.openOutputStream(unwrittenAcrossRestart).use { out ->
                requireNotNull(out).write(restartWav)
            }
        }
        awaitNoRunningImports()

        // Check oldest (evicted from in-memory cache), middle, newest, and post-recreation documents.
        val indicesToVerify = listOf(0, 1, totalCreated / 2, totalCreated - 1)
        withContext(Dispatchers.IO) {
            for (idx in indicesToVerify) {
                val (uri, expectedBytes) = payloads[idx]
                val expectedDocId = DocumentsContract.getDocumentId(uri)
                assertTrue(DocumentsContract.isChildDocument(resolver, rootUri, uri))
                val queried = querySingle(expectedDocId)
                assertEquals("COLUMN_DOCUMENT_ID must not mutate to note:<id>", expectedDocId, queried.documentId)
                assertEquals("Clip $idx.wav", queried.displayName)
                assertEquals(expectedBytes.size.toLong(), queried.size)
                val actualBytes = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
                assertArrayEquals(expectedBytes, actualBytes)
            }

            val restartId = DocumentsContract.getDocumentId(unwrittenAcrossRestart)
            val restartMeta = querySingle(restartId)
            assertEquals(restartId, restartMeta.documentId)
            assertArrayEquals(
                restartWav,
                requireNotNull(resolver.openInputStream(unwrittenAcrossRestart)).use { it.readBytes() },
            )
        }
    }

    /**
     * Review #169 r1 finding 4 (major): native clients that call `ParcelFileDescriptor.detachFd()` and keep writing to
     * the detached descriptor complete cleanly when the native descriptor closes instead of losing their audio.
     */
    @Test
    fun nativeDetachedFileDescriptorWritesAfterDetachFdAndImportsOnRelease(): Unit = runBlocking {
        val rootUri = DocumentsContract.buildDocumentUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)
        val wavFile = folder.newFile("native-source.wav").also { writePcm16Wav(it, frames = 2_400) }
        val wavBytes = wavFile.readBytes()

        val changed = watchNextChildrenChange()
        val nativeUri = withContext(Dispatchers.IO) {
            val docUri = createChildDocument(rootUri, "audio/wav", "Native capture.wav")
            val pfd = requireNotNull(resolver.openFileDescriptor(docUri, "w"))
            val rawFd = pfd.detachFd()
            // Wrapper is now detached; calling close() on the wrapper must be a harmless no-op.
            pfd.close()

            // While the native FD is still open, querying must report the in-progress document rather than failing.
            val inProgress = querySingle(DocumentsContract.getDocumentId(docUri))
            assertEquals(DocumentsContract.getDocumentId(docUri), inProgress.documentId)
            assertNull(inProgress.size)

            // Native writer continues writing in chunks via the detached descriptor and finally closes it.
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(rawFd)).use { nativeOut ->
                val split = wavBytes.size / 2
                nativeOut.write(wavBytes, 0, split)
                nativeOut.flush()
                nativeOut.write(wavBytes, split, wavBytes.size - split)
            }
            docUri
        }
        withTimeout(EVENT_BOUND_MS) { changed.await() }
        awaitNoRunningImports()

        val children = withContext(Dispatchers.IO) { queryChildren() }
        assertEquals(1, children.size)
        assertEquals("Native capture.wav", children.single().displayName)

        val queried = withContext(Dispatchers.IO) { querySingle(DocumentsContract.getDocumentId(nativeUri)) }
        assertEquals(DocumentsContract.getDocumentId(nativeUri), queried.documentId)
        assertEquals(wavBytes.size.toLong(), queried.size)
        val readBack = withContext(Dispatchers.IO) {
            requireNotNull(resolver.openInputStream(nativeUri)).use { it.readBytes() }
        }
        assertArrayEquals(wavBytes, readBack)
    }

    @Test
    fun rejectedAndCancelledImportsCleanUpStagingWithoutOrphanFiles(): Unit = runBlocking {
        val rootUri = DocumentsContract.buildDocumentUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)
        val grantsDir = File(paths.root, PendingImports.GRANTS_DIR)

        // Invalid non-audio bytes are rejected cleanly and leave no grant or orphan note files.
        withContext(Dispatchers.IO) {
            val badUri = createChildDocument(rootUri, "audio/ogg", "broken.opus")
            resolver.openOutputStream(badUri).use { out ->
                requireNotNull(out).write("not an audio stream".toByteArray())
            }
            assertNull(resolver.query(badUri, null, Bundle.EMPTY, null))
            assertThrows(FileNotFoundException::class.java) {
                resolver.openInputStream(badUri)
            }
        }
        val failure = withTimeout(EVENT_BOUND_MS) {
            currentGraph.notices.imports.state.first { it.running.isEmpty() && it.failure != null }.failure
        }
        assertEquals(Failure.OPEN, failure?.failure)
        assertEquals(emptyList<String>(), grantsDir.listFiles().orEmpty().map { it.name })
        assertEquals(0, paths.notes.listFiles().orEmpty().size)

        // Cancelled write discards staged bytes without importing.
        withContext(Dispatchers.IO) {
            val cancelledUri = createChildDocument(rootUri, "audio/wav", "aborted.wav")
            val signal = CancellationSignal()
            resolver.openFileDescriptor(cancelledUri, "w", signal).use { pfd ->
                requireNotNull(pfd)
                signal.cancel()
            }
            assertNull(resolver.query(cancelledUri, null, Bundle.EMPTY, null))
            assertThrows(FileNotFoundException::class.java) {
                resolver.openInputStream(cancelledUri)
            }
        }
        assertEquals(emptyList<String>(), grantsDir.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun refusesMutationsTraversalAndMainThreadBlockingCalls(): Unit = runBlocking {
        val noteId = createSealedNote("Quarterly roadmap", listOf("Ship", "it"))
        val docId = "note:$noteId"
        val docUri = DocumentsContract.buildDocumentUri(authority, docId)

        // Main-thread calls to I/O entry points fail fast instead of blocking UI.
        val rootUri = DocumentsContract.buildDocumentUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)
        assertThrows(IllegalStateException::class.java) {
            queryChildren()
        }
        assertThrows(IllegalStateException::class.java) {
            resolver.openInputStream(docUri)
        }
        assertThrows(IllegalStateException::class.java) {
            createChildDocument(rootUri, "audio/wav", "Main thread.wav")
        }

        withContext(Dispatchers.IO) {
            assertThrows(SecurityException::class.java) {
                resolver.openFileDescriptor(docUri, "w")
            }
            assertThrows(SecurityException::class.java) {
                resolver.openFileDescriptor(docUri, "rw")
            }
            assertThrows(UnsupportedOperationException::class.java) {
                DocumentsContract.deleteDocument(resolver, docUri)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                DocumentsContract.renameDocument(resolver, docUri, "Renamed.opus")
            }
            val badIds = listOf(
                "note:../cassini.db",
                "note:not-a-uuid",
                "pending:../cassini.db",
                "pending:not-a-uuid",
                "other:$noteId",
                "../secret",
            )
            for (badId in badIds) {
                val badUri = DocumentsContract.buildDocumentUri(authority, badId)
                assertThrows(FileNotFoundException::class.java) {
                    resolver.openInputStream(badUri)
                }
                assertNull(resolver.query(badUri, null, Bundle.EMPTY, null))
            }
        }
    }

    @Test
    fun scalesLinearlyWithDuplicateTitlesAndIgnoresLargeCachedWordTables(): Unit = runBlocking {
        val catalogue = currentGraph.awaitCatalogue()
        val words = (0 until 5_000).map { idx -> "word$idx" }
        val longNoteId = createSealedNote("Long board meeting", words)
        val variantId = withContext(Dispatchers.IO) {
            catalogue.database.cache().variantsBlocking(longNoteId).single().variantId
        }
        assertEquals(5_000, catalogue.database.cache().words(longNoteId, variantId).size)
        val onDiskOpus = File(paths.note(longNoteId), "document.opus")
        val docUri = DocumentsContract.buildDocumentUri(authority, "note:$longNoteId")

        withContext(Dispatchers.IO) {
            val listedBefore = queryChildren().single()
            resolver.openFileDescriptor(docUri, "r").use { pfd ->
                requireNotNull(pfd)
                assertEquals(onDiskOpus.length(), pfd.statSize)
            }
            // Even if Room's cached word rows are absent, listing and streaming read only notes/jobs + the file FD.
            catalogue.database.openHelper.writableDatabase.execSQL("DELETE FROM words")
            val listedAfter = queryChildren().single()
            assertEquals(listedBefore, listedAfter)
            assertArrayEquals(onDiskOpus.readBytes(), readDocumentBytes("note:$longNoteId"))
        }

        // Verify assignDisplayNames is strictly O(N) even when every note collides on the same title (500 vs 2_000).
        val baseRow = requireNotNull(catalogue.database.notes().note(longNoteId))
        val smallBatch = (0 until 500).map { idx ->
            val syntheticId = "%08x-0000-4000-8000-%012x".format(idx, idx)
            baseRow.copy(id = syntheticId, title = "Imported audio", createdAt = idx.toLong())
        }
        val largeBatch = (0 until 2_000).map { idx ->
            val syntheticId = "%08x-0000-4000-8000-%012x".format(idx, idx)
            baseRow.copy(id = syntheticId, title = "Imported audio", createdAt = idx.toLong())
        }
        var smallProbes = 0
        val smallNames = DocumentsVault.assignDisplayNames(smallBatch) { smallProbes++ }
        var largeProbes = 0
        val largeNames = DocumentsVault.assignDisplayNames(largeBatch) { largeProbes++ }

        assertEquals(500, smallNames.values.toSet().size)
        assertEquals(2_000, largeNames.values.toSet().size)
        // First note takes 1 probe; each subsequent colliding note fails baseKey and succeeds on nextSuffix (2 probes).
        // 500 notes = 999 probes; 2_000 notes = 3_999 probes (exact 4x O(N); naive O(N^2) would take ~2_001_000 probes).
        assertEquals(999, smallProbes)
        assertEquals(3_999, largeProbes)
    }

    // Helpers. --------------------------------------------------------------------------------------------------------

    private fun buildGraph(): AppGraph = AppGraph(
        app,
        AppGraph.Config(
            paths = paths,
            dispatchers = CassiniDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Unconfined),
            fetcher = ModelFetcher { _, _, _ -> fail("No model download expected") },
            network = { false },
            clock = nowMs::get,
        ),
    )

    private fun recreateProvider(evictInMemoryGrants: Boolean = false) {
        if (evictInMemoryGrants) {
            currentGraph.providerGrants.clearInMemoryStateForTesting()
        }
        val info = ProviderInfo().apply {
            authority = this@CassiniDocumentsProviderTest.authority
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        }
        Robolectric.buildContentProvider(CassiniDocumentsProvider::class.java).create(info)
    }

    private suspend fun restartAppAndProvider(awaitReady: Boolean) {
        currentGraph.close { shadowOf(Looper.getMainLooper()).idle() }
        currentGraph = buildGraph()
        CassiniDocumentsProvider.graphOverrideForTesting = ::currentGraph
        CassiniApplication.graphFactory = GraphFactory { _, _ -> currentGraph }
        recreateProvider()
        if (awaitReady) {
            withTimeout(EVENT_BOUND_MS) { currentGraph.ready.await() }
        }
    }

    private suspend fun awaitNoRunningImports() {
        withTimeout(EVENT_BOUND_MS) {
            currentGraph.notices.imports.state.first { it.running.isEmpty() }
        }
    }

    private suspend fun buildCustomerInterviewOpusBytes(): ByteArray = withContext(Dispatchers.IO) {
        val sourceOpus = folder.newFile("initial-${UUID.randomUUID()}.opus")
        val rawOpus = folder.newFile("raw-${UUID.randomUUID()}.opus").also {
            writeSyntheticOpus(it, packetCount = 8, seed = 42)
        }
        CassiniDocument.create(
            rawOpus,
            Transcript(listOf(Word("S1", 0, 500, "Hello"), Word("S1", 550, 950, "world"))),
            "Customer interview",
            JSONObject().put("engine", "test"),
            null,
            mapOf("S1" to "Sam"),
            sourceOpus,
        )
        sourceOpus.readBytes()
    }

    private suspend fun writeNewChildDocument(
        rootUri: Uri,
        mimeType: String,
        displayName: String,
        bytes: ByteArray,
    ): Uri = withContext(Dispatchers.IO) {
        val docUri = createChildDocument(rootUri, mimeType, displayName)
        resolver.openOutputStream(docUri).use { out ->
            requireNotNull(out).write(bytes)
        }
        querySingle(DocumentsContract.getDocumentId(docUri))
        docUri
    }

    private suspend fun editNoteWithPrivateMetadata(catalogue: io.codemyriad.cassini.Catalogue, noteId: String) {
        catalogue.jobs.enqueue(noteId, JobKind.TRANSCRIBE)
        assertTrue(catalogue.jobs.claimNext() is ClaimOutcome.Claimed)
        withContext(Dispatchers.IO) {
            val existingFile = File(paths.note(noteId), "document.opus")
            val existingDoc = CassiniDocument.read(existingFile)
            catalogue.documents.sealBlocking(
                noteId = noteId,
                audio = existingFile,
                transcript = Transcript(listOf(Word("S1", 0, 600, "Confidential"), Word("S1", 650, 1100, "budget"))),
                title = "Private HR review",
                processing = JSONObject().put("engine", "test-retranscribe"),
                existing = existingDoc,
                labels = mapOf("S1" to "Alice Secret"),
            )
        }
        catalogue.adopter.adopt(noteId)
        catalogue.notes.rename(noteId, "Private HR review")
    }

    private suspend fun assertGrantExposesOnlySubmittedBytes(
        grantUri: Uri,
        expectedDisplayName: String,
        submittedBytes: ByteArray,
    ) {
        val rootUri = DocumentsContract.buildDocumentUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)
        val grantDocId = DocumentsContract.getDocumentId(grantUri)
        val meta = withContext(Dispatchers.IO) {
            assertTrue(DocumentsContract.isChildDocument(resolver, rootUri, grantUri))
            querySingle(grantDocId)
        }
        assertEquals("Document ID must stay stable on creation grant", grantDocId, meta.documentId)
        assertEquals(expectedDisplayName, meta.displayName)
        assertEquals(submittedBytes.size.toLong(), meta.size)

        val readBack = withContext(Dispatchers.IO) {
            requireNotNull(resolver.openInputStream(grantUri)).use { it.readBytes() }
        }
        assertArrayEquals("Creation grant must expose only submitted bytes", submittedBytes, readBack)
        val parsed = withContext(Dispatchers.IO) {
            val verifyFile = folder.newFile("verify-${UUID.randomUUID()}.opus")
            verifyFile.writeBytes(readBack)
            CassiniDocument.readChecked(verifyFile, verify = true)
        }
        assertEquals("Customer interview", parsed.title)
        val rawUtf8 = String(readBack, Charsets.UTF_8)
        assertFalse(rawUtf8.contains("Private HR review"))
        assertFalse(rawUtf8.contains("Alice Secret"))
        assertFalse(rawUtf8.contains("Confidential"))
    }

    private suspend fun assertGrantUnavailable(rootUri: Uri, grantUri: Uri) = withContext(Dispatchers.IO) {
        assertFalse(DocumentsContract.isChildDocument(resolver, rootUri, grantUri))
        assertNull(resolver.query(grantUri, null, Bundle.EMPTY, null))
        assertThrows(FileNotFoundException::class.java) {
            resolver.openInputStream(grantUri)
        }
    }

    private suspend fun importPlainWav(title: String, frames: Int): String {
        val file = folder.newFile("$title.wav")
        writePcm16Wav(file, frames = frames)
        val outcome = currentGraph.awaitCatalogue().imports.import(Uri.fromFile(file))
        return (outcome as ImportOutcome.Created).noteId
    }

    private suspend fun createPlainOpusNote(title: String, seed: Int): String {
        val catalogue = currentGraph.awaitCatalogue()
        val id = catalogue.notes.createRecording(title, nowMs.get(), "opus")
        val target = File(paths.note(id), "capture.opus")
        writeSyntheticOpus(target, packetCount = 6, seed = seed)
        assertTrue(catalogue.notes.finishRecording(id, durationMs = 120L))
        return id
    }

    private suspend fun createSealedNote(title: String, words: List<String>): String {
        val catalogue = currentGraph.awaitCatalogue()
        val id = createPlainOpusNote(title, seed = title.hashCode())
        catalogue.jobs.enqueue(id, JobKind.TRANSCRIBE)
        assertTrue(catalogue.jobs.claimNext() is ClaimOutcome.Claimed)
        val transcript = Transcript(
            words.mapIndexed { idx, word -> Word("S1", idx * 300L, idx * 300L + 240L, word) },
        )
        withContext(Dispatchers.IO) {
            catalogue.documents.sealBlocking(
                noteId = id,
                audio = File(paths.note(id), "capture.opus"),
                transcript = transcript,
                title = title,
                processing = JSONObject().put("engine", "test"),
                existing = null,
                labels = mapOf("S1" to "Taylor"),
            )
        }
        catalogue.adopter.adopt(id)
        return id
    }

    private fun watchNextChildrenChange(): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        val childrenUri = DocumentsContract.buildChildDocumentsUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)
        // Ensure the vault has attached its Room invalidation observer before waiting.
        runBlocking(Dispatchers.IO) { queryChildren() }
        val observer = object : ContentObserver(Handler(observerThread.looper)) {
            override fun onChange(selfChange: Boolean) {
                if (gate.complete(Unit)) {
                    resolver.unregisterContentObserver(this)
                }
            }
        }
        resolver.registerContentObserver(childrenUri, true, observer)
        gate.invokeOnCompletion { resolver.unregisterContentObserver(observer) }
        return gate
    }

    private fun createChildDocument(parentUri: Uri, mimeType: String, displayName: String): Uri =
        requireNotNull(DocumentsContract.createDocument(resolver, parentUri, mimeType, displayName))

    private fun queryChildren(): List<ExposedDocument> {
        val uri = DocumentsContract.buildChildDocumentsUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)
        return resolver.query(uri, null, Bundle.EMPTY, null).use { cursor ->
            requireNotNull(cursor)
            buildList {
                while (cursor.moveToNext()) add(cursor.toExposedDocument())
            }
        }
    }

    private fun querySingle(documentId: String): ExposedDocument {
        val uri = DocumentsContract.buildDocumentUri(authority, documentId)
        return resolver.query(uri, null, Bundle.EMPTY, null).use { cursor ->
            requireNotNull(cursor)
            assertTrue("Expected row for $documentId", cursor.moveToFirst())
            cursor.toExposedDocument()
        }
    }

    private fun readDocumentBytes(documentId: String): ByteArray {
        val uri = DocumentsContract.buildDocumentUri(authority, documentId)
        return requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
    }

    private fun android.database.Cursor.toExposedDocument() = ExposedDocument(
        documentId = string(Document.COLUMN_DOCUMENT_ID),
        displayName = string(Document.COLUMN_DISPLAY_NAME),
        mimeType = string(Document.COLUMN_MIME_TYPE),
        size = longOrNull(Document.COLUMN_SIZE),
        lastModified = longOrNull(Document.COLUMN_LAST_MODIFIED),
        flags = int(Document.COLUMN_FLAGS),
    )

    private fun android.database.Cursor.string(col: String): String = getString(getColumnIndexOrThrow(col))

    private fun android.database.Cursor.int(col: String): Int = getInt(getColumnIndexOrThrow(col))

    private fun android.database.Cursor.longOrNull(col: String): Long? {
        val idx = getColumnIndexOrThrow(col)
        return if (isNull(idx)) null else getLong(idx)
    }

    private fun writeSyntheticOpus(file: File, packetCount: Int, seed: Int) {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0x38, 1, -128, -69, 0, 0, 0, 0, 0)
        val tags = CassiniDocument.tagsPacket(listOf("ENCODER=test"))
        // 0xF8 = CELT-only FB 20 ms mono frame (960 samples at 48 kHz).
        val packets = (0 until packetCount).map { idx ->
            byteArrayOf(0xF8.toByte(), ((seed + idx) and 0xFF).toByte(), (idx and 0xFF).toByte())
        }
        val stream = OggOpus.Stream(head, tags, packets, packetCount * 960L, true)
        file.parentFile?.mkdirs()
        file.writeBytes(OggOpus.mux(stream, tags, 0xCA55 + seed))
    }

    private fun writePcm16Wav(file: File, rate: Int = 16_000, frames: Int = 1_600) {
        val dataBytes = frames * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        buffer.putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataBytes)
        for (i in 0 until frames) {
            buffer.putShort(((i * 17) % 500).toShort())
        }
        file.writeBytes(buffer.array())
    }

    private companion object {
        const val EVENT_BOUND_MS = 10_000L
    }
}
