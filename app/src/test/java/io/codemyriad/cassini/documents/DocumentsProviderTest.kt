package io.codemyriad.cassini.documents

import android.content.Context
import android.database.ContentObserver
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.CassiniApplication
import io.codemyriad.cassini.GraphFactory
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.notes.ImportOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
class DocumentsProviderTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val io = GateDispatcher()
    private lateinit var graph: AppGraph
    private lateinit var provider: CassiniDocumentsProvider
    private val changed = Channel<Unit>(Channel.UNLIMITED)
    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) { changed.trySend(Unit) }
    }
    private val fixtures = File(checkNotNull(System.getProperty("cassini.fixtures")), "audio")

    @Before fun setUp() = runBlocking {
        val paths = AppPaths(folder.newFolder(), folder.newFolder(), folder.newFolder())
        graph = AppGraph(context, AppGraph.Config(paths = paths, network = { false }, dispatchers = CassiniDispatchers(io = io)))
        CassiniApplication.graphFactory = GraphFactory { _, _ -> graph }
        provider = freshProvider()
        withTimeout(20_000) { graph.ready.await() }
        val uri = DocumentsContract.buildChildDocumentsUri(context.packageName + ".documents", DocumentStorage.ROOT)
        context.contentResolver.registerContentObserver(uri, false, observer)
    }

    @After fun tearDown() {
        context.contentResolver.unregisterContentObserver(observer)
        provider.shutdown()
        graph.close()
        CassiniApplication.graphFactory = null
    }

    @Test fun manifestIsSystemProtectedAndOnlyPortableNotesAreListed() = runBlocking {
        val info = context.packageManager.resolveContentProvider(context.packageName + ".documents", 0)!!
        assertTrue(info.exported)
        assertTrue(info.grantUriPermissions)
        assertEquals("android.permission.MANAGE_DOCUMENTS", info.readPermission)
        assertEquals("android.permission.MANAGE_DOCUMENTS", info.writePermission)
        val document = document("Monday")
        val id = imported(document)
        val raw = imported(File(fixtures, "fleurs-it-short.wav"))
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_FLAGS, "unknown")
        provider.queryChildDocuments(DocumentStorage.ROOT, columns, sortOrder = null).use { cursor ->
            assertEquals(columns.toList(), cursor.columnNames.toList())
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("note:$id", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertTrue(cursor.isNull(2))
        }
        assertTrue(provider.isChildDocument(DocumentStorage.ROOT, "note:$id"))
        assertFalse(provider.isChildDocument(DocumentStorage.ROOT, "note:$raw"))
        for (mode in listOf("w", "wt", "rw", "wa")) {
            assertThrows(FileNotFoundException::class.java) { provider.openDocument("note:$id", mode, null) }
        }
        assertThrows(UnsupportedOperationException::class.java) { provider.deleteDocument("note:$id") }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.renameDocument("note:$id", "Changed.opus")
        }
        assertArrayEquals(document.readBytes(), read("note:$id"))
        assertArrayEquals(document.readBytes(), File(graph.paths.note(id), "document.opus").readBytes())
    }

    @Test fun incomingUrisRemainReadableAfterImportDeduplicationAndProviderRecreation() = runBlocking {
        val bytes = document("Monday").readBytes()
        val first = upload(bytes)
        val second = upload(bytes)
        repeat(8) { upload(bytes) }
        provider.queryChildDocuments(DocumentStorage.ROOT, null, sortOrder = null).use { assertEquals(1, it.count) }
        provider.shutdown()
        provider = freshProvider()
        for (id in listOf(first, second)) {
            provider.queryDocument(id, arrayOf(Document.COLUMN_FLAGS)).use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            assertArrayEquals(bytes, read(id))
        }
        assertTrue(File(graph.paths.cache, "documents-provider").listFiles().orEmpty().all { it.isDirectory })
    }

    @Test fun unknownAndEscapingIdsCannotReadOrCreateFiles() {
        val unknown = listOf(
            "../notes",
            "note:../../voices/people.json",
            "incoming:../models",
            "note:${UUID.randomUUID()}",
        )
        for (id in unknown) {
            assertThrows(FileNotFoundException::class.java) { provider.openDocument(id, "r", null) }
            assertFalse(provider.isChildDocument(DocumentStorage.ROOT, id))
        }
        for (name in listOf("../meeting.opus", "sub/meeting.opus", "..\\meeting.opus", ".", "..")) {
            assertThrows(FileNotFoundException::class.java) {
                provider.createDocument(DocumentStorage.ROOT, "audio/ogg", name)
            }
        }
        assertThrows(FileNotFoundException::class.java) {
            provider.createDocument("note:${UUID.randomUUID()}", "audio/ogg", "Monday.opus")
        }
    }

    @Test fun interruptedAtomicMetadataRecoversCompletedAliasesAndDropsPendingHandles() {
        val directory = folder.newFolder()
        val handles = ImportHandles(directory)
        val completed = handles.create("Monday.opus", "audio/ogg")
        val target = UUID.randomUUID().toString()
        handles.complete(completed, target)
        val pending = handles.create("Pending.opus", "audio/ogg")
        val record = File(directory, "$completed.json")
        assertTrue(record.renameTo(File(directory, "$completed.json.bak")))
        File(directory, "$pending.json.new").writeText("interrupted")
        val recovered = ImportHandles(directory)
        assertEquals(target, recovered.get(completed).target)
        assertThrows(FileNotFoundException::class.java) { recovered.get(pending) }
        assertFalse(File(directory, "$pending.json.new").exists())
    }

    private suspend fun upload(bytes: ByteArray): String {
        val id = provider.createDocument(DocumentStorage.ROOT, "audio/ogg", "Monday.opus")
        // Robolectric's pipe is a regular file: do not let its reader observe EOF before the writer runs.
        io.pause()
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(provider.openDocument(id, "w", null)).use { it.write(bytes) }
        } finally {
            io.resume()
        }
        withTimeout(20_000) { changed.receive() }
        assertEquals(
            org.robolectric.shadows.ShadowLog.getLogs().toString(),
            null,
            graph.notices.imports.state.value.failure,
        )
        return id
    }

    private class GateDispatcher : CoroutineDispatcher() {
        private var paused = false
        private val pending = ArrayList<Pair<CoroutineContext, Runnable>>()

        @Synchronized fun pause() { paused = true }

        @Synchronized override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (paused) pending += context to block else Dispatchers.IO.dispatch(context, block)
        }
        fun resume() {
            val tasks = synchronized(this) {
                paused = false
                pending.toList().also { pending.clear() }
            }
            tasks.forEach { (context, task) -> Dispatchers.IO.dispatch(context, task) }
        }
    }

    private fun read(id: String): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(provider.openDocument(id, "r", null)).use { it.readBytes() }

    private fun freshProvider(): CassiniDocumentsProvider =
        Robolectric.buildContentProvider(CassiniDocumentsProvider::class.java).create().get()

    private suspend fun imported(file: File): String =
        (graph.awaitCatalogue().imports.import(file.toUri()) as ImportOutcome.Created).noteId

    private fun document(title: String): File = folder.newFile(UUID.randomUUID().toString() + ".opus").apply {
        writeBytes(
            CassiniDocument.create(
                File(fixtures, "fleurs-it-short.opus").readBytes(),
                Transcript(listOf(Word("S1", 0, 100, "Hello")), "en"),
                title,
                JSONObject(),
            ),
        )
    }
}
