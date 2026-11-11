package io.codemyriad.cassini.documents

import android.content.Context
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.data.notes.ImportOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** The provider's private staging and bounded descriptors; no client path ever reaches a note directory. */
@Suppress("TooManyFunctions") // Provider operations and their descriptor cleanup share one bounded workspace.
internal class DocumentStorage(
    private val context: Context,
    private val graph: AppGraph,
    private val maxBytes: Long = MAX_BYTES,
) {
    private val root = File(graph.paths.root, "documents-provider")
    private val scratch = File(graph.paths.cache, "documents-provider")
    private val handles = ImportHandles(File(root, "handles"))
    val copies = DocumentCopies(graph)
    private val uploads = Semaphore(MAX_OPEN)
    private val reads = Semaphore(MAX_OPEN)
    private val budget = AtomicLong()
    private val handler = Handler(Looper.getMainLooper())

    init {
        check(!scratch.exists() || scratch.deleteRecursively())
        check(scratch.mkdirs())
    }

    fun create(name: String, mime: String): String {
        val safe = name.isNotEmpty() && name != "." && name != ".." && name.toByteArray().size <= MAX_NAME_BYTES &&
            name.none { it == '/' || it == '\\' || Character.isISOControl(it) }
        if (!safe || mime == DocumentsContract.Document.MIME_TYPE_DIR || mime.length > MAX_MIME_LENGTH) {
            throw FileNotFoundException("Unsupported import name or type")
        }
        if (mime == "application/zip" || name.endsWith(".zip", ignoreCase = true)) {
            throw FileNotFoundException("Use the Library picker to import archives")
        }
        return "incoming:" + handles.create(name, mime)
    }

    suspend fun document(id: String): ExposedDocument {
        val target = target(id)
        if (target == null) {
            val handle = handles.get(token(id))
            return ExposedDocument(id, handle.name, handle.mime, null, null)
        }
        return copies.metadata(id, copies.row(target))
    }

    fun writable(id: String): Boolean = id.startsWith("incoming:") && handles.get(token(id)).let {
        it.target == null && !it.writing
    }

    suspend fun open(id: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = when {
        mode == "r" -> read(id)
        mode in WRITE_MODES && id.startsWith("incoming:") -> upload(token(id), signal)
        else -> throw FileNotFoundException("Document is read-only")
    }

    private suspend fun target(id: String): String? = when {
        id.startsWith("note:") -> token(id).also {
            if (copies.row(it).audioKind != AudioKind.DOCUMENT) throw FileNotFoundException("Unknown document")
        }
        id.startsWith("incoming:") -> handles.get(token(id)).target
        else -> throw FileNotFoundException("Unknown document")
    }

    @Suppress("ThrowsCount") // Acquisition failures and copy failures must each release the descriptor reservation.
    private suspend fun read(id: String): ParcelFileDescriptor {
        val target = target(id) ?: throw FileNotFoundException("Import is not finished")
        if (!reads.tryAcquire()) throw FileNotFoundException("Too many open documents")
        val directory = File(scratch, UUID.randomUUID().toString())
        val reserved = AtomicLong()
        val released = AtomicBoolean()
        fun release() {
            if (released.compareAndSet(false, true)) {
                directory.deleteRecursively()
                budget.addAndGet(-reserved.get())
                reads.release()
            }
        }
        try {
            check(directory.mkdirs())
            val copy = copies.snapshot(target, directory) { size ->
                reserve(size, true)
                reserved.set(size)
            }
            return ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY, handler) {
                graph.appScope.launch(graph.dispatchers.io) { release() }
            }
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            release()
            throw error
        }
    }

    @Suppress("ThrowsCount") // Roll back each acquired resource before propagating open/cancel failures.
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun upload(id: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (!uploads.tryAcquire()) throw FileNotFoundException("Too many open imports")
        val handle = try {
            handles.claim(id)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            uploads.release()
            throw error
        }
        val pipe = try { ParcelFileDescriptor.createReliablePipe() } catch (error: IOException) {
            uploads.release()
            handles.discard(id)
            throw error
        }
        // ATOMIC guarantees entry into receive's finally even when IO was cancelled before dispatch.
        val job = graph.appScope.launch(graph.dispatchers.io, start = CoroutineStart.ATOMIC) {
            receive(id, handle, pipe[0])
        }
        signal?.setOnCancelListener { job.cancel() }
        job.invokeOnCompletion { signal?.setOnCancelListener(null) }
        if (signal?.isCanceled == true) {
            runCatching { pipe[1].closeWithError("Cancelled") }
            job.cancel()
            throw OperationCanceledException()
        }
        return pipe[1]
    }

    private suspend fun receive(id: String, handle: ImportHandles.Handle, pipe: ParcelFileDescriptor) {
        val directory = File(scratch, UUID.randomUUID().toString())
        val reserved = AtomicLong()
        var completed = false
        try {
            check(directory.mkdirs())
            val file = File(directory, handle.name)
            receiveBytes(pipe, file, reserved)
            val outcome = graph.notices.imports.track(handle.name) {
                graph.awaitCatalogue().imports.import(file.toUri())
            }
            val target = when (outcome) {
                is ImportOutcome.Created -> outcome.noteId
                is ImportOutcome.Existing -> outcome.noteId
                else -> null
            }
            if (target != null) {
                handles.complete(id, target)
                completed = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Files import failed", error)
            graph.notices.imports.refuse(if (scratch.usableSpace < FREE_MARGIN) Failure.SPACE else Failure.OPEN)
            runCatching { pipe.closeWithError("Import failed") }
        } finally {
            runCatching { pipe.close() }
            try {
                directory.deleteRecursively()
            } finally {
                budget.addAndGet(-reserved.get())
                uploads.release()
                if (!completed) runCatching { handles.discard(id) }
                changed()
            }
        }
    }

    private suspend fun receiveBytes(pipe: ParcelFileDescriptor, file: File, reserved: AtomicLong): Long = coroutineScope {
        val input = ParcelFileDescriptor.AutoCloseInputStream(pipe)
        val finished = AtomicBoolean()
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!finished.get()) runCatching { input.close() } }
        }
        try {
            input.use { stream ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var copied = 0L
                    var nextSpaceCheck = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = stream.read(buffer)
                        if (n < 0) break
                        reserve(n.toLong(), copied >= nextSpaceCheck)
                        reserved.addAndGet(n.toLong())
                        nextSpaceCheck = if (copied >= nextSpaceCheck) copied + SPACE_CHECK_BYTES else nextSpaceCheck
                        copied += n
                        out.write(buffer, 0, n)
                    }
                    copied
                }
            }
        } finally {
            finished.set(true)
            closer.cancel()
        }
    }

    @Synchronized private fun reserve(bytes: Long, checkSpace: Boolean) {
        if (bytes < 0 || bytes > maxBytes - budget.get()) throw IOException("Temporary size limit exceeded")
        if (checkSpace && scratch.usableSpace < bytes + FREE_MARGIN) throw IOException("Not enough temporary space")
        budget.addAndGet(bytes)
    }

    private fun changed() {
        val authority = context.packageName + ".documents"
        context.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, ROOT), null)
    }

    private fun token(id: String): String = id.substringAfter(':').also {
        if (!ImportHandles.uuid(it)) throw FileNotFoundException("Unknown document")
    }

    companion object {
        const val ROOT = "cassini"
        private const val TAG = "CassiniDocuments"
        private const val MAX_OPEN = 8
        private const val MAX_NAME_BYTES = 255
        private const val MAX_MIME_LENGTH = 128
        private const val MAX_BYTES = 4L shl 30
        private const val FREE_MARGIN = 128L shl 20
        private const val SPACE_CHECK_BYTES = 8L shl 20
        private const val COPY_BUFFER = 64 * 1_024
        private val WRITE_MODES = setOf("w", "wt")
    }
}
