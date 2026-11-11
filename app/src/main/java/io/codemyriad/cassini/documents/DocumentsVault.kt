package io.codemyriad.cassini.documents

import android.content.ContentResolver
import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
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
import io.codemyriad.cassini.data.notes.AudioProbe
import io.codemyriad.cassini.inAppLanguage
import io.codemyriad.cassini.note.NoteExports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
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
                val precheckJob = catalogue.database.jobs().job(noteId)?.state
                if (precheck != null && needsRetitleBeforeExport(precheck, precheckJob, graph.paths)) {
                    catalogue.documents.retitle(noteId)
                }
                graph.locks.withNoteLock(noteId, graph.dispatchers.io) {
                    signal?.throwIfCanceled()
                    val row = catalogue.database.notes().noteBlocking(noteId)
                    val jobState = catalogue.database.jobs().jobBlocking(noteId)?.state
                    val audio = row?.let { exposableAudioFile(it, jobState, graph.paths) }
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
            if (shouldFlush && needsRetitleBeforeExport(note, initialJobs[note.id], paths)) {
                catalogue.documents.retitle(note.id)
            }
        }
        val jobsByNote = catalogue.database.jobs().all().associate { it.noteId to it.state }
        val exposableFiles = HashMap<String, File>()
        val exposable = catalogue.database.notes().all().filter { note ->
            val file = exposableAudioFile(note, jobsByNote[note.id], paths)
            if (file != null) exposableFiles[note.id] = file
            file != null
        }
        val names = assignDisplayNames(exposable)
        return exposable
            .sortedWith(compareByDescending<NoteEntity> { it.createdAt }.thenBy { it.id })
            .mapNotNull { note ->
                val file = exposableFiles[note.id] ?: return@mapNotNull null
                ExposedDocument(
                    documentId = "$NOTE_PREFIX${note.id}",
                    displayName = names.getValue(note.id),
                    mimeType = mimeOf(note, file),
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

    @Suppress("TooManyFunctions") // containment, symlink rejection, collision naming, and plain-audio MIME resolution
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
        private const val AUDIO_MIME_PREFIX = "audio/"
        private const val FALLBACK_MIME = "application/octet-stream"
        private const val HEADER_SNIFF_BYTES = 64
        private const val FTYP_OFFSET = 4
        private const val BRAND_OFFSET = 8
        private const val WAVE_TAG_OFFSET = 8
        private const val EBML_MAGIC_LEN = 4
        private const val EBML_LAST_INDEX = 3
        private const val EBML_B0 = 0x1A
        private const val EBML_B1 = 0x45
        private const val EBML_B2 = 0xDF
        private const val EBML_B3 = 0xA3
        private const val BYTE_MASK = 0xFF
        private const val SYNC_BYTE_FF = 0xFF
        private const val ADTS_MASK = 0xF6
        private const val ADTS_SYNC = 0xF0
        private const val MPEG_MASK = 0xE0
        private const val MPEG_SYNC = 0xE0
        private const val MPEG_LAYER_MASK = 0x06

        private val PLAIN_AUDIO_MIMES = mapOf(
            "opus" to "audio/ogg",
            "ogg" to "audio/ogg",
            "oga" to "audio/ogg",
            "spx" to "audio/ogg",
            "wav" to "audio/wav",
            "wave" to "audio/wav",
            "mp3" to "audio/mpeg",
            "mp2" to "audio/mpeg",
            "mpga" to "audio/mpeg",
            "mpega" to "audio/mpeg",
            "m4a" to "audio/mp4",
            "mp4" to "audio/mp4",
            "m4b" to "audio/mp4",
            "aac" to "audio/aac",
            "adts" to "audio/aac",
            "flac" to "audio/flac",
            "amr" to "audio/amr",
            "awb" to "audio/amr-wb",
            "3gp" to "audio/3gpp",
            "3gpp" to "audio/3gpp",
            "3ga" to "audio/3gpp",
            "3g2" to "audio/3gpp2",
            "3gp2" to "audio/3gpp2",
            "webm" to "audio/webm",
            "mka" to "audio/x-matroska",
            "mid" to "audio/midi",
            "midi" to "audio/midi",
            "kar" to "audio/midi",
            "rmi" to "audio/midi",
            "imy" to "audio/imelody",
            "xmf" to "audio/mobile-xmf",
            "mxmf" to "audio/mobile-xmf",
            "aif" to "audio/x-aiff",
            "aiff" to "audio/x-aiff",
            "aifc" to "audio/x-aiff",
            "caf" to "audio/x-caf",
            "wma" to "audio/x-ms-wma",
        )

        private val HEADER_PREFIX_MIMES = listOf(
            "#!AMR-WB" to "audio/amr-wb",
            "#!AMR" to "audio/amr",
            "fLaC" to "audio/flac",
            "OggS" to "audio/ogg",
            "MThd" to "audio/midi",
            "ID3" to "audio/mpeg",
        )

        private val TRACK_CONTAINER_MIMES = mapOf(
            "audio/mp4a-latm" to "audio/mp4",
            "audio/vorbis" to "audio/ogg",
            "audio/opus" to "audio/ogg",
        )

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

        internal fun isExposable(note: NoteEntity, jobState: JobState?, paths: AppPaths): Boolean =
            exposableAudioFile(note, jobState, paths) != null

        internal fun exposableAudioFile(note: NoteEntity, jobState: JobState?, paths: AppPaths): File? {
            val finishedKind = note.audioKind == AudioKind.DOCUMENT || note.audioKind == AudioKind.PLAIN
            val eligible = NoteIds.isValid(note.id) && note.trashedAt == null && finishedKind &&
                !isSealingOrUnsafeWork(note.id, jobState, paths)
            return if (eligible) containedAudioFile(note, paths) else null
        }

        internal fun containedAudioFile(note: NoteEntity, paths: AppPaths): File? {
            if (!NoteIds.isValid(note.id) || !isSafeAudioName(note.audioFile)) return null
            return try {
                resolveContainedFile(paths.notes.toPath(), note.id, note.audioFile)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }

        private fun resolveContainedFile(notesRoot: Path, noteId: String, fileName: String): File? {
            val realNotesRoot = notesRoot.takeIf(::isRealDirectory)?.toRealPath()
            val realNoteDir = realNotesRoot?.resolve(noteId)?.takeIf(::isRealDirectory)
                ?.toRealPath(LinkOption.NOFOLLOW_LINKS)
                ?.takeIf { it.parent == realNotesRoot && it.fileName?.toString() == noteId }
            val realAudio = realNoteDir?.resolve(fileName)?.takeIf(::isRealRegularFile)
                ?.toRealPath(LinkOption.NOFOLLOW_LINKS)
                ?.takeIf { it.parent == realNoteDir && it.fileName?.toString() == fileName }
            return realAudio?.toFile()
        }

        private fun isSealingOrUnsafeWork(noteId: String, jobState: JobState?, paths: AppPaths): Boolean {
            if (jobState == JobState.RUNNING) return true
            val workDir = paths.work(noteId).toPath()
            val unsafeWorkDir = Files.exists(workDir, LinkOption.NOFOLLOW_LINKS) && !isRealDirectory(workDir)
            return unsafeWorkDir ||
                Files.exists(workDir.resolve(SEALED_MARKER), LinkOption.NOFOLLOW_LINKS) ||
                Files.exists(workDir.resolve(SEALED_OUTPUT), LinkOption.NOFOLLOW_LINKS)
        }

        private fun isRealDirectory(path: Path): Boolean =
            !Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)

        private fun isRealRegularFile(path: Path): Boolean =
            !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)

        private fun isSafeAudioName(name: String): Boolean =
            name.isNotEmpty() && '/' !in name && '\\' !in name &&
                name != "." && name != ".." && !name.endsWith(TMP_SUFFIX)

        private fun needsRetitleBeforeExport(note: NoteEntity, jobState: JobState?, paths: AppPaths): Boolean =
            isExposable(note, jobState, paths) && note.titleDirty && note.audioKind == AudioKind.DOCUMENT &&
                note.documentState == DocumentState.OK && jobState == null

        private fun extensionOf(note: NoteEntity): String =
            if (note.audioKind == AudioKind.DOCUMENT) {
                "opus"
            } else {
                note.audioFile.substringAfterLast('.', "opus").lowercase(Locale.ROOT)
            }

        private fun mimeOf(note: NoteEntity, file: File): String =
            if (note.audioKind == AudioKind.DOCUMENT) {
                NoteExports.MIME_DOCUMENT
            } else {
                plainAudioMime(extensionOf(note), file)
            }

        internal fun plainAudioMime(ext: String, file: File): String {
            val fromExtension = PLAIN_AUDIO_MIMES[ext]
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    ?.takeIf { it.startsWith(AUDIO_MIME_PREFIX) }
            return fromExtension
                ?: sniffAudioContainerMime(file)
                ?: probedContainerMime(file)
                ?: FALLBACK_MIME
        }

        private fun sniffAudioContainerMime(file: File): String? {
            val head = try {
                file.inputStream().use { input -> readPrefix(input, HEADER_SNIFF_BYTES) }
            } catch (_: IOException) {
                return null
            }
            return mimeFromHeader(head)
        }

        private fun readPrefix(input: java.io.InputStream, maxBytes: Int): ByteArray {
            val buffer = ByteArray(maxBytes)
            var offset = 0
            while (offset < maxBytes) {
                val read = input.read(buffer, offset, maxBytes - offset)
                if (read < 0) break
                offset += read
            }
            return buffer.copyOf(offset)
        }

        private fun mimeFromHeader(head: ByteArray): String? =
            HEADER_PREFIX_MIMES.firstOrNull { (magic, _) -> hasAsciiAt(head, 0, magic) }?.second
                ?: mimeFromContainerBox(head)

        private fun mimeFromContainerBox(head: ByteArray): String? = when {
            hasAsciiAt(head, 0, "RIFF") && hasAsciiAt(head, WAVE_TAG_OFFSET, "WAVE") -> "audio/wav"
            hasAsciiAt(head, FTYP_OFFSET, "ftyp") -> isoBoxAudioMime(head)
            isEbmlHeader(head) -> ebmlAudioMime(head)
            else -> frameSyncAudioMime(head)
        }

        private fun isoBoxAudioMime(head: ByteArray): String = when {
            hasAsciiAt(head, BRAND_OFFSET, "3gp") -> "audio/3gpp"
            hasAsciiAt(head, BRAND_OFFSET, "3g2") -> "audio/3gpp2"
            else -> "audio/mp4"
        }

        private fun isEbmlHeader(head: ByteArray): Boolean =
            head.size >= EBML_MAGIC_LEN &&
                (head[0].toInt() and BYTE_MASK) == EBML_B0 &&
                (head[1].toInt() and BYTE_MASK) == EBML_B1 &&
                (head[2].toInt() and BYTE_MASK) == EBML_B2 &&
                (head[EBML_LAST_INDEX].toInt() and BYTE_MASK) == EBML_B3

        private fun ebmlAudioMime(head: ByteArray): String =
            if (String(head, Charsets.ISO_8859_1).lowercase(Locale.ROOT).contains("webm")) {
                "audio/webm"
            } else {
                "audio/x-matroska"
            }

        private fun frameSyncAudioMime(head: ByteArray): String? {
            if (head.size < 2 || (head[0].toInt() and BYTE_MASK) != SYNC_BYTE_FF) return null
            val second = head[1].toInt() and BYTE_MASK
            return when {
                (second and ADTS_MASK) == ADTS_SYNC -> "audio/aac"
                (second and MPEG_MASK) == MPEG_SYNC && (second and MPEG_LAYER_MASK) != 0 -> "audio/mpeg"
                else -> null
            }
        }

        private fun probedContainerMime(file: File): String? {
            val trackMime = AudioProbe.Extractor.probe(file)?.mime?.lowercase(Locale.ROOT) ?: return null
            return TRACK_CONTAINER_MIMES[trackMime] ?: trackMime.takeIf { it.startsWith(AUDIO_MIME_PREFIX) }
        }

        private fun hasAsciiAt(bytes: ByteArray, offset: Int, ascii: String): Boolean {
            if (offset < 0 || offset + ascii.length > bytes.size) return false
            return ascii.indices.all { idx -> bytes[offset + idx] == ascii[idx].code.toByte() }
        }

        private fun noteIdOf(documentId: String): String? {
            if (!documentId.startsWith(NOTE_PREFIX)) return null
            return documentId.removePrefix(NOTE_PREFIX).takeIf(NoteIds::isValid)
        }
    }
}
