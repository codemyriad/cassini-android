package io.codemyriad.cassini.documents

import android.Manifest
import android.content.ClipDescription
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
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.CassiniApplication
import io.codemyriad.cassini.GraphFactory
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
import io.codemyriad.cassini.data.notes.AudioProbe
import io.codemyriad.cassini.data.notes.ImportOutcome
import io.codemyriad.cassini.data.notes.ImportRepository
import io.codemyriad.cassini.data.notes.ImportSource
import io.codemyriad.cassini.data.notes.ProbedAudio
import io.codemyriad.cassini.nav.DeepLinks
import io.codemyriad.cassini.testing.NoUncaughtCoroutines
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Tests for the read-only [CassiniDocumentsProvider] (D97):
 * - manifest protection (`MANAGE_DOCUMENTS`, URI grants, `DOCUMENTS_PROVIDER`, no self-URI deep-link loop);
 * - root and directory metadata off-disk with zero create or write flags advertised;
 * - read-only exposure of sealed `.opus` documents and plain-audio notes, dirty-title flush, byte-identical unedited
 *   imports, and case-insensitive collision numbering;
 * - exclusion of trashed notes, active recordings, running jobs, and un-adopted seals, plus `ContentObserver` updates;
 * - cold-start readiness gating on [AppGraph.ready] across database set-aside + `STEP_REBUILD`, plus `CancellationSignal`;
 * - refusal of `createDocument`, all write/append/read-write modes (`"w"`, `"wt"`, `"wa"`, `"rw"`, `"rwt"`),
 *   delete/remove/rename/move/copy, symlinks, path traversal, and main-thread calls;
 * - linear `O(N)` duplicate-title collision naming and zero `words` table reads (D87, D88).
 */
