package io.codemyriad.cassini.transfer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.export.ExportNames
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.data.notes.ImportSummary
import io.codemyriad.cassini.data.notes.NoteArchives
import io.codemyriad.cassini.data.useCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Process-scoped, cancellable transfers. Temporary snapshots never become a second source of truth. */
class BulkTransfers(context: Context, private val graph: AppGraph) {
    private val resolver = context.contentResolver
    private val importer = ArchiveImports(context, graph)
    private val archives = NoteArchives()
    private val scratchRoot = File(graph.paths.cache, "bulk-transfers")
    private val initialization = Mutex()
    private var initialized = false
    private val mutable = MutableStateFlow(TransferState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    data class Choice(val id: String, val title: String)
    data class TransferState(
        val running: Boolean = false,
        val summary: ImportSummary? = null,
        val exported: Int? = null,
        val failed: Boolean = false,
    )

    suspend fun choices(): List<Choice> = graph.awaitCatalogue().notes.library().first()
        .filter { it.audioKind != AudioKind.LIVE_CAPTURE }.map { Choice(it.id, it.title) }

    fun import(uris: List<Uri>) = start { scratch ->
        var total = ImportSummary()
        for (uri in uris.take(MAX_FILES)) total += importer.importOne(uri, scratch)
        if (uris.size > MAX_FILES) total += ImportSummary(failed = uris.size - MAX_FILES)
        TransferState(summary = total)
    }

    fun export(ids: List<String>, destination: Uri) = start { scratch ->
        require(ids.isNotEmpty() && ids.distinct().size == ids.size && ids.size <= MAX_FILES)
        try {
            var total = 0L
            val files = ids.map {
                snapshot(it, scratch, MAX_BYTES - total).also { (_, file) ->
                    total += file.length()
                    if (total > MAX_BYTES) throw IOException("Archive is too large")
                }
            }
            if (scratch.usableSpace < total + FREE_MARGIN) throw IOException("Not enough space")
            val archive = File(scratch, "notes.zip")
            archives.write(archive.outputStream(), files)
            val output = resolver.openOutputStream(destination, "wt") ?: throw IOException("Cannot open destination")
            output.useCancellable { out ->
                archive.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
            }
            TransferState(exported = files.size)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            removeIncomplete(destination)
            throw error
        }
    }

    /** Providers may reject deletion of a partially written destination; private staging is always removed. */
    private fun removeIncomplete(destination: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, destination)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.w(TAG, "Cannot remove an incomplete export", error)
        }
    }

    /** Retitle through the existing repository, then copy a complete file while its normal writer lock is held. */
    suspend fun snapshot(
        id: String,
        scratch: File,
        maxBytes: Long = MAX_BYTES,
    ): Pair<String, File> = withContext(graph.dispatchers.io) {
        val catalogue = graph.awaitCatalogue()
        catalogue.documents.retitle(id)
        val context = currentCoroutineContext()
        graph.locks.withNoteLock(id, graph.dispatchers.io) {
            val row = catalogue.database.notes().noteBlocking(id) ?: throw IOException("Note unavailable")
            if (row.trashedAt != null || row.audioKind == AudioKind.LIVE_CAPTURE) {
                throw IOException("Document unavailable")
            }
            val file = File(scratch, UUID.randomUUID().toString())
            File(graph.paths.note(id), row.audioFile).inputStream().use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var nextSpaceCheck = 0L
                    while (true) {
                        context.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied >= nextSpaceCheck || copied > maxBytes) {
                            nextSpaceCheck = copied + SPACE_CHECK_BYTES
                            if (copied > maxBytes || scratch.usableSpace < FREE_MARGIN) {
                                throw IOException("Not enough space for the document")
                            }
                        }
                        out.write(buffer, 0, n)
                    }
                }
            }
            ExportNames.file(row.title, row.audioFile.substringAfterLast('.', "opus")) to file
        }
    }

    fun cancel() { job?.cancel() }
    fun dismiss() { if (!mutable.value.running) mutable.value = TransferState() }

    private fun start(action: suspend (File) -> TransferState) {
        if (mutable.value.running) return
        mutable.value = TransferState(running = true)
        job = graph.appScope.launch(graph.dispatchers.io) {
            try {
                mutable.value = perform(action)
            } catch (cancelled: CancellationException) {
                mutable.value = TransferState()
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Log.w(TAG, "Transfer failed", error)
                mutable.value = TransferState(failed = true)
            }
        }
    }

    private suspend fun perform(action: suspend (File) -> TransferState): TransferState {
        val scratch = File(scratchRoot, UUID.randomUUID().toString())
        try {
            initialization.withLock {
                if (!initialized) {
                    scratchRoot.deleteRecursively()
                    check(scratchRoot.mkdirs())
                    initialized = true
                }
            }
            check(scratch.mkdirs())
            return action(scratch)
        } finally {
            scratch.deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "CassiniTransfer"
        const val MAX_FILES = 1_000
        const val MAX_BYTES = 4L shl 30
        const val SPACE_CHECK_BYTES = 8L shl 20
        const val FREE_MARGIN = 128L shl 20
    }
}
