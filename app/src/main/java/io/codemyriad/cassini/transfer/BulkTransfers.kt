package io.codemyriad.cassini.transfer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.data.db.NoteEntity
import io.codemyriad.cassini.data.notes.ArchiveLimits
import io.codemyriad.cassini.data.notes.ImportSummary
import io.codemyriad.cassini.data.notes.NoteArchives
import io.codemyriad.cassini.data.useCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.UUID

/** Process-scoped, cancellable transfers. Temporary snapshots never become a second source of truth. */
class BulkTransfers(context: Context, private val graph: AppGraph) {
    private val resolver = context.contentResolver
    private val importer = ArchiveImports(context, graph)
    private val archiveLimits = ArchiveLimits()
    private val archives = NoteArchives(archiveLimits)
    private val scratchRoot = File(graph.paths.cache, "bulk-transfers")
    private val initialization = Mutex()
    private var initialized = false
    private val mutable = MutableStateFlow(TransferState())
    val state = mutable.asStateFlow()

    @Volatile private var job: Job? = null
    private val snapshots = TransferSnapshots(graph)

    data class Choice(val id: String, val title: String)
    data class TransferState(
        val running: Boolean = false,
        val summary: ImportSummary? = null,
        val exported: Int? = null,
        val failed: Boolean = false,
        val exporting: Boolean = false,
        val done: Int = 1,
        val total: Int = 1,
        val cancelled: Boolean = false,
    )

    val available: Flow<List<Choice>> = flow {
        val catalogue = graph.awaitCatalogue()
        emitAll(
            catalogue.notes.library().map { library ->
                val rows = catalogue.database.notes().all().associateBy { it.id }
                library.mapNotNull { item -> rows[item.id]?.takeIf(::exportable)?.let { Choice(it.id, it.title) } }
            },
        )
    }.flowOn(graph.dispatchers.io)

    private fun exportable(row: NoteEntity): Boolean {
        if (row.trashedAt != null || row.audioKind in setOf(AudioKind.LIVE_CAPTURE, AudioKind.UNPLAYABLE)) return false
        return File(graph.paths.note(row.id), row.audioFile).isFile
    }

    suspend fun choices(): List<Choice> = available.first()

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        start(total = uris.size, beginning = { graph.notices.imports.dismissFailure() }) { scratch ->
            var total = ImportSummary()
            for ((index, uri) in uris.withIndex()) {
                // A cancelled selection imports nothing more: some failures return without suspending.
                currentCoroutineContext().ensureActive()
                mutable.value = mutable.value.copy(done = index + 1)
                val previous = total
                // Each picked file has its Library card (L-25, W4.5); one that added nothing keeps it (W4.6).
                val card = graph.notices.imports.begin(importer.nameOf(uri).orEmpty())
                // Settled on every path: a cancel removes the card, an error leaves it failed; none stays "Importing…".
                var cardFailure: Failure? = Failure.OPEN
                try {
                    val result = importer.importOne(uri, scratch) { partial ->
                        mutable.value = mutable.value.copy(summary = previous + partial)
                    }
                    // Cancelled as the file failed: a cancelled transfer leaves no "Needs attention" card.
                    currentCoroutineContext().ensureActive()
                    cardFailure = cardFailure(result)
                    total += result
                    mutable.value = mutable.value.copy(summary = total)
                } catch (cancelled: CancellationException) {
                    cardFailure = null
                    throw cancelled
                } finally {
                    graph.notices.imports.finish(card, cardFailure)
                }
            }
            TransferState(summary = total)
        }
    }

    /** OPEN when the picked file added nothing (no note, no duplicate): its card stays with "Needs attention". */
    private fun cardFailure(result: ImportSummary): Failure? =
        if (result.imported == 0 && result.duplicates == 0) Failure.OPEN else null

    fun export(ids: List<String>, destination: Uri) = start(exporting = true, total = ids.size) { scratch ->
        try {
            require(ids.isNotEmpty() && ids.distinct().size == ids.size && ids.size <= archiveLimits.entries)
            val output = resolver.openOutputStream(destination, "wt") ?: throw IOException("Cannot open destination")
            output.useCancellable { out ->
                archives.write(out) { writer ->
                    for ((index, id) in ids.withIndex()) {
                        mutable.value = mutable.value.copy(done = index + 1)
                        val copy = snapshots.copy(id, scratch, writer.remainingBytes)
                        try {
                            writer.add(copy.name, copy.file, copy.crc)
                        } finally {
                            copy.file.delete()
                        }
                    }
                }
            }
            TransferState(exported = ids.size, exporting = true)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            removeIncomplete(destination)
            throw error
        }
    }

    /** Providers may reject deletion of a partially written destination; private staging is always removed. */
    private fun removeIncomplete(destination: Uri) {
        try {
            if (destination.scheme == "file") {
                destination.path?.let { File(it).delete() }
            } else {
                DocumentsContract.deleteDocument(resolver, destination)
            }
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.w(TAG, "Cannot remove an incomplete export", error)
        }
    }

    @Synchronized fun cancel() { job?.cancel() }

    @Synchronized fun dismiss() { if (!mutable.value.running) mutable.value = TransferState() }

    @Synchronized private fun start(
        exporting: Boolean = false,
        total: Int,
        beginning: () -> Unit = {},
        action: suspend (File) -> TransferState,
    ) {
        if (mutable.value.running) return
        beginning()
        mutable.value = TransferState(running = true, exporting = exporting, total = total.coerceAtLeast(1))
        val launched = graph.appScope.launch(graph.dispatchers.io, start = CoroutineStart.LAZY) {
            try {
                val result = perform(action)
                currentCoroutineContext().ensureActive()
                mutable.value = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "Transfer failed", error)
                mutable.value = TransferState(failed = true, exporting = exporting)
            }
        }
        job = launched
        launched.invokeOnCompletion { cause ->
            if (cause is CancellationException && job === launched && mutable.value.running) {
                mutable.value = if (exporting) TransferState() else mutable.value.copy(running = false, cancelled = true)
            }
        }
        launched.start()
    }

    private suspend fun perform(action: suspend (File) -> TransferState): TransferState {
        // Opening Room does not finish recovery. Its rebuild must not race an import's file/row commit.
        graph.ready.await()
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
    }
}
