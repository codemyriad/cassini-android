package io.codemyriad.cassini.documents

import android.content.ContentResolver
import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.room.InvalidationTracker
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.Catalogue
import io.codemyriad.cassini.R
import io.codemyriad.cassini.core.export.ExportNames
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.NoteIds
import io.codemyriad.cassini.data.Threads
import io.codemyriad.cassini.data.db.NoteEntity
import io.codemyriad.cassini.inAppLanguage
import io.codemyriad.cassini.note.NoteExports
import io.codemyriad.cassini.note.NoteMapping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Metadata of one document exposed by [CassiniDocumentsProvider]. */
internal data class ExposedDocument(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val size: Long?,
    val lastModified: Long?,
    val flags: Int,
)

/**
 * Read-only storage and catalogue operations behind [CassiniDocumentsProvider].
 *
 * Listing, child checks, metadata queries, and opens run strictly off the main thread, wait for [AppGraph.ready]
 * before reading the catalogue, read only the lightweight `notes` and `jobs` tables (never loading transcript words or
 * whole files into memory), exclude notes that are trashed or still being recorded or sealed, and open note files with
 * `MODE_READ_ONLY` under [AppGraph.locks]. All write, append, and read-write modes are rejected immediately.
 */
@Suppress("TooManyFunctions") // read-only query, open, dirty-title flush, and invalidation helpers of D97
internal class DocumentsVault(
    private val context: Context,
    private val authority: () -> String,
    private val graphProvider: () -> AppGraph,
) {
    private val observedCatalogues: MutableSet<Catalogue> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    fun rootTitle(): String = context.inAppLanguage().getString(R.string.app_name)

    fun rootDocument(): ExposedDocument = ExposedDocument(
        documentId = ROOT_DOCUMENT_ID,
        displayName = rootTitle(),
        mimeType = Document.MIME_TYPE_DIR,
        size = null,
        lastModified = null,
        flags = 0,
    )

    fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        Threads.assertOffMain("CassiniDocumentsProvider.isChildDocument")
        val noteId = documentId?.takeIf { parentDocumentId == ROOT_DOCUMENT_ID }?.let(::noteIdOf) ?: return false
        val graph = graphProvider()
        return runBlocking(graph.dispatchers.io) {
            val catalogue = awaitReadyCatalogue(graph)
            observeChangesOnce(graph, catalogue)
            val row = catalogue.database.notes().note(noteId)
            val jobState = catalogue.database.jobs().job(noteId)?.state
            row != null && isExposable(row, jobState, graph.paths)
        }
    }

    /** Lists live, finished, sealed-or-plain notes newest-first without loading transcript words. */
    fun listChildren(parentDocumentId: String?): List<ExposedDocument> {
        Threads.assertOffMain("CassiniDocumentsProvider.queryChildDocuments")
        if (parentDocumentId != ROOT_DOCUMENT_ID) {
            throw FileNotFoundException("Unknown parent document: $parentDocumentId")
        }
        val graph = graphProvider()
        return runBlocking(graph.dispatchers.io) {
            val catalogue = awaitReadyCatalogue(graph)
            observeChangesOnce(graph, catalogue)
            exposableDocuments(catalogue, graph.paths, flushDirtyNoteId = ALL_DIRTY_NOTES)
        }
    }

    /** Metadata for `"root"` or `"note:<uuid>"`. */
    fun queryDocument(documentId: String?): ExposedDocument {
        val id = documentId ?: throw FileNotFoundException("Missing documentId")
        if (id == ROOT_DOCUMENT_ID) return rootDocument()
        Threads.assertOffMain("CassiniDocumentsProvider.queryDocument")
        val noteId = noteIdOf(id) ?: throw FileNotFoundException("Unknown document: $id")
        val graph = graphProvider()
        return runBlocking(graph.dispatchers.io) {
            queryNoteDocument(graph, noteId, id)
        }
    }

    /** Opens an existing note strictly read-only (`"r"`). */
    fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        Threads.assertOffMain("CassiniDocumentsProvider.openDocument")
        val id = documentId ?: throw FileNotFoundException("Missing documentId")
        requireReadOnlyMode(mode)
        val noteId = noteIdOf(id) ?: throw FileNotFoundException("Unknown document: $id")
        return openNoteReadOnly(noteId, signal)
    }

    private fun requireReadOnlyMode(mode: String?) {
        val raw = mode ?: throw IllegalArgumentException("Missing mode")
        val parsed = ParcelFileDescriptor.parseMode(raw)
        if (raw != MODE_READ || parsed != ParcelFileDescriptor.MODE_READ_ONLY) {
            throw SecurityException("CassiniDocumentsProvider is read-only (mode=$raw)")
        }
    }

    private suspend fun queryNoteDocument(graph: AppGraph, noteId: String, documentId: String): ExposedDocument {
        val catalogue = awaitReadyCatalogue(graph)
        observeChangesOnce(graph, catalogue)
        val precheck = catalogue.database.notes().note(noteId)
        val initialJob = catalogue.database.jobs().job(noteId)?.state
        if (precheck == null || !isExposable(precheck, initialJob, graph.paths)) {
            throw FileNotFoundException("Note is not available: $documentId")
        }
        return exposableDocuments(catalogue, graph.paths, flushDirtyNoteId = noteId)
            .firstOrNull { it.documentId == "$NOTE_PREFIX$noteId" }
            ?: throw FileNotFoundException("Note is not available: $documentId")
    }

    private fun openNoteReadOnly(noteId: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val graph = graphProvider()
        return try {
            runBlocking(graph.dispatchers.io) {
                val catalogue = awaitReadyCatalogue(graph, signal)
                observeChangesOnce(graph, catalogue)
                val precheck = catalogue.database.notes().note(noteId)
                if (precheck != null && needsRetitleBeforeExport(precheck, catalogue.database.jobs().job(noteId)?.state)) {
                    catalogue.documents.retitle(noteId)
                }
                graph.locks.withNoteLock(noteId, graph.dispatchers.io) {
                    signal?.throwIfCanceled()
                    val row = catalogue.database.notes().noteBlocking(noteId)
                    val jobState = catalogue.database.jobs().jobBlocking(noteId)?.state
                    val audio = row?.takeIf { isExposable(it, jobState, graph.paths) }?.let { audioFileOf(it, graph.paths) }
                        ?: throw FileNotFoundException("Note is not available: $noteId")
                    ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            }
        } catch (cancelled: CancellationException) {
            signal?.throwIfCanceled()
            throw cancelled
        }
    }

    private suspend fun awaitReadyCatalogue(graph: AppGraph, signal: CancellationSignal? = null): Catalogue {
        if (signal == null) {
            onAwaitingReadyForTesting?.invoke()
            graph.ready.await()
        } else {
            signal.throwIfCanceled()
            try {
                suspendCancellableCoroutine { cont ->
                    signal.setOnCancelListener { cont.cancel() }
                    val handle = graph.ready.invokeOnCompletion { error ->
                        if (cont.isActive) {
                            if (error != null) cont.resumeWithException(error) else cont.resume(Unit)
                        }
                    }
                    cont.invokeOnCancellation { handle.dispose() }
                    onAwaitingReadyForTesting?.invoke()
                }
            } finally {
                signal.setOnCancelListener(null)
            }
        }
        return graph.awaitCatalogue()
    }

    private suspend fun exposableDocuments(
        catalogue: Catalogue,
        paths: AppPaths,
        flushDirtyNoteId: String?,
    ): List<ExposedDocument> {
        val initialJobs = catalogue.database.jobs().all().associate { it.noteId to it.state }
        for (note in catalogue.database.notes().all()) {
            val shouldFlush = flushDirtyNoteId == ALL_DIRTY_NOTES || flushDirtyNoteId == note.id
            if (shouldFlush && needsRetitleBeforeExport(note, initialJobs[note.id])) {
                catalogue.documents.retitle(note.id)
            }
        }
        val jobsByNote = catalogue.database.jobs().all().associate { it.noteId to it.state }
        val exposable = catalogue.database.notes().all().filter { note ->
            isExposable(note, jobsByNote[note.id], paths)
        }
        val names = assignDisplayNames(exposable)
        return exposable
            .sortedWith(compareByDescending<NoteEntity> { it.createdAt }.thenBy { it.id })
            .mapNotNull { note ->
                val file = audioFileOf(note, paths)?.takeIf(::isRegularFileNoFollow) ?: return@mapNotNull null
                ExposedDocument(
                    documentId = "$NOTE_PREFIX${note.id}",
                    displayName = names.getValue(note.id),
                    mimeType = mimeOf(note),
                    size = file.length(),
                    lastModified = note.updatedAt,
                    flags = 0,
                )
            }
    }

    private fun observeChangesOnce(graph: AppGraph, catalogue: Catalogue) {
        if (!observedCatalogues.add(catalogue)) return
        val resolver = context.contentResolver
        val observer = object : InvalidationTracker.Observer("notes", "jobs") {
            override fun onInvalidated(tables: Set<String>) {
                notifyChildrenChanged(resolver)
            }
        }
        catalogue.database.invalidationTracker.addObserver(observer)
        graph.appScope.coroutineContext.job.invokeOnCompletion {
            catalogue.database.invalidationTracker.removeObserver(observer)
            observedCatalogues.remove(catalogue)
        }
    }

    private fun notifyChildrenChanged(resolver: ContentResolver) {
        val childrenUri = DocumentsContract.buildChildDocumentsUri(authority(), ROOT_DOCUMENT_ID)
        resolver.notifyChange(childrenUri, null)
    }

    companion object {
        const val ROOT_ID = "notes"
        const val ROOT_DOCUMENT_ID = "root"
        const val NOTE_PREFIX = "note:"

        @Volatile
        internal var onAwaitingReadyForTesting: (() -> Unit)? = null

        private const val MODE_READ = "r"
        private const val ALL_DIRTY_NOTES = "*"
        private const val SEALED_MARKER = "sealed"
        private const val SEALED_OUTPUT = "sealed.opus"
        private const val TMP_SUFFIX = ".tmp"

        /**
         * Deterministic export names across [notes] in O(N) time: oldest notes claim the base `ExportNames.file` name
         * first, and later notes with colliding titles receive `Title (2).opus`, `Title (3).opus`, etc.
         */
        internal fun assignDisplayNames(
            notes: List<NoteEntity>,
            onProbe: (() -> Unit)? = null,
        ): Map<String, String> {
            val ordered = notes.sortedWith(compareBy<NoteEntity> { it.createdAt }.thenBy { it.id })
            val used = HashSet<String>(ordered.size * 2)
            val nextSuffix = HashMap<String, Int>()
            val result = HashMap<String, String>(ordered.size * 2)
            fun claim(key: String): Boolean {
                onProbe?.invoke()
                return used.add(key)
            }
            for (note in ordered) {
                val ext = extensionOf(note)
                val base = ExportNames.file(note.title, ext)
                val baseKey = base.lowercase(Locale.ROOT)
                val chosen = if (claim(baseKey)) {
                    base
                } else {
                    val stem = base.substringBeforeLast('.')
                    var index = nextSuffix.getOrDefault(baseKey, 2)
                    var candidate = "$stem ($index).$ext"
                    while (!claim(candidate.lowercase(Locale.ROOT))) {
                        index++
                        candidate = "$stem ($index).$ext"
                    }
                    nextSuffix[baseKey] = index + 1
                    candidate
                }
                result[note.id] = chosen
            }
            return result
        }

        internal fun isExposable(note: NoteEntity, jobState: JobState?, paths: AppPaths): Boolean {
            val finishedKind = note.audioKind == AudioKind.DOCUMENT || note.audioKind == AudioKind.PLAIN
            val work = paths.work(note.id)
            val sealing = jobState == JobState.RUNNING ||
                File(work, SEALED_MARKER).isFile ||
                File(work, SEALED_OUTPUT).isFile
            return note.trashedAt == null && finishedKind && !sealing &&
                audioFileOf(note, paths)?.let(::isRegularFileNoFollow) == true
        }

        internal fun audioFileOf(note: NoteEntity, paths: AppPaths): File? {
            val name = note.audioFile
            val safeName = NoteIds.isValid(note.id) && name.isNotEmpty() && '/' !in name && '\\' !in name &&
                name != "." && name != ".." && !name.endsWith(TMP_SUFFIX)
            if (!safeName) return null
            val dir = paths.note(note.id)
            return File(dir, name).takeIf { it.parentFile == dir }
        }

        private fun isRegularFileNoFollow(file: File): Boolean =
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

        private fun needsRetitleBeforeExport(note: NoteEntity, jobState: JobState?): Boolean =
            note.titleDirty && note.audioKind == AudioKind.DOCUMENT &&
                note.documentState == DocumentState.OK && jobState == null

        private fun extensionOf(note: NoteEntity): String =
            if (note.audioKind == AudioKind.DOCUMENT) {
                "opus"
            } else {
                note.audioFile.substringAfterLast('.', "opus").lowercase(Locale.ROOT)
            }

        private fun mimeOf(note: NoteEntity): String =
            if (note.audioKind == AudioKind.DOCUMENT) {
                NoteExports.MIME_DOCUMENT
            } else {
                NoteMapping.audioMime(extensionOf(note))
            }

        private fun noteIdOf(documentId: String): String? {
            if (!documentId.startsWith(NOTE_PREFIX)) return null
            return documentId.removePrefix(NOTE_PREFIX).takeIf(NoteIds::isValid)
        }
    }
}
