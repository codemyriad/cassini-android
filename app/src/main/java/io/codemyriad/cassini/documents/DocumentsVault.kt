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
import io.codemyriad.cassini.data.notes.ImportOutcome
import io.codemyriad.cassini.inAppLanguage
import io.codemyriad.cassini.note.NoteExports
import io.codemyriad.cassini.note.NoteMapping
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

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
 * Storage and catalogue operations behind [CassiniDocumentsProvider].
 *
 * Listing and opening run strictly off the main thread, read only the lightweight `notes` and `jobs` tables (never
 * loading transcript words or whole files into memory), exclude notes that are trashed or still being recorded or
 * sealed, open note files with `MODE_READ_ONLY` under [AppGraph.locks], and route newly created files through
 * `NoticeCenter.imports` (`ImportRepository` with SHA-256 deduplication).
 */
@Suppress("TooManyFunctions") // read, create, open, dirty-title flush and invalidation helpers of D97
internal class DocumentsVault(
    private val context: Context,
    private val authority: () -> String,
    private val graphProvider: () -> AppGraph,
) {
    private val observedCatalogues: MutableSet<Catalogue> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    private val pending = PendingImports(
        stagingRoot = { File(graphProvider().paths.cache, PendingImports.STAGING_DIR) },
        scope = { graphProvider().appScope },
        io = { graphProvider().dispatchers.io },
        clock = { graphProvider().config.clock() },
        importFileUri = ::importStagedFile,
    )

    fun rootTitle(): String = context.inAppLanguage().getString(R.string.app_name)

    fun rootDocument(): ExposedDocument = ExposedDocument(
        documentId = ROOT_DOCUMENT_ID,
        displayName = rootTitle(),
        mimeType = Document.MIME_TYPE_DIR,
        size = null,
        lastModified = null,
        flags = Document.FLAG_DIR_SUPPORTS_CREATE,
    )

    fun isKnownChildId(documentId: String?): Boolean {
        if (documentId == null) return false
        return noteIdOf(documentId) != null || pending.isKnown(documentId)
    }

    /** Lists live, finished, sealed-or-plain notes newest-first without loading transcript bodies. */
    fun listChildren(): List<ExposedDocument> {
        Threads.assertOffMain("CassiniDocumentsProvider.queryChildDocuments")
        val graph = graphProvider()
        return runBlocking(graph.dispatchers.io) {
            val catalogue = graph.awaitCatalogue()
            observeChangesOnce(graph, catalogue)
            exposableDocuments(catalogue, graph.paths)
        }
    }

    /** Metadata for `"root"`, `"note:<uuid>"`, or `"pending:<uuid>"`. */
    fun queryDocument(documentId: String?): ExposedDocument {
        val id = documentId ?: throw FileNotFoundException("Missing documentId")
        if (id == ROOT_DOCUMENT_ID) return rootDocument()
        Threads.assertOffMain("CassiniDocumentsProvider.queryDocument")
        val graph = graphProvider()
        return runBlocking(graph.dispatchers.io) {
            when {
                id.startsWith(NOTE_PREFIX) -> queryNoteDocument(graph, id)
                id.startsWith(PendingImports.PREFIX) -> queryPendingDocument(graph, id)
                else -> throw FileNotFoundException("Unknown document: $id")
            }
        }
    }

    private suspend fun queryPendingDocument(graph: AppGraph, documentId: String): ExposedDocument =
        when (val result = pending.inspect(documentId)) {
            is PendingImports.Inspection.Pending -> result.document
            is PendingImports.Inspection.Resolved -> {
                val noteId = result.noteId ?: throw FileNotFoundException("Import rejected: $documentId")
                queryNoteDocument(graph, "$NOTE_PREFIX$noteId")
            }
        }

    /** Allocates a write-once `pending:<uuid>` target under `"root"`. */
    fun createDocument(parentDocumentId: String?, mimeType: String?, displayName: String?): String {
        if (parentDocumentId != ROOT_DOCUMENT_ID) {
            throw FileNotFoundException("Cannot create documents under $parentDocumentId")
        }
        if (mimeType == Document.MIME_TYPE_DIR) {
            throw UnsupportedOperationException("Folders are not supported")
        }
        return pending.create(mimeType, displayName)
    }

    /** Opens an existing note read-only, or streams a newly created document into `ImportRepository`. */
    fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        Threads.assertOffMain("CassiniDocumentsProvider.openDocument")
        val id = documentId ?: throw FileNotFoundException("Missing documentId")
        val parsedMode = parseRequiredMode(mode)
        return when {
            id.startsWith(NOTE_PREFIX) -> openExistingNote(id, parsedMode, signal)
            id.startsWith(PendingImports.PREFIX) -> openPending(id, parsedMode, signal)
            else -> throw FileNotFoundException("Unknown document: $id")
        }
    }

    private fun parseRequiredMode(mode: String?): Int =
        ParcelFileDescriptor.parseMode(mode ?: throw IllegalArgumentException("Missing mode"))

    private fun openExistingNote(
        documentId: String,
        parsedMode: Int,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (parsedMode != ParcelFileDescriptor.MODE_READ_ONLY) {
            throw SecurityException("Cassini notes are read-only")
        }
        val noteId = noteIdOf(documentId) ?: throw FileNotFoundException("Unknown document: $documentId")
        return openNoteReadOnly(noteId, signal)
    }

    private fun openPending(
        documentId: String,
        parsedMode: Int,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (isWriteMode(parsedMode)) {
            return pending.openForWrite(documentId, parsedMode, signal)
        }
        if (parsedMode != ParcelFileDescriptor.MODE_READ_ONLY) {
            throw SecurityException("Unsupported mode for $documentId")
        }
        val graph = graphProvider()
        val noteId = runBlocking(graph.dispatchers.io) { pending.awaitImportedNoteId(documentId) }
        return openNoteReadOnly(noteId, signal)
    }

    private fun openNoteReadOnly(noteId: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val graph = graphProvider()
        return runBlocking(graph.dispatchers.io) {
            val catalogue = graph.awaitCatalogue()
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
    }

    private suspend fun queryNoteDocument(graph: AppGraph, documentId: String): ExposedDocument {
        val noteId = noteIdOf(documentId) ?: throw FileNotFoundException("Unknown document: $documentId")
        val catalogue = graph.awaitCatalogue()
        observeChangesOnce(graph, catalogue)
        return exposableDocuments(catalogue, graph.paths).firstOrNull { it.documentId == "$NOTE_PREFIX$noteId" }
            ?: throw FileNotFoundException("Note is not available: $documentId")
    }

    private suspend fun exposableDocuments(catalogue: Catalogue, paths: AppPaths): List<ExposedDocument> {
        val initialJobs = catalogue.database.jobs().all().associate { it.noteId to it.state }
        for (note in catalogue.database.notes().all()) {
            if (needsRetitleBeforeExport(note, initialJobs[note.id])) {
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
                val file = audioFileOf(note, paths)?.takeIf(File::isFile) ?: return@mapNotNull null
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

    private suspend fun importStagedFile(fileUri: String): String? {
        val graph = graphProvider()
        graph.ready.await()
        val outcome = graph.notices.imports.start(fileUri).await()
        notifyChildrenChanged(context.contentResolver)
        return when (outcome) {
            is ImportOutcome.Created -> outcome.noteId
            is ImportOutcome.Existing -> outcome.noteId
            is ImportOutcome.Rejected, null -> null
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
            return note.trashedAt == null && finishedKind && !sealing && audioFileOf(note, paths)?.isFile == true
        }

        internal fun audioFileOf(note: NoteEntity, paths: AppPaths): File? {
            val name = note.audioFile
            val safeName = NoteIds.isValid(note.id) && name.isNotEmpty() && '/' !in name && '\\' !in name &&
                name != "." && name != ".." && !name.endsWith(TMP_SUFFIX)
            if (!safeName) return null
            val dir = paths.note(note.id)
            return File(dir, name).takeIf { it.parentFile == dir }
        }

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

        private fun isWriteMode(parsedMode: Int): Boolean =
            (parsedMode and ParcelFileDescriptor.MODE_WRITE_ONLY) != 0
    }
}
