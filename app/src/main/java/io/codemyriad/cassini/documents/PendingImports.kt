package io.codemyriad.cassini.documents

import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import io.codemyriad.cassini.core.export.ExportNames
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * In-flight documents created through [CassiniDocumentsProvider.createDocument].
 *
 * Each `createDocument` allocates an unguessable `pending:<uuid>` handle without touching disk. The first write opens
 * `cacheDir/provider-imports/<uuid>/<cleanName>` with a close callback on a dedicated background [HandlerThread].
 * When the writer closes cleanly, [importFileUri] hands `Uri.fromFile(file)` to `NoticeCenter.imports`
 * (`ImportRepository`, enforcing the 4 GiB cap, free-space floor and SHA-256 deduplication) and removes the staging
 * folder afterwards.
 */
@Suppress("TooManyFunctions") // staging lifecycle, sanitization and bounded eviction helpers
internal class PendingImports(
    private val stagingRoot: () -> File,
    private val scope: () -> CoroutineScope,
    private val io: () -> CoroutineDispatcher,
    private val clock: () -> Long,
    private val importFileUri: suspend (String) -> String?,
) {
    private val entries = ConcurrentHashMap<String, Entry>()
    private val order = ConcurrentLinkedDeque<String>()

    private val closeHandler: Handler by lazy {
        val thread = HandlerThread("cassini-documents-close").apply {
            isDaemon = true
            start()
        }
        Handler(thread.looper)
    }

    /** Registers a new write-once pending document under the root directory and returns its `pending:<uuid>` id. */
    fun create(mimeType: String?, displayName: String?): String {
        val now = clock()
        sweep(now)
        val token = UUID.randomUUID().toString()
        val id = "$PREFIX$token"
        val cleanName = cleanDisplayName(displayName, mimeType)
        val cleanMime = mimeType?.trim()?.takeIf { it.isNotEmpty() && it != Document.MIME_TYPE_DIR } ?: DEFAULT_MIME
        entries[token] = Entry(token, id, cleanName, cleanMime, now)
        order.addLast(token)
        trimBounded()
        return id
    }

    fun isKnown(documentId: String): Boolean = tokenOf(documentId)?.let(entries::containsKey) == true

    /** Metadata before write starts; once written, awaits the import outcome and returns the resolved `noteId`. */
    suspend fun inspect(documentId: String): Inspection {
        val entry = entryOf(documentId) ?: throw FileNotFoundException("Unknown pending document: $documentId")
        if (entry.stage.get() is Stage.Writing) {
            flushCloseQueue()
        }
        return when (val stage = entry.stage.get()) {
            Stage.AwaitingWrite -> Inspection.Pending(
                ExposedDocument(
                    documentId = entry.documentId,
                    displayName = entry.displayName,
                    mimeType = entry.mimeType,
                    size = 0L,
                    lastModified = entry.createdAt,
                    flags = Document.FLAG_SUPPORTS_WRITE,
                ),
            )
            is Stage.Writing -> Inspection.Pending(
                ExposedDocument(
                    documentId = entry.documentId,
                    displayName = entry.displayName,
                    mimeType = entry.mimeType,
                    size = null,
                    lastModified = entry.createdAt,
                    flags = 0,
                ),
            )
            is Stage.Importing -> Inspection.Resolved(withContext(io()) { entry.completed.await() })
            is Stage.Finished -> Inspection.Resolved(stage.noteId)
        }
    }

    /** Opens the write-once staging descriptor for an external writer. */
    fun openForWrite(documentId: String, parsedMode: Int, signal: CancellationSignal?): ParcelFileDescriptor {
        val entry = entryOf(documentId) ?: throw FileNotFoundException("Unknown pending document: $documentId")
        signal?.throwIfCanceled()
        val folder = prepareStagingFolder(entry, documentId)
        val file = File(folder, entry.displayName)
        signal?.setOnCancelListener {
            entry.cancelled.set(true)
        }
        val openMode = writeOpenMode(parsedMode)
        return try {
            ParcelFileDescriptor.open(file, openMode, closeHandler) { closeError ->
                onWriterClosed(entry, folder, file, closeError)
            }
        } catch (error: IOException) {
            folder.deleteRecursively()
            entry.finish(null)
            throw FileNotFoundException(error.message ?: "Cannot open staging file").apply { initCause(error) }
        }
    }

    private fun prepareStagingFolder(entry: Entry, documentId: String): File {
        val folder = File(stagingRoot(), entry.token)
        if (!entry.stage.compareAndSet(Stage.AwaitingWrite, Stage.Writing(folder))) {
            throw SecurityException("Document $documentId is not writable")
        }
        if (!folder.mkdirs() && !folder.isDirectory) {
            entry.finish(null)
            throw FileNotFoundException("Cannot create staging folder")
        }
        return folder
    }

    /** Awaits a completed write-and-import and returns the imported `noteId`, or throws if unwritten or rejected. */
    suspend fun awaitImportedNoteId(documentId: String): String {
        val ready = entryOf(documentId)?.takeUnless { entry -> entry.stage.get() == Stage.AwaitingWrite }
            ?: throw FileNotFoundException("Document $documentId is unavailable")
        return withContext(io()) { ready.completed.await() }
            ?: throw FileNotFoundException("Import of $documentId was rejected")
    }

    private suspend fun flushCloseQueue() {
        val gate = CompletableDeferred<Unit>()
        val posted = closeHandler.post {
            if (!closeHandler.post { gate.complete(Unit) }) {
                gate.complete(Unit)
            }
        }
        if (posted) {
            withContext(io()) { gate.await() }
        }
    }

    private fun onWriterClosed(entry: Entry, folder: File, file: File, closeError: IOException?) {
        if (closeError != null || entry.cancelled.get()) {
            folder.deleteRecursively()
            entry.finish(null)
            return
        }
        entry.stage.set(Stage.Importing(folder))
        scope().launch(io()) {
            var importedNoteId: String? = null
            try {
                importedNoteId = importFileUri(Uri.fromFile(file).toString())
            } finally {
                withContext(NonCancellable) {
                    folder.deleteRecursively()
                    entry.finish(importedNoteId)
                }
            }
        }
    }

    private fun writeOpenMode(parsedMode: Int): Int {
        val base = if ((parsedMode and ParcelFileDescriptor.MODE_READ_WRITE) == ParcelFileDescriptor.MODE_READ_WRITE) {
            ParcelFileDescriptor.MODE_READ_WRITE
        } else {
            ParcelFileDescriptor.MODE_WRITE_ONLY
        }
        return base or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
    }

    private fun entryOf(documentId: String): Entry? = tokenOf(documentId)?.let(entries::get)

    private fun tokenOf(documentId: String): String? {
        if (!documentId.startsWith(PREFIX)) return null
        val token = documentId.removePrefix(PREFIX)
        return token.takeIf { token.isNotEmpty() && '/' !in token && '\\' !in token && ".." !in token }
    }

    private fun cleanDisplayName(rawName: String?, mimeType: String?): String {
        val trimmed = rawName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
        val dot = trimmed.lastIndexOf('.')
        val rawExt = if (dot > 0 && dot < trimmed.lastIndex) trimmed.substring(dot + 1) else ""
        val ext = rawExt.lowercase(Locale.ROOT).takeIf { VALID_EXT.matches(it) } ?: defaultExtension(mimeType)
        val title = if (dot > 0) trimmed.substring(0, dot) else trimmed
        return ExportNames.file(title, ext)
    }

    private fun defaultExtension(mimeType: String?): String = when (mimeType?.lowercase(Locale.ROOT)) {
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/m4a", "audio/aac" -> "m4a"
        "audio/flac", "audio/x-flac" -> "flac"
        else -> "opus"
    }

    private fun sweep(now: Long) {
        val cutoff = now - STALE_MS
        val activeFolders = HashSet<String>()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val current = iterator.next().value
            when (val stage = current.stage.get()) {
                is Stage.Writing -> activeFolders += stage.folder.name
                is Stage.Importing -> activeFolders += stage.folder.name
                Stage.AwaitingWrite, is Stage.Finished -> if (current.createdAt < cutoff) iterator.remove()
            }
        }
        val root = stagingRoot()
        if (root.isDirectory) {
            root.listFiles().orEmpty()
                .filter { it.name !in activeFolders && it.lastModified() < cutoff }
                .forEach(File::deleteRecursively)
        }
    }

    private fun trimBounded() {
        while (order.size > MAX_ENTRIES) {
            val oldest = order.pollFirst() ?: break
            val stage = entries[oldest]?.stage?.get()
            if (stage == Stage.AwaitingWrite || stage is Stage.Finished) {
                entries.remove(oldest)
            }
        }
    }

    sealed interface Inspection {
        data class Pending(val document: ExposedDocument) : Inspection
        data class Resolved(val noteId: String?) : Inspection
    }

    private sealed interface Stage {
        data object AwaitingWrite : Stage
        class Writing(val folder: File) : Stage
        class Importing(val folder: File) : Stage
        class Finished(val noteId: String?) : Stage
    }

    private class Entry(
        val token: String,
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val createdAt: Long,
    ) {
        val stage = AtomicReference<Stage>(Stage.AwaitingWrite)
        val cancelled = AtomicBoolean(false)
        val completed = CompletableDeferred<String?>()

        fun finish(noteId: String?) {
            stage.set(Stage.Finished(noteId))
            completed.complete(noteId)
        }
    }

    companion object {
        const val PREFIX = "pending:"
        const val STAGING_DIR = "provider-imports"
        private const val DEFAULT_MIME = "audio/ogg"
        private const val MAX_ENTRIES = 128
        private const val STALE_MS = 60L * 60L * 1000L
        private val VALID_EXT = Regex("[a-z0-9]{1,5}")
    }
}
