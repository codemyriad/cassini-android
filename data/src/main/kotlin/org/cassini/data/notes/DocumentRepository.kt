package org.cassini.data.notes

import android.content.ContentResolver
import android.net.Uri
import androidx.annotation.WorkerThread
import kotlinx.coroutines.withContext
import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.cassini.core.export.ExportNames
import org.cassini.core.export.ExportTranscript
import org.cassini.core.export.SrtWriter
import org.cassini.core.export.TextExport
import org.cassini.core.export.VttWriter
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.DocumentState
import org.cassini.core.format.SpeakerMerges
import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.cassini.core.library.AudioKind
import org.cassini.data.AppPaths
import org.cassini.data.CassiniDispatchers
import org.cassini.data.DocumentSignature
import org.cassini.data.NoteLocks
import org.cassini.data.Threads
import org.cassini.data.db.CassiniDatabase
import org.cassini.data.db.NoteCache
import org.cassini.data.db.NoteEntity
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.Callable

/** The transcript export formats of features.md section 6. */
enum class TranscriptFormat(val extension: String) { TXT("txt"), SRT("srt"), VTT("vtt") }

/**
 * One transcript export. [labels] are the display labels per speaker id, chosen by the caller (named labels verbatim,
 * placeholders localized); [subtitle] and [emptyText] are the localized lines of a TXT export. [variantId] null means
 * the selected variant.
 */
data class TranscriptExport(
    val format: TranscriptFormat,
    val labels: Map<String, String>,
    val subtitle: String = "",
    val emptyText: String = "",
    val variantId: String? = null,
)

/**
 * The sealed document of each note, `notes/<id>/document.opus` (docs/architecture.md, sections 3.1, 3.2 and 4.3), and
 * the cache rows derived from it.
 *
 * Every write under a note directory holds the note's lock from [NoteLocks]; Room work done under it uses the blocking
 * DAO calls, so nothing suspends while a lock is held. The document is authoritative for the transcript; the cache
 * rows (variants, speakers, words, searchable text, verification result) carry the `DocumentSignature` of the file they
 * came from, so a note whose file did not change opens without reading it (the FAST NOTE OPENING rule).
 */