@Suppress("TooManyFunctions", "LargeClass") // one test per D97 read-only contract and D87/review invariant
@RunWith(RobolectricTestRunner::class)
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
    private lateinit var providerInstance: CassiniDocumentsProvider
    private lateinit var observerThread: HandlerThread

    @Before
    fun setUp() {
        DocumentsVault.onAwaitingReadyForTesting = null
        observerThread = HandlerThread("test-content-observer").apply { start() }
        paths = AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder())
        currentGraph = buildGraph()
        CassiniDocumentsProvider.graphOverrideForTesting = ::currentGraph
        CassiniApplication.graphFactory = GraphFactory { _, _ -> currentGraph }
        providerInstance = recreateProvider()
        runBlocking { withTimeout(EVENT_BOUND_MS) { currentGraph.ready.await() } }
    }

    @After
    fun tearDown() {
        DocumentsVault.onAwaitingReadyForTesting = null
        CassiniDocumentsProvider.graphOverrideForTesting = null
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
        val siblingAuthority = "io.codemyriad.cassini.talk${CassiniDocumentsProvider.AUTHORITY_SUFFIX}"
        if (siblingAuthority != authority) {
            val siblingDocUri = DocumentsContract.buildDocumentUri(
                siblingAuthority,
                "note:00000000-0000-4000-8000-000000000001",
            )
            assertNotNull(
                "Side-by-side debug and release variants may share documents across distinct package IDs",
                DeepLinks.external(siblingDocUri.toString(), ownPackage = app.packageName),
            )
        }
    }

    @Test
    fun queryRootsAndRootDocumentRunOffDiskWithoutCreateOrWriteFlags() {
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
            assertTrue((flags and Root.FLAG_SUPPORTS_IS_CHILD) != 0)
            assertEquals(
                "Read-only provider must not advertise Root.FLAG_SUPPORTS_CREATE",
                0,
                flags and Root.FLAG_SUPPORTS_CREATE,
            )
            val mimeTypes = cursor.string(Root.COLUMN_MIME_TYPES)
            assertTrue(mimeTypes.contains("audio/*"))
            assertTrue(mimeTypes.contains("application/ogg"))
        }

        val rootDocUri = DocumentsContract.buildDocumentUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)
        resolver.query(rootDocUri, null, Bundle.EMPTY, null).use { cursor ->
            requireNotNull(cursor)
            assertTrue(cursor.moveToFirst())
            assertEquals(Document.MIME_TYPE_DIR, cursor.string(Document.COLUMN_MIME_TYPE))
            assertEquals("Root directory must advertise zero write/create flags", 0, cursor.int(Document.COLUMN_FLAGS))
        }
    }

    @Test
    fun listsSealedOpusAndPlainAudioNotesReadOnlyFlushesDirtyTitlesAndPreservesUneditedImports(): Unit = runBlocking {
        val catalogue = currentGraph.awaitCatalogue()
        nowMs.set(1_760_000_010_000L)
        val wavId = importPlainWav("Morning standup", frames = 3_200)
        nowMs.set(1_760_000_015_000L)
        val importedOpusBytes = buildCustomerInterviewOpusBytes()
        val importedOpusFile = folder.newFile("customer-interview.opus").apply { writeBytes(importedOpusBytes) }
        val importedOpusId = (catalogue.imports.import(Uri.fromFile(importedOpusFile)) as ImportOutcome.Created).noteId
        nowMs.set(1_760_000_020_000L)
        val opusId = createSealedNote("Architecture review", listOf("Good", "morning", "team"))

        // Simulate a rename whose background retitle has not run yet (title_dirty = 1 on disk).
        catalogue.database.notes().rename(opusId, "Architecture review final", titleDirty = true, now = nowMs.get())

        val rootUri = DocumentsContract.buildDocumentUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)
        val listed = withContext(Dispatchers.IO) { queryChildren() }
        assertEquals(
            listOf("note:$opusId", "note:$importedOpusId", "note:$wavId"),
            listed.map { it.documentId },
        )
        val opusRow = listed[0]
        val importedRow = listed[1]
        val wavRow = listed[2]
        assertEquals("Architecture review final.opus", opusRow.displayName)
        assertEquals("audio/ogg", opusRow.mimeType)
        assertEquals(0, opusRow.flags)
        assertEquals("Customer interview.opus", importedRow.displayName)
        assertEquals(importedOpusBytes.size.toLong(), importedRow.size)
        assertEquals(0, importedRow.flags)
        assertEquals("Morning standup.wav", wavRow.displayName)
        assertEquals("audio/wav", wavRow.mimeType)
        assertEquals(0, wavRow.flags)

        withContext(Dispatchers.IO) {
            for (row in listed) {
                val childUri = DocumentsContract.buildDocumentUri(authority, row.documentId)
                assertTrue(DocumentsContract.isChildDocument(resolver, rootUri, childUri))
            }
            assertFalse(DocumentsContract.isChildDocument(resolver, rootUri, rootUri))
        }

        // Unedited imported document streams byte-for-byte without rewriting.
        val streamedImported = withContext(Dispatchers.IO) { readDocumentBytes(importedRow.documentId) }
        assertArrayEquals(importedOpusBytes, streamedImported)

        // Renamed OK document flushes its dirty title before COLUMN_SIZE and read stream.
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
        val second = createSealedNote("weekly sync", listOf("Second", "note"))
        nowMs.set(1_760_000_300_000L)
        val third = createSealedNote("Weekly Sync", listOf("Third", "note"))

        val children = withContext(Dispatchers.IO) { queryChildren() }
        assertEquals(
            listOf("Weekly Sync (3).opus", "weekly sync (2).opus", "Weekly sync.opus"),
            children.map { it.displayName },
        )
        for ((docId, expectedName) in listOf(
            "note:$first" to "Weekly sync.opus",
            "note:$second" to "weekly sync (2).opus",
            "note:$third" to "Weekly Sync (3).opus",
        )) {
            val single = withContext(Dispatchers.IO) { querySingle(docId) }
            assertEquals(expectedName, single.displayName)
        }
    }

    @Test
    fun hidesLiveRecordingsTrashedNotesAndNotesBeingSealedUntilAdopted(): Unit = runBlocking {
        val catalogue = currentGraph.awaitCatalogue()
        val rootUri = DocumentsContract.buildDocumentUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)
        val liveId = catalogue.notes.createRecording("Ongoing capture", nowMs.incrementAndGet(), "opus")
        writeSyntheticOpus(File(paths.note(liveId), "capture.opus"), packetCount = 5, seed = 1)

        val sealingId = createPlainOpusNote("Design walkthrough", seed = 2)
        catalogue.jobs.enqueue(sealingId, JobKind.TRANSCRIBE)
        assertTrue(catalogue.jobs.claimNext() is ClaimOutcome.Claimed)

        // Neither the active capture nor the running transcription job may appear or open.
        assertEquals(emptyList<String>(), withContext(Dispatchers.IO) { queryChildren().map { it.documentId } })
        assertNoteHiddenFromProvider(rootUri, liveId)
        assertNoteHiddenFromProvider(rootUri, sealingId)

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

        val adoptedGate = watchNextChildrenChange()
        catalogue.adopter.adopt(sealingId)
        withTimeout(EVENT_BOUND_MS) { adoptedGate.await() }
        assertEquals(listOf("note:$sealingId"), withContext(Dispatchers.IO) { queryChildren().map { it.documentId } })

        // Trashing hides the note immediately and notifies observers; restoring brings it back.
        val trashedGate = watchNextChildrenChange()
        assertTrue(catalogue.notes.moveToTrash(sealingId))
        withTimeout(EVENT_BOUND_MS) { trashedGate.await() }
        assertEquals(emptyList<String>(), withContext(Dispatchers.IO) { queryChildren().map { it.documentId } })
        assertNoteHiddenFromProvider(rootUri, sealingId)

        val restoredGate = watchNextChildrenChange()
        assertTrue(catalogue.notes.restore(sealingId))
        withTimeout(EVENT_BOUND_MS) { restoredGate.await() }
        assertEquals(listOf("note:$sealingId"), withContext(Dispatchers.IO) { queryChildren().map { it.documentId } })
    }

    /**
     * Cold-start queries, child checks, listings, and reads over a set-aside database wait for `STEP_REBUILD` and
     * [AppGraph.ready] before inspecting `notes`, while `CancellationSignal` unblocks `openDocument` immediately with
     * [OperationCanceledException].
     */
    @Test
    fun coldStartAccessWaitsForCatalogueRebuildAndHonorsCancellationSignal(): Unit = runBlocking {
        val opusBytes = buildCustomerInterviewOpusBytes()
        val opusFile = folder.newFile("cold-start.opus").apply { writeBytes(opusBytes) }
        val importOutcome = currentGraph.awaitCatalogue().imports.import(Uri.fromFile(opusFile))
        val rebuiltOpusId = (importOutcome as ImportOutcome.Created).noteId
        val rebuiltDocId = "${DocumentsVault.NOTE_PREFIX}$rebuiltOpusId"

        // Close graph and corrupt cassini.db so CassiniDatabaseOpener sets it aside and opens an empty database.
        currentGraph.close { shadowOf(Looper.getMainLooper()).idle() }
        corruptDatabaseForSetAside()

        val enteredRebuild = CompletableDeferred<Unit>()
        val releaseRebuild = CountDownLatch(1)
        startRebuildGatedGraph(enteredRebuild, releaseRebuild)

        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            withTimeout(EVENT_BOUND_MS) { enteredRebuild.await() }
            assertTrue("Room is already open at STEP_REBUILD", currentGraph.catalogue.isCompleted)
            assertFalse("Startup is not yet ready at STEP_REBUILD", currentGraph.ready.isCompleted)
            assertEquals(0, currentGraph.awaitCatalogue().database.notes().all().size)

            // 1. CancellationSignal unblocks openDocument immediately while STEP_REBUILD is still paused.
            verifyColdStartCancellationUnblocks(rebuiltDocId, callerScope)

            // 2. All four provider read entry points wait for STEP_REBUILD rather than seeing an empty database.
            assertColdStartCallsWaitThenSucceed(rebuiltDocId, opusBytes, callerScope, releaseRebuild)
        } finally {
            DocumentsVault.onAwaitingReadyForTesting = null
            callerScope.cancel()
            releaseRebuild.countDown()
        }
    }

    @Test
    fun refusesCreateWriteModesMutationsSymlinksTraversalAndMainThreadCalls(): Unit = runBlocking {
        val noteId = createSealedNote("Quarterly roadmap", listOf("Ship", "it"))
        val docId = "note:$noteId"
        val docUri = DocumentsContract.buildDocumentUri(authority, docId)
        val rootUri = DocumentsContract.buildDocumentUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)
        val originalBytes = File(paths.note(noteId), "document.opus").readBytes()

        // Main-thread calls to I/O entry points fail fast instead of blocking the UI thread.
        assertThrows(IllegalStateException::class.java) {
            DocumentsContract.isChildDocument(resolver, rootUri, docUri)
        }
        assertThrows(IllegalStateException::class.java) { queryChildren() }
        assertThrows(IllegalStateException::class.java) { providerInstance.queryDocument(docId, null) }
        assertThrows(IllegalStateException::class.java) { resolver.openInputStream(docUri) }

        // Direct provider mutation callbacks throw UnsupportedOperationException unconditionally.
        assertUnsupportedMutations(docId)

        withContext(Dispatchers.IO) {
            assertCreateAndWriteModesRejected(rootUri, docUri)
            assertSymlinkAndTraversalRefused(rootUri, noteId)
        }
        assertArrayEquals(
            "Rejected write/mutation calls must never alter note bytes",
            originalBytes,
            File(paths.note(noteId), "document.opus").readBytes(),
        )
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
        assertEquals(999, smallProbes)
        assertEquals(3_999, largeProbes)
    }

    @Test
    fun rejectsDirectorySymlinksAcrossListQueryReadAndDirtyRetitle(): Unit = runBlocking {
        val catalogue = currentGraph.awaitCatalogue()
        val rootUri = DocumentsContract.buildDocumentUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)

        // 1. Live note whose notes/<id> directory is replaced by a symlink leaving notes/ (with titleDirty = true).
        val outsideAliasId = createSealedNote("Outside alias", listOf("Original", "words"))
        catalogue.database.notes().rename(
            outsideAliasId,
            "Hijacked outside title",
            titleDirty = true,
            now = nowMs.incrementAndGet(),
        )
        val outsideDir = folder.newFolder("outside-note-target")
        val outsideBytes = buildCustomerInterviewOpusBytes()
        File(outsideDir, "document.opus").writeBytes(outsideBytes)
        replaceNoteDirWithSymlink(outsideAliasId, outsideDir)

        // 2. Live note whose notes/<id> directory is replaced by a symlink to a trashed note's directory inside notes/.
        val trashedId = createSealedNote("Private trashed note", listOf("Secret", "trash"))
        val trashedBytes = File(paths.note(trashedId), "document.opus").readBytes()
        assertTrue(catalogue.notes.moveToTrash(trashedId))
        val trashAliasId = createSealedNote("Trash alias", listOf("Decoy", "words"))
        catalogue.database.notes().rename(
            trashAliasId,
            "Hijacked trash title",
            titleDirty = true,
            now = nowMs.incrementAndGet(),
        )
        replaceNoteDirWithSymlink(trashAliasId, paths.note(trashedId))

        // Keep one legitimate live note alongside them so listChildren returns only the legitimate row.
        val validId = createSealedNote("Legitimate note", listOf("Safe", "content"))

        val listed = withContext(Dispatchers.IO) { queryChildren() }
        assertEquals(listOf("note:$validId"), listed.map { it.documentId })
        assertDirectorySymlinkNoteRejected(rootUri, outsideAliasId)
        assertDirectorySymlinkNoteRejected(rootUri, trashAliasId)

        assertArrayEquals(
            "Dirty-title flush must never rewrite a document.opus outside notes/ through a directory symlink",
            outsideBytes,
            File(outsideDir, "document.opus").readBytes(),
        )
        assertArrayEquals(
            "Dirty-title flush must never rewrite a trashed note's document.opus through a directory symlink",
            trashedBytes,
            File(paths.note(trashedId), "document.opus").readBytes(),
        )
    }

    @Test
    fun exposesAccuratePlainAudioMimesAndMatchesAudioWildcardPicker(): Unit = runBlocking {
        val samples = plainAudioMimeSamples()
        val imported = samples.map { sample -> sample to importPlainSample(sample) }

        val listedById = withContext(Dispatchers.IO) { queryChildren().associateBy { it.documentId } }
        assertEquals(imported.size, listedById.size)

        for ((sample, noteId) in imported) {
            val docId = "note:$noteId"
            val listedRow = requireNotNull(listedById[docId]) { "Missing listed row for ${sample.label}" }
            val queriedRow = withContext(Dispatchers.IO) { querySingle(docId) }
            for (row in listOf(listedRow, queriedRow)) {
                assertEquals("Unexpected MIME for ${sample.label}", sample.expectedMime, row.mimeType)
                assertEquals("Unexpected display name for ${sample.label}", sample.expectedDisplayName, row.displayName)
                assertNotEquals("application/octet-stream", row.mimeType)
                assertTrue(
                    "audio/* picker must match ${row.mimeType} (${sample.label})",
                    ClipDescription.compareMimeTypes(row.mimeType, "audio/*"),
                )
            }
            if (sample.fileName?.endsWith(".aac") == true) {
                assertNotEquals("Raw .aac must not be mislabeled as audio/mp4", "audio/mp4", listedRow.mimeType)
            }
            val streamed = withContext(Dispatchers.IO) { readDocumentBytes(docId) }
            assertArrayEquals(sample.bytes, streamed)
        }
    }

    // Helpers. --------------------------------------------------------------------------------------------------------

    private fun buildGraph(
        dispatchers: CassiniDispatchers =
            CassiniDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Unconfined),
        onStartupStep: (String) -> Unit = {},
    ): AppGraph = AppGraph(
        app,
        AppGraph.Config(
            paths = paths,
            dispatchers = dispatchers,
            fetcher = ModelFetcher { _, _, _ -> fail("No model download expected") },
            network = { false },
            clock = nowMs::get,
            onStartupStep = onStartupStep,
        ),
    )

    private fun recreateProvider(): CassiniDocumentsProvider {
        val info = ProviderInfo().apply {
            authority = this@CassiniDocumentsProviderTest.authority
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        }
        return Robolectric.buildContentProvider(CassiniDocumentsProvider::class.java).create(info).get()
    }

    private suspend fun assertNoteHiddenFromProvider(rootUri: Uri, noteId: String) = withContext(Dispatchers.IO) {
        val uri = DocumentsContract.buildDocumentUri(authority, "note:$noteId")
        assertFalse(DocumentsContract.isChildDocument(resolver, rootUri, uri))
        assertNull(resolver.query(uri, null, Bundle.EMPTY, null))
        assertThrows(FileNotFoundException::class.java) { readDocumentBytes("note:$noteId") }
    }

    private fun assertUnsupportedMutations(docId: String) {
        assertThrows(UnsupportedOperationException::class.java) {
            providerInstance.createDocument(DocumentsVault.ROOT_DOCUMENT_ID, "audio/wav", "New.wav")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            providerInstance.deleteDocument(docId)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            providerInstance.removeDocument(docId, DocumentsVault.ROOT_DOCUMENT_ID)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            providerInstance.renameDocument(docId, "Renamed.opus")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            providerInstance.moveDocument(docId, DocumentsVault.ROOT_DOCUMENT_ID, DocumentsVault.ROOT_DOCUMENT_ID)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            providerInstance.copyDocument(docId, DocumentsVault.ROOT_DOCUMENT_ID)
        }
    }

    private fun assertCreateAndWriteModesRejected(rootUri: Uri, docUri: Uri) {
        val createAttempt = runCatching {
            DocumentsContract.createDocument(resolver, rootUri, "audio/wav", "External.wav")
        }.getOrNull()
        assertNull("DocumentsContract.createDocument must not allocate any URI", createAttempt)

        for (targetUri in listOf(docUri, rootUri)) {
            for (writeMode in listOf("w", "wt", "wa", "rw", "rwt")) {
                assertThrows(
                    "Mode $writeMode must be rejected immediately with SecurityException on $targetUri",
                    SecurityException::class.java,
                ) {
                    resolver.openFileDescriptor(targetUri, writeMode)
                }
            }
            assertThrows(SecurityException::class.java) { resolver.openOutputStream(targetUri) }
        }
        assertThrows(IllegalArgumentException::class.java) { providerInstance.openDocument("root", null, null) }
        assertThrows(UnsupportedOperationException::class.java) { DocumentsContract.deleteDocument(resolver, docUri) }
        assertThrows(UnsupportedOperationException::class.java) {
            DocumentsContract.renameDocument(resolver, docUri, "Renamed.opus")
        }
    }

    private suspend fun assertSymlinkAndTraversalRefused(rootUri: Uri, validNoteId: String) {
        // Seed a catalogued note row whose document.opus was replaced on disk by a symlink to an outside file.
        val secretTarget = folder.newFile("outside-secret.txt").apply { writeText("private") }
        val symlinkId = createPlainOpusNote("Symlink probe", seed = 99)
        val captureFile = File(paths.note(symlinkId), "capture.opus")
        assertTrue(captureFile.delete())
        Files.createSymbolicLink(captureFile.toPath(), secretTarget.toPath())

        assertNoteHiddenFromProvider(rootUri, symlinkId)

        assertThrows(FileNotFoundException::class.java) {
            providerInstance.queryChildDocuments("other-parent", null, null as String?)
        }
        val badIds = listOf(
            "note:../cassini.db",
            "note:not-a-uuid",
            "note:${validNoteId.uppercase()}",
            "pending:$validNoteId",
            "other:$validNoteId",
            "../secret",
        )
        for (badId in badIds) {
            val badUri = DocumentsContract.buildDocumentUri(authority, badId)
            assertFalse(DocumentsContract.isChildDocument(resolver, rootUri, badUri))
            assertThrows(FileNotFoundException::class.java) { resolver.openInputStream(badUri) }
            assertNull(resolver.query(badUri, null, Bundle.EMPTY, null))
        }
    }

    private suspend fun verifyColdStartCancellationUnblocks(rebuiltDocId: String, callerScope: CoroutineScope) {
        val signal = CancellationSignal()
        val parkedOnReady = CompletableDeferred<Unit>()
        DocumentsVault.onAwaitingReadyForTesting = { parkedOnReady.complete(Unit) }
        val openAttempt = callerScope.async {
            assertThrows(OperationCanceledException::class.java) {
                providerInstance.openDocument(rebuiltDocId, "r", signal)
            }
        }
        withTimeout(EVENT_BOUND_MS) { parkedOnReady.await() }
        DocumentsVault.onAwaitingReadyForTesting = null
        // Cancelling an in-flight openDocument parked on AppGraph.ready unblocks immediately with OperationCanceledException.
        signal.cancel()
        withTimeout(EVENT_BOUND_MS) { openAttempt.await() }

        val preCanceled = CancellationSignal().apply { cancel() }
        withContext(Dispatchers.IO) {
            assertThrows(OperationCanceledException::class.java) {
                providerInstance.openDocument(rebuiltDocId, "r", preCanceled)
            }
        }
        assertFalse("Startup must still be paused in STEP_REBUILD", currentGraph.ready.isCompleted)
    }

    private suspend fun assertColdStartCallsWaitThenSucceed(
        rebuiltDocId: String,
        expectedBytes: ByteArray,
        callerScope: CoroutineScope,
        releaseRebuild: CountDownLatch,
    ) {
        val rootUri = DocumentsContract.buildDocumentUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)
        val rebuiltUri = DocumentsContract.buildDocumentUri(authority, rebuiltDocId)

        val allFourParked = CountDownLatch(4)
        DocumentsVault.onAwaitingReadyForTesting = { allFourParked.countDown() }
        val isChildJob = callerScope.async { DocumentsContract.isChildDocument(resolver, rootUri, rebuiltUri) }
        val listJob = callerScope.async { queryChildren() }
        val singleJob = callerScope.async { querySingle(rebuiltDocId) }
        val readJob = callerScope.async { readDocumentBytes(rebuiltDocId) }

        assertTrue("All four cold-start calls must enter awaitReadyCatalogue", allFourParked.await(5, TimeUnit.SECONDS))
        DocumentsVault.onAwaitingReadyForTesting = null

        assertFalse("isChildDocument must wait for AppGraph.ready", isChildJob.isCompleted)
        assertFalse("queryChildDocuments must wait for AppGraph.ready", listJob.isCompleted)
        assertFalse("queryDocument must wait for AppGraph.ready", singleJob.isCompleted)
        assertFalse("openDocument must wait for AppGraph.ready", readJob.isCompleted)

        releaseRebuild.countDown()

        assertTrue(withTimeout(EVENT_BOUND_MS) { isChildJob.await() })
        val listed = withTimeout(EVENT_BOUND_MS) { listJob.await() }
        assertEquals(listOf(rebuiltDocId), listed.map { it.documentId })
        assertEquals("Customer interview.opus", withTimeout(EVENT_BOUND_MS) { singleJob.await() }.displayName)
        assertArrayEquals(expectedBytes, withTimeout(EVENT_BOUND_MS) { readJob.await() })
    }

    private fun startRebuildGatedGraph(
        enteredRebuild: CompletableDeferred<Unit>,
        releaseRebuild: CountDownLatch,
    ) {
        currentGraph = buildGraph(
            onStartupStep = { step ->
                if (step == AppGraph.STEP_REBUILD) {
                    enteredRebuild.complete(Unit)
                    check(releaseRebuild.await(10, TimeUnit.SECONDS)) { "STEP_REBUILD gate was not released" }
                }
            },
        )
        CassiniDocumentsProvider.graphOverrideForTesting = ::currentGraph
        CassiniApplication.graphFactory = GraphFactory { _, _ -> currentGraph }
        providerInstance = recreateProvider()
    }

    private fun corruptDatabaseForSetAside() {
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            File(paths.database.path + suffix).delete()
        }
        paths.database.writeBytes("corrupt sqlite header for set-aside test".toByteArray())
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
            createdAtUtc = Instant.ofEpochMilli(nowMs.get()),
        )
        sourceOpus.readBytes()
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

    private fun replaceNoteDirWithSymlink(noteId: String, targetDir: File) {
        val dir = paths.note(noteId)
        assertTrue(dir.deleteRecursively())
        Files.createSymbolicLink(dir.toPath(), targetDir.toPath())
    }

    private suspend fun assertDirectorySymlinkNoteRejected(rootUri: Uri, noteId: String) = withContext(Dispatchers.IO) {
        val docId = "note:$noteId"
        val uri = DocumentsContract.buildDocumentUri(authority, docId)
        assertFalse(DocumentsContract.isChildDocument(resolver, rootUri, uri))
        assertNull(resolver.query(uri, null, Bundle.EMPTY, null))
        assertThrows(FileNotFoundException::class.java) { providerInstance.queryDocument(docId, null) }
        assertThrows(FileNotFoundException::class.java) { providerInstance.openDocument(docId, "r", null) }
        assertThrows(FileNotFoundException::class.java) { readDocumentBytes(docId) }
    }

    private suspend fun importPlainSample(sample: PlainAudioSample): String {
        val catalogue = currentGraph.awaitCatalogue()
        val uri = Uri.parse("content://external.audio/item/${UUID.randomUUID()}")
        val source = object : ImportSource {
            override fun describe(uri: Uri) = ImportSource.Description(sample.fileName, sample.bytes.size.toLong())

            override fun open(uri: Uri): InputStream = sample.bytes.inputStream()
        }
        val probe = AudioProbe { ProbedAudio(sample.trackMime, durationMs = 2_400L) }
        val importer = ImportRepository(
            database = catalogue.database,
            paths = paths,
            dispatchers = currentGraph.dispatchers,
            locks = currentGraph.locks,
            documents = catalogue.documents,
            source = source,
            probe = probe,
            clock = nowMs::incrementAndGet,
        )
        val outcome = importer.import(uri)
        assertTrue("Expected Created for ${sample.label}, got $outcome", outcome is ImportOutcome.Created)
        return (outcome as ImportOutcome.Created).noteId
    }

    private companion object {
        const val EVENT_BOUND_MS = 10_000L
    }
}