// One repository per storage area keeps the note-directory rules in one place.
@Suppress("TooManyFunctions", "LongParameterList")
class DocumentRepository(
    private val database: CassiniDatabase,
    private val paths: AppPaths,
    private val dispatchers: CassiniDispatchers,
    private val locks: NoteLocks,
    private val resolver: ContentResolver,
    private val reader: DocumentReader = DocumentReader.Verified,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lru = DocumentLru()
    private val notes = database.notes()
    private val cache = database.cache()
    private val jobs = database.jobs()

    private fun documentFile(noteId: String) = File(paths.note(noteId), NoteFiles.DOCUMENT)

    /**
     * The note's document, read and verified (`readChecked` with the digest), or null without a document file. The
     * last [DocumentLru.CAPACITY] documents are kept by signature, so repeated reads of one file version are free.
     */
    suspend fun read(noteId: String): CassiniDocument? = locks.withNoteLock(noteId, dispatchers.io) {
        val file = documentFile(noteId)
        DocumentSignature.of(file)?.let { readLocked(file, it) }
    }

    /**
     * Brings the cache rows of a note in step with its document. When the file's signature equals
     * `notes.document_signature` and every readable variant has its words cached, nothing is read ([RefreshOutcome.Fresh]).
     * Otherwise the document is read once and every cache row is written in one transaction with the new signature.
     * `notes.title` is never overwritten; `title_dirty` is set when the document title differs.
     *
     * A `document.opus` is always the note's audio, so a row that still names a capture (a crash between the adopt
     * rename and the row update) becomes a DOCUMENT row here.
     */
    suspend fun refreshIfStale(noteId: String): RefreshOutcome =
        locks.withNoteLock(noteId, dispatchers.io) { refreshLocked(noteId) }

    /**
     * Adds the note [row] for the `document.opus` already in its directory (import and catalogue rebuild): the
     * document is read and verified once, and the row and every cache row are written in one transaction with its
     * signature, so the first open reads nothing. The title, the recording time and the meeting id come from the
     * manifest when it has them; otherwise [row]'s values stay, and a document without a manifest gets its duration
     * from its Ogg pages. [duplicateOf] runs inside that transaction first: when it returns a note id, nothing is
     * written and that id is returned. Returns null when the note was written.
     */
    internal suspend fun catalogueDocument(row: NoteEntity, duplicateOf: () -> String? = { null }): String? =
        locks.withNoteLock(row.id, dispatchers.io) {
            val file = documentFile(row.id)
            val signature = DocumentSignature.of(file)
            val document = if (signature == null) missing(file) else readLocked(file, signature)
            val described = describedBy(row, document, file)
            database.runInTransaction(
                Callable<String?> {
                    duplicateOf() ?: run {
                        cache.replaceCacheBlocking(DocumentDerivation.derive(described, document, signature, clock()))
                        null
                    }
                },
            )
        }

    /**
     * The title of a marked sealed job output that reads back OK (`work/sealed.opus`), or null. The catalogue rebuild names a
     * note after it before adopt; the read is remembered by signature, so adopt does not read the file again.
     */
    internal suspend fun sealedTitle(noteId: String): String? = locks.withNoteLock(noteId, dispatchers.io) {
        val sealed = File(paths.work(noteId), NoteFiles.SEALED)
        val marked = File(paths.work(noteId), NoteFiles.SEALED_MARKER).isFile
        DocumentSignature.of(sealed)?.takeIf { marked }?.let { readLocked(sealed, it) }
            ?.takeIf { it.documentState == DocumentState.OK }
            ?.let { NoteTitles.fit(it.title) }
    }

    /**
     * Seals [transcript] over [audio] into `work/sealed.opus` with `CassiniDocument.create` (which re-reads and verifies
     * the written file), fsyncs it, then writes the `work/sealed` marker. It never touches `document.opus`; adopt
     * installs the output. The verified document is remembered by signature, so the adopt that follows does not read
     * it again. Runs on the inference thread.
     */
    @WorkerThread
    fun sealBlocking(
        noteId: String,
        audio: File,
        transcript: Transcript,
        title: String,
        processing: JSONObject,
        existing: CassiniDocument?,
        labels: Map<String, String>,
    ): CassiniDocument {
        Threads.assertOffMain("sealBlocking")
        return locks.withNoteLockBlocking(noteId) {
            val work = paths.work(noteId).apply { mkdirs() }
            val marker = File(work, NoteFiles.SEALED_MARKER)
            val sealed = File(work, NoteFiles.SEALED)
            marker.delete()
            var done = false
            try {
                val document = CassiniDocument.create(audio, transcript, title, processing, existing, labels, sealed)
                NoteFiles.syncFile(sealed)
                marker.createNewFile()
                NoteFiles.syncFile(marker)
                NoteFiles.syncDirectory(work)
                DocumentSignature.of(sealed)?.let { lru.put(it, document) }
                done = true
                document
            } finally {
                if (!done) sealed.delete()
            }
        }
    }

    /**
     * Installs `work/sealed.opus` (architecture 3.2): renames it over `document.opus`, fsyncs the directory, rebuilds
     * the row and every cache row and deletes the job row in one transaction, writes a dirty title into the document
     * and deletes the previous `capture.*`/`source.*` file. `work/` and its marker stay: the caller runs
     * `VoiceRepository.adoptPending` and then [deleteAdoptedWork] (architecture 3.5: the row first, then the
     * directory). Safe to run again after a crash at any point: a marker without `sealed.opus` means the rename
     * happened, and the remaining steps run. [selectNewDefault] selects the document's default variant (the new one);
     * otherwise the selection is kept. [SealedAdopter.of] runs the whole sequence.
     */
    suspend fun adoptSealed(noteId: String, selectNewDefault: Boolean = true): AdoptOutcome =
        locks.withNoteLock(noteId, dispatchers.io) { adoptLocked(noteId, selectNewDefault) }

    /**
     * The last step of an adopt: deletes `work/` with the `sealed` marker last, so a crash before the end leaves a
     * marker and the next start runs the adopt again. Does nothing without a marker: the directory then belongs to a
     * newer job.
     */
    suspend fun deleteAdoptedWork(noteId: String) {
        locks.withNoteLock(noteId, dispatchers.io) {
            val work = paths.work(noteId)
            val marker = File(work, NoteFiles.SEALED_MARKER)
            if (marker.isFile) {
                work.listFiles().orEmpty().filter { it != marker }.forEach { it.deleteRecursively() }
                marker.delete()
                work.delete()
            }
        }
    }

    /** Renames speakers in the document ([labels] by speaker id). Refused while a job row exists or unless OK. */
    suspend fun relabel(noteId: String, labels: Map<String, String>): EditOutcome = edit(noteId) { _, document ->
        val known = document.manifest?.optJSONArray("speakers")?.let { list ->
            (0 until list.length()).map { list.getJSONObject(it).optString("id") }.toSet()
        }.orEmpty()
        when {
            labels.isEmpty() -> EditOutcome.Done()
            !known.containsAll(labels.keys) -> EditOutcome.Invalid("unknown speaker")
            labels.values.any { it.isBlank() } -> EditOutcome.Invalid("blank label")
            else -> {
                rewrite(noteId) { source, target -> CassiniDocument.relabel(source, document, labels, target) }
                EditOutcome.Done()
            }
        }
    }

    /**
     * Writes `notes.title` into the document (`meeting.title` and the `TITLE` tag) and clears `title_dirty`. The title
     * is read from the row under the note's lock, so the latest rename wins. Refused while a job row exists (adopt
     * writes the title then) and for a document that is not OK, which keeps the new title in Room only.
     */
    suspend fun retitle(noteId: String): EditOutcome = edit(noteId) { row, document ->
        retitleDocument(noteId, row, document)
    }

    /**
     * Merges speaker [source] into [target] in variant [variantId]: a new variant from `SpeakerMerges.apply` with the
     * `SpeakerMerges.processing` provenance, which becomes the default and the selected variant (version N+1). The
     * earlier variants stay. Refused while a job row exists or unless OK. Voiceprints are copied by the caller.
     */
    suspend fun mergeSpeakers(noteId: String, variantId: String, source: String, target: String): EditOutcome =
        edit(noteId) { row, document ->
            val transcript = document.variants.firstOrNull { it.id == variantId }?.transcript
            val speakers = transcript?.words?.mapTo(HashSet()) { it.speaker }.orEmpty()
            when {
                transcript == null -> EditOutcome.Invalid("unknown or unreadable variant")
                source == target || source !in speakers || target !in speakers -> EditOutcome.Invalid("speakers")
                else -> {
                    val aliases = mapOf(source to target)
                    val merged = SpeakerMerges.apply(transcript, aliases)
                    val previous = DocumentDerivation.processingOf(document, variantId)
                    val processing = SpeakerMerges.processing(previous, aliases, variantId)
                    // With an existing document, create keeps its manifest (and title) and adds the variant as default.
                    val written = rewrite(noteId, selectDefault = true) { audio, out ->
                        CassiniDocument.create(audio, merged, row.title, processing, document, emptyMap(), out)
                    }
                    EditOutcome.Done(written.defaultId)
                }
            }
        }

    /** "Save a copy": the note's audio file byte for byte (the document, or the plain capture or import). */
    suspend fun exportCopy(noteId: String, dest: Uri): ExportOutcome = withContext(dispatchers.io) {
        val row = notes.note(noteId)
        val file = row?.let { File(paths.note(noteId), it.audioFile) }
        when {
            row == null || file == null -> ExportOutcome.NoNote
            row.audioKind == AudioKind.LIVE_CAPTURE || !file.isFile -> ExportOutcome.NotAvailable
            else -> writeTo(dest) { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    /**
     * A TXT, SRT or VTT export of one variant with the :core writers. The words come from the word cache (refreshed
     * first), the title and duration from the row. The text is staged under `cacheDir/exports/`, which is cleaned
     * after the write. Not available for plain notes and for UNREADABLE or UNKNOWN_FORMAT documents.
     */
    suspend fun exportTranscript(noteId: String, request: TranscriptExport, dest: Uri): ExportOutcome {
        if (refreshIfStale(noteId) == RefreshOutcome.NoNote) return ExportOutcome.NoNote
        return withContext(dispatchers.io) {
            val row = notes.note(noteId)
            val variants = cache.variants(noteId).filter { it.readable }
            val variantId = request.variantId
                ?: row?.selectedVariant?.takeIf { id -> variants.any { it.variantId == id } }
                ?: variants.firstOrNull { it.isDefault }?.variantId ?: variants.firstOrNull()?.variantId
            when {
                row == null -> ExportOutcome.NoNote
                row.audioKind != AudioKind.DOCUMENT || row.documentState in NOT_EXPORTABLE -> ExportOutcome.NotAvailable
                variantId == null || variants.none { it.variantId == variantId } -> ExportOutcome.NotAvailable
                else -> {
                    val words = cache.words(noteId, variantId).map { Word(it.speaker, it.startMs, it.endMs, it.text) }
                    val transcript = ExportTranscript(row.title, row.durationMs, words, request.labels)
                    val name = ExportNames.file(row.title, request.format.extension)
                    stageAndWrite(name, render(transcript, request), dest)
                }
            }
        }
    }

    /** Deletes `notes/<id>/` recursively (step 3 of the purge, architecture 4.10). True when nothing is left. */
    suspend fun deleteFiles(noteId: String): Boolean = locks.withNoteLock(noteId, dispatchers.io) {
        val dir = paths.note(noteId)
        !dir.exists() || dir.deleteRecursively()
    }

    /**
     * The startup sweep (architecture 4.8, item 6): deletes `cacheDir/exports/`, `*.tmp` files older than an hour
     * directly inside a note directory, and a note directory without a row that holds nothing but such files and is
     * older than an hour. It never deletes audio and never looks inside `work/`.
     */
    suspend fun sweep(): SweepReport = withContext(dispatchers.io) {
        val deleted = mutableListOf<String>()
        if (paths.exports.exists() && paths.exports.deleteRecursively()) deleted += paths.exports.path
        val rows = notes.all().mapTo(HashSet()) { it.id }
        val cutoff = clock() - STALE_TMP_MS
        for (dir in paths.notes.listFiles().orEmpty().filter { it.isDirectory }) {
            deleted += locks.withNoteLock(dir.name, dispatchers.io) { sweepNote(dir, dir.name in rows, cutoff) }
        }
        SweepReport(deleted)
    }

    private fun sweepNote(dir: File, hasRow: Boolean, cutoff: Long): List<String> {
        val entries = dir.listFiles().orEmpty().toList()
        val staleTmp = entries.filter { it.isFile && it.name.endsWith(NoteFiles.TMP_SUFFIX) && it.lastModified() < cutoff }
        return if (!hasRow && staleTmp.size == entries.size && dir.lastModified() < cutoff) {
            if (dir.deleteRecursively()) listOf(dir.path) else emptyList()
        } else {
            staleTmp.filter { it.delete() }.map { it.path }
        }
    }

    // Under the note's lock from here on.

    private fun readLocked(file: File, signature: String): CassiniDocument =
        lru.get(signature) ?: reader.read(file).also { lru.put(signature, it) }

    private fun refreshLocked(noteId: String): RefreshOutcome {
        val row = notes.noteBlocking(noteId)
        val file = documentFile(noteId)
        val signature = DocumentSignature.of(file)
        return when {
            row == null -> RefreshOutcome.NoNote
            row.audioKind != AudioKind.DOCUMENT && signature == null -> RefreshOutcome.NotDocument
            row.audioKind == AudioKind.DOCUMENT && signature != null && signature == row.documentSignature &&
                cacheComplete(noteId, row) -> RefreshOutcome.Fresh
            else -> {
                val document = if (signature == null) missing(file) else readLocked(file, signature)
                writeCache(noteId, document, signature)
                    ?.let { RefreshOutcome.Refreshed(document.documentState) } ?: RefreshOutcome.NoNote
            }
        }
    }

    /** Whether every readable variant has its words cached (a deleted cache is derived again). */
    private fun cacheComplete(noteId: String, row: NoteEntity): Boolean =
        cache.incompleteVariantsBlocking(noteId) == 0 &&
            (row.documentState !in WITH_MANIFEST || cache.variantCountBlocking(noteId) > 0)

    /**
     * Derives the cache of [document] (the file with [signature]) from the current row and writes it, in one
     * transaction, so a rename that commits meanwhile is never lost. Null when the row is gone.
     */
    private fun writeCache(
        noteId: String,
        document: CassiniDocument,
        signature: String?,
        select: String? = null,
        removeJob: Boolean = false,
    ): NoteCache? =
        database.runInTransaction(
            Callable {
                notes.noteBlocking(noteId)?.let { row ->
                    if (removeJob) jobs.deleteBlocking(noteId)
                    DocumentDerivation.derive(row, document, signature, clock(), select)
                        .also(cache::replaceCacheBlocking)
                }
            },
        )

    private fun adoptLocked(noteId: String, selectNewDefault: Boolean): AdoptOutcome {
        val dir = paths.note(noteId)
        val work = paths.work(noteId)
        val marker = File(work, NoteFiles.SEALED_MARKER)
        val document = File(dir, NoteFiles.DOCUMENT)
        val blocked = when {
            !marker.isFile -> AdoptOutcome.NothingToAdopt
            notes.noteBlocking(noteId) == null -> AdoptOutcome.NoNote
            File(work, NoteFiles.SEALED).isFile -> if (installSealed(dir, work)) null else AdoptOutcome.Unreadable
            // The marker outlived its output: the rename happened before a crash, unless nothing was ever installed.
            !document.isFile -> AdoptOutcome.Unreadable.also { marker.delete() }
            else -> null
        }
        return blocked ?: finishAdopt(noteId, dir, selectNewDefault)
    }

    /** Moves a sealed output that reads back OK over `document.opus`; drops it with its marker otherwise. */
    private fun installSealed(dir: File, work: File): Boolean {
        val sealed = File(work, NoteFiles.SEALED)
        val signature = DocumentSignature.of(sealed)
        val ok = signature != null && readLocked(sealed, signature).documentState == DocumentState.OK
        if (ok) {
            NoteFiles.replace(sealed, File(dir, NoteFiles.DOCUMENT))
            NoteFiles.syncDirectory(dir)
        } else {
            // The marker first: a marker that outlives its output means "the rename happened".
            File(work, NoteFiles.SEALED_MARKER).delete()
            sealed.delete()
        }
        return ok
    }

    private fun finishAdopt(noteId: String, dir: File, selectNewDefault: Boolean): AdoptOutcome {
        val file = File(dir, NoteFiles.DOCUMENT)
        val signature = DocumentSignature.of(file)
        val document = if (signature == null) missing(file) else readLocked(file, signature)
        // The job row goes with the row update, before anything in work/: a crash later leaves a marker and no job,
        // and the next start finishes this adopt instead of queueing the finished note again.
        val written = writeCache(
            noteId,
            document,
            signature,
            if (selectNewDefault) document.defaultId else null,
            removeJob = true,
        ) ?: return AdoptOutcome.NoNote
        // A rename made while the job ran set title_dirty; adopt writes that title into the document. The first seal of
        // a plain Ogg Opus file keeps the file's own TITLE tag, so adopt also aligns a tag that differs.
        val tagDiffers = titleTag(document) != written.note.title
        if ((written.note.titleDirty || tagDiffers) && document.documentState == DocumentState.OK) {
            guarded { retitleDocument(noteId, written.note, document) }
        }
        dir.listFiles().orEmpty().filter { it.isFile && NoteFiles.isPreviousAudio(it.name) }.forEach { it.delete() }
        return AdoptOutcome.Adopted(document.documentState)
    }

    /**
     * Writes [row]'s title into [document] when its `meeting.title` or its first `TITLE` tag differs; otherwise only
     * clears a stale `title_dirty`.
     */
    private fun retitleDocument(noteId: String, row: NoteEntity, document: CassiniDocument): EditOutcome {
        if (document.title != row.title || titleTag(document) != row.title) {
            rewrite(noteId) { source, target -> CassiniDocument.retitle(source, document, row.title, target) }
        } else if (row.titleDirty) {
            writeCache(noteId, document, DocumentSignature.of(documentFile(noteId)))
        }
        return EditOutcome.Done()
    }

    /** The value of the first `TITLE` tag of [document], or null without one. */
    private fun titleTag(document: CassiniDocument): String? = document.comments
        .firstOrNull { it.substringBefore('=').equals(TITLE_TAG, ignoreCase = true) }
        ?.substringAfter('=')

    /**
     * Runs an edit of an OK document under the note's lock. Refused while a job row exists: the document is then the
     * job's audio source and its signature is in the checkpoint (architecture 3.1).
     */
    private suspend fun edit(noteId: String, block: (NoteEntity, CassiniDocument) -> EditOutcome): EditOutcome =
        locks.withNoteLock(noteId, dispatchers.io) {
            val row = notes.noteBlocking(noteId)
            val file = documentFile(noteId)
            val signature = DocumentSignature.of(file)
            when {
                row == null -> EditOutcome.NoNote
                jobs.jobBlocking(noteId) != null -> EditOutcome.JobPending
                row.audioKind != AudioKind.DOCUMENT || signature == null -> EditOutcome.NotEditable(row.documentState)
                else -> {
                    val document = readLocked(file, signature)
                    if (document.documentState != DocumentState.OK) {
                        EditOutcome.NotEditable(document.documentState)
                    } else {
                        guarded { block(row, document) }
                    }
                }
            }
        }

    /**
     * Replaces `document.opus` with what [write] produces from it: `document.opus.tmp`, fsync, rename, directory fsync,
     * then every cache row in one transaction. [selectDefault] selects the written document's default variant.
     */
    private fun rewrite(
        noteId: String,
        selectDefault: Boolean = false,
        write: (File, File) -> CassiniDocument,
    ): CassiniDocument {
        val dir = paths.note(noteId)
        val file = File(dir, NoteFiles.DOCUMENT)
        val tmp = File(dir, NoteFiles.DOCUMENT_TMP)
        tmp.delete()
        var moved = false
        try {
            val document = write(file, tmp)
            NoteFiles.syncFile(tmp)
            NoteFiles.replace(tmp, file)
            moved = true
            NoteFiles.syncDirectory(dir)
            val signature = DocumentSignature.of(file)
            signature?.let { lru.put(it, document) }
            writeCache(noteId, document, signature, if (selectDefault) document.defaultId else null)
            return document
        } finally {
            if (!moved) tmp.delete()
        }
    }

    private fun render(transcript: ExportTranscript, request: TranscriptExport): String = when (request.format) {
        TranscriptFormat.TXT -> TextExport.write(transcript, request.subtitle, request.emptyText)
        TranscriptFormat.SRT -> SrtWriter.write(transcript)
        TranscriptFormat.VTT -> VttWriter.write(transcript)
    }

    private fun stageAndWrite(name: String, text: String, dest: Uri): ExportOutcome {
        val staging = File(paths.exports, UUID.randomUUID().toString())
        return try {
            staging.mkdirs()
            val staged = File(staging, name).apply { writeText(text, Charsets.UTF_8) }
            writeTo(dest) { out -> staged.inputStream().use { it.copyTo(out) } }
        } catch (error: IOException) {
            ExportOutcome.Failed(Failure.SAVE, error.message)
        } finally {
            staging.deleteRecursively()
            paths.exports.delete() // Only when empty: another export may be staging.
        }
    }

    private fun writeTo(dest: Uri, copy: (OutputStream) -> Long): ExportOutcome = try {
        val out = resolver.openOutputStream(dest, "wt")
        if (out == null) ExportOutcome.Failed(Failure.OPEN, null) else ExportOutcome.Saved(out.use(copy))
    } catch (error: IOException) {
        ExportOutcome.Failed(Failure.SAVE, error.message)
    } catch (error: SecurityException) {
        ExportOutcome.Failed(Failure.SAVE, error.message)
    }

    /** [row] as [document] describes it: its title, recording time and audio file; a duration when it has no manifest. */
    private fun describedBy(row: NoteEntity, document: CassiniDocument, file: File): NoteEntity {
        val meeting = document.manifest?.optJSONObject("meeting")
        val createdAt = meeting?.optString("createdAtUtc")?.let { text ->
            try {
                Instant.parse(text).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
        val duration = if (meeting == null && row.durationMs == 0L) AudioFiles.opusDurationMs(file) else null
        return row.copy(
            title = NoteTitles.fit(document.title) ?: row.title,
            createdAt = createdAt ?: row.createdAt,
            durationMs = duration ?: row.durationMs,
            audioFile = NoteFiles.DOCUMENT,
            audioKind = AudioKind.DOCUMENT,
        )
    }

    private fun missing(file: File) =
        CassiniDocument(DocumentState.UNREADABLE.code, warnings = listOf("${file.name} is missing"))

    /** Runs a document write; a failure leaves the old document in place and becomes [EditOutcome.Failed]. */
    private fun guarded(block: () -> EditOutcome): EditOutcome = try {
        block()
    } catch (error: UserFacingException) {
        EditOutcome.Failed(error.failure, error.message)
    } catch (error: IOException) {
        EditOutcome.Failed(Failure.SAVE, error.message)
    } catch (error: IllegalArgumentException) {
        EditOutcome.Failed(Failure.SAVE, error.message)
    } catch (error: IllegalStateException) {
        EditOutcome.Failed(Failure.SAVE, error.message)
    } catch (error: JSONException) {
        EditOutcome.Failed(Failure.SAVE, error.message)
    }

    private companion object {
        /** A `*.tmp` file this old belongs to no running write. */
        const val STALE_TMP_MS = 60 * 60 * 1000L

        /** Document states that carry a manifest and therefore at least one variant. */
        val WITH_MANIFEST = setOf(DocumentState.OK, DocumentState.UNVERIFIED, DocumentState.STALE_AUDIO)

        /** Document states whose transcript cannot be exported (features.md section 6). */
        val NOT_EXPORTABLE = setOf(DocumentState.UNREADABLE, DocumentState.UNKNOWN_FORMAT)

        /** The Vorbis comment that names the recording; players show it. */
        const val TITLE_TAG = "TITLE"
    }
}