private class PlainAudioSample(
    val fileName: String?,
    val trackMime: String,
    val bytes: ByteArray,
    val expectedMime: String,
    val expectedDisplayName: String,
) {
    val label: String get() = "${fileName ?: expectedDisplayName} ($expectedMime)"
}

private fun sample(
    fileName: String?,
    trackMime: String,
    latin1Bytes: String,
    expectedMime: String,
    expectedDisplayName: String = checkNotNull(fileName),
) = PlainAudioSample(
    fileName = fileName,
    trackMime = trackMime,
    bytes = latin1Bytes.toByteArray(Charsets.ISO_8859_1),
    expectedMime = expectedMime,
    expectedDisplayName = expectedDisplayName,
)

private fun plainAudioMimeSamples(): List<PlainAudioSample> = listOf(
    sample("voice-memo.amr", "audio/3gpp", "#!AMR\n\u0004payload-amr-nb", "audio/amr"),
    sample("wideband-call.awb", "audio/amr-wb", "#!AMR-WB\n\u0004payload-awb", "audio/amr-wb"),
    sample("handset-clip.3gp", "audio/3gpp", "\u0000\u0000\u0000\u0014ftyp3gp4\u0000\u00003gp4", "audio/3gpp"),
    sample("carrier-clip.3g2", "audio/3gpp2", "\u0000\u0000\u0000\u0014ftyp3g2a\u0000\u00003g2a", "audio/3gpp2"),
    sample("browser-capture.webm", "audio/opus", "\u001a\u0045\u00df\u00a3\u009fwebm-opus-1", "audio/webm"),
    sample("raw-stream.aac", "audio/aac", "\u00ff\u00f1\u0050\u0080\u0001\u003f\u00fc", "audio/aac"),
    sample("podcast-clip.m4a", "audio/mp4a-latm", "\u0000\u0000\u0000\u0014ftypM4A 0000", "audio/mp4"),
    sample("interview.mp3", "audio/mpeg", "ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000mp3", "audio/mpeg"),
    sample("studio-take.flac", "audio/flac", "fLaC\u0000\u0000\u0000\u0022flac-stream", "audio/flac"),
    sample(
        fileName = null,
        trackMime = "audio/opus",
        latin1Bytes = "\u001a\u0045\u00df\u00a3\u009fwebm-nameless",
        expectedMime = "audio/webm",
        expectedDisplayName = "Imported audio.bin",
    ),
    sample(
        fileName = null,
        trackMime = "audio/amr",
        latin1Bytes = "#!AMR\n\u0004nameless-amr-nb",
        expectedMime = "audio/amr",
        expectedDisplayName = "Imported audio (2).bin",
    ),
)

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
