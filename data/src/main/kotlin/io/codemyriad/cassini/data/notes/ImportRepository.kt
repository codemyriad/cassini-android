package io.codemyriad.cassini.data.notes

import android.content.ContentResolver
import android.database.SQLException
import android.database.sqlite.SQLiteFullException
import android.net.Uri
import android.provider.OpenableColumns
import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.audio.ImportTypes
import io.codemyriad.cassini.core.audio.Limits
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.Origin
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.NoteLocks
import io.codemyriad.cassini.data.Sha256
import io.codemyriad.cassini.data.db.CassiniDatabase
import io.codemyriad.cassini.data.db.NoteEntity
import io.codemyriad.cassini.data.useCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Where imported bytes come from: the picker's `content://` Uris through the ContentResolver, or `file://` Uris. */
interface ImportSource {
    /**
     * `OpenableColumns.DISPLAY_NAME` and `SIZE`, and the provider's MIME type (`ContentResolver.getType`); each is
     * null when the provider does not say.
     */
    data class Description(val displayName: String?, val size: Long?, val mimeType: String? = null)

    fun describe(uri: Uri): Description

    /** The bytes, or null when the provider gives no stream. */
    fun open(uri: Uri): InputStream?

    companion object {
        fun of(resolver: ContentResolver): ImportSource = ResolverImportSource(resolver)
    }
}

private class ResolverImportSource(private val resolver: ContentResolver) : ImportSource {
    override fun describe(uri: Uri): ImportSource.Description {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            // No provider type: the display name's extension decides (ImportTypes.verdict).
            val file = uri.path?.let(::File)
            return ImportSource.Description(file?.name, file?.takeIf { it.isFile }?.length())
        }
        return columns(uri).copy(mimeType = typeOf(uri))
    }

    private fun typeOf(uri: Uri): String? = try {
        resolver.getType(uri)
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun columns(uri: Uri): ImportSource.Description {
        return try {
            val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            resolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                ImportSource.Description(
                    displayName = if (name >= 0 && !cursor.isNull(name)) cursor.getString(name) else null,
                    size = if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size) else null,
                )
            } ?: UNKNOWN
        } catch (_: SecurityException) {
            UNKNOWN
        } catch (_: IllegalArgumentException) {
            UNKNOWN
        }
    }

    override fun open(uri: Uri): InputStream? = resolver.openInputStream(uri)

    private companion object {
        /**
         * A content Uri without a display name. Its last path segment is an opaque id (`msf:24`, `audio:1234`), not a
         * name, so the note gets the fallback title.
         */
        val UNKNOWN = ImportSource.Description(null, null)
    }
}

/** The size and space limits of an import; tests pass a small cap and a fake free-space reading. */
class ImportLimits(
    val maxBytes: Long = Limits.MAX_IMPORT_BYTES,
    val minFreeBytes: Long = Limits.MIN_FREE_BYTES,
    val usableSpace: (File) -> Long = File::getUsableSpace,
) {
    /** LARGE above the cap, SPACE when [size] plus the free margin does not fit in [dir]; null when it fits. */
    fun refusal(size: Long, dir: File): ImportOutcome.Rejected? = when {
        size > maxBytes -> ImportOutcome.Rejected(Failure.LARGE, "size $size")
        usableSpace(dir) < size + minFreeBytes ->
            ImportOutcome.Rejected(Failure.SPACE, neededBytes = size + minFreeBytes)
        else -> null
    }
}

/**
 * "Import audio" (docs/architecture.md, section 4.2; features.md sections 1 and 13.5). An import:
 *
 * 1. reads the display name, size and MIME type; with `checkType` (a file the person picked, opened with or shared to
 *    Cassini) it rejects OPEN a file that is not a decided type of the shared import-types contract
 *    ([ImportTypes.verdict], L-25), and OPEN an Opus/Ogg or compatibility-MIME input whose first
 *    [ImportTypes.MIN_PROBE_BYTES] are not an Ogg Opus stream ([ImportTypes.contentValidated]: Ogg video, Vorbis or
 *    FLAC-in-Ogg). That head is read from the opened stream before anything is written, so a large Ogg video is
 *    neither copied nor reported as too large or as needing space;
 * 2. rejects LARGE above 4 GiB (`Limits.MAX_IMPORT_BYTES`), and requires the size plus `Limits.MIN_FREE_BYTES` free,
 *    else SPACE with the bytes needed;
 * 3. copies the bytes (the probed head first) into `notes/<newId>/source.tmp` in one pass while it hashes them
 *    (SHA-256) and enforces the cap and the free space, which also covers a provider that gave no size;
 * 4. returns [ImportOutcome.Existing] when a live note has the same hash (a trashed one does not count);
 * 5. classifies the file: Ogg Opus with Cassini metadata becomes `document.opus` (IMPORTED_DOCUMENT, verified now so
 *    the first open is cheap), other Ogg Opus `source.opus`, a 16-bit PCM WAV `source.wav` (any other WAV is
 *    rejected as WAV), and anything else `source.<ext>` when [probe] finds an audio track (else OPEN);
 * 6. inserts the row with the duration and a title: the document's `meeting.title`, else the display name without
 *    its extension (a content Uri without a display name gets the fallback title). A plain Ogg Opus file's own TITLE
 *    tag is not used; the adopt of the first seal writes the note title into it.
 *
 * Imports run while a job runs. Nothing is left behind when an import fails. A storage error after the copy (a full
 * disk, a failed rename, read or insert) gives SPACE or SAVE, not an exception.
 */
// Manual DI: the repositories take their collaborators explicitly.
@Suppress("LongParameterList")
class ImportRepository(
    private val database: CassiniDatabase,
    private val paths: AppPaths,
    private val dispatchers: CassiniDispatchers,
    private val locks: NoteLocks,
    private val documents: DocumentRepository,
    private val source: ImportSource,
    private val probe: AudioProbe = AudioProbe.Extractor,
    private val limits: ImportLimits = ImportLimits(),
    /** The title of a file without a usable name, read at each import; the app passes its localized string. */
    private val fallbackTitle: () -> String = { FALLBACK_TITLE },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val notes = database.notes()

    /**
     * [displayName] retains a staged archive/provider file's name without using it as a filesystem path. [checkType]
     * applies the shared import-types contract (step 1); entries of Cassini's own archives skip it, so a backup of an
     * older note in another format still restores. [mimeType] is the type the sending app put on its intent, used
     * when the provider itself reports none.
     */
    suspend fun import(
        uri: Uri,
        displayName: String? = null,
        checkType: Boolean = false,
        mimeType: String? = null,
    ): ImportOutcome =
        withContext(dispatchers.io) {
            val described = source.describe(uri)
            val description = described.copy(
                displayName = displayName ?: described.displayName,
                mimeType = described.mimeType ?: mimeType?.takeIf { it.isNotBlank() },
            )
            val size = description.size?.takeIf { it >= 0 }
            val verdict = if (checkType) {
                ImportTypes.verdict(typeName(description.displayName, uri), description.mimeType)
            } else {
                ImportTypes.Verdict.ACCEPT
            }
            paths.notes.mkdirs()
            val validate = verdict
            when {
                verdict == ImportTypes.Verdict.REJECT ->
                    ImportOutcome.Rejected(Failure.OPEN, "not an import type: ${description.mimeType}")
                // An input that needs the stream check is checked first (in the copy): an Ogg video is OPEN, not LARGE.
                validate == ImportTypes.Verdict.ACCEPT && size != null ->
                    limits.refusal(size, paths.notes) ?: importNew(uri, description, size, validate)
                else -> importNew(uri, description, size, validate)
            }
        }

    private suspend fun importNew(
        uri: Uri,
        description: ImportSource.Description,
        size: Long?,
        validate: ImportTypes.Verdict,
    ): ImportOutcome {
        val id = newId()
        val dir = paths.note(id)
        var outcome: ImportOutcome = ImportOutcome.Rejected(Failure.SAVE, "cannot create the note directory")
        try {
            // Nobody knows the new id yet, so the copy runs without the note's lock: a lock stripe is shared with
            // other notes, and a long copy must not hold up their writes.
            if (dir.mkdirs()) outcome = stage(uri, id, description, size, validate)
        } finally {
            // NonCancellable: a cancelled import must still delete what it copied. A row means the insert committed.
            if (outcome !is ImportOutcome.Created) {
                withContext(NonCancellable) {
                    locks.withNoteLock(id, dispatchers.io) {
                        if (notes.noteBlocking(id) == null) dir.deleteRecursively()
                    }
                }
            }
        }
        return outcome
    }

    /** Steps 3 to 6 for the new note [id], whose directory exists; [validate] runs the stream check of step 1. */
    private suspend fun stage(
        uri: Uri,
        id: String,
        description: ImportSource.Description,
        size: Long?,
        validate: ImportTypes.Verdict,
    ): ImportOutcome {
        val tmp = File(paths.note(id), SOURCE_TMP)
        return when (val copied = copy(uri, tmp, size, validate)) {
            is Copy.Failed -> copied.outcome
            is Copy.Done -> try {
                notes.liveWithSource(copied.sha256)?.let { ImportOutcome.Existing(it) }
                    ?: when (val staged = locks.withNoteLock(id, dispatchers.io) { classify(tmp, description) }) {
                        is Classified.Rejected -> staged.outcome
                        is Classified.Audio -> insert(id, description.displayName, staged, copied.sha256)
                    }
            } catch (error: SQLiteFullException) {
                ImportOutcome.Rejected(Failure.SPACE, error.message, neededBytes = copied.bytes + limits.minFreeBytes)
            } catch (error: SQLException) {
                ImportOutcome.Rejected(Failure.SAVE, error.message)
            } catch (error: IOException) {
                // A rename or a read after the copy failed; `importNew` deletes the copied file.
                lowSpace(size, copied.bytes) ?: ImportOutcome.Rejected(Failure.SAVE, error.message)
            }
        }
    }

    /** Step 6: the row, written only when no live note has the same bytes by now. */
    private suspend fun insert(id: String, name: String?, staged: Classified.Audio, sha256: String): ImportOutcome {
        val now = clock()
        val row = NoteEntity(
            id = id,
            title = titleOf(name, fallbackTitle()),
            createdAt = now,
            addedAt = now,
            origin = staged.origin,
            audioFile = staged.fileName,
            audioKind = staged.kind,
            durationMs = staged.durationMs,
            sourceSha256 = sha256,
            updatedAt = now,
        )
        val duplicate = if (staged.kind == AudioKind.DOCUMENT) {
            documents.catalogueDocument(row) { notes.liveWithSourceBlocking(sha256) }
        } else {
            notes.insertUnlessDuplicate(row)
        }
        return duplicate?.let { ImportOutcome.Existing(it) } ?: ImportOutcome.Created(id)
    }

    private sealed interface Copy {
        class Done(val sha256: String, val bytes: Long) : Copy

        class Failed(val outcome: ImportOutcome.Rejected) : Copy
    }

    /**
     * Step 3: copies the bytes into [tmp] with their SHA-256, enforcing the size cap and the free space. With
     * [validate] it first reads the head from the stream and runs the stream check and step 2 on it, before [tmp] is
     * even created: a rejected input costs at most [ImportTypes.MIN_PROBE_BYTES] read and nothing written. The head
     * is then written first, so the stream is read once (a pipe-backed provider cannot be reopened).
     */
    private suspend fun copy(uri: Uri, tmp: File, size: Long?, validate: ImportTypes.Verdict): Copy = try {
        val input = source.open(uri) ?: throw FileNotFoundException("no stream")
        val digest = MessageDigest.getInstance("SHA-256")
        val failed = input.useCancellable {
            val probed = when (validate) {
                ImportTypes.Verdict.VALIDATE -> oggOpusHead(input)
                ImportTypes.Verdict.VALIDATE_FLAC -> head(input, ImportTypes.FLAC_MIN_PROBE_BYTES)
                    .takeIf { ImportTypes.flacHeaderValidated(it) }
                else -> ByteArray(0)
            }
            currentCoroutineContext().ensureActive()
            if (probed == null) {
                return@useCancellable Copy.Failed(ImportOutcome.Rejected(Failure.OPEN, "invalid import header"))
            }
            val refused = size?.takeIf { validate != ImportTypes.Verdict.ACCEPT }?.let { limits.refusal(it, paths.notes) }
            refused?.let(Copy::Failed) ?: tmp.outputStream().use { out -> pump(input, out, size, digest, probed) }
        }
        failed ?: Copy.Done(Sha256.hex(digest), tmp.length())
    } catch (error: FileNotFoundException) {
        currentCoroutineContext().ensureActive()
        Copy.Failed(ImportOutcome.Rejected(Failure.OPEN, error.message))
    } catch (error: SecurityException) {
        currentCoroutineContext().ensureActive()
        Copy.Failed(ImportOutcome.Rejected(Failure.OPEN, error.message))
    } catch (error: IOException) {
        currentCoroutineContext().ensureActive()
        Copy.Failed(lowSpace(size, tmp.length()) ?: ImportOutcome.Rejected(Failure.OPEN, error.message))
    }

    /** The copy loop of step 3; [head] is the part of the stream read already, written first. */
    private suspend fun pump(
        input: InputStream,
        out: FileOutputStream,
        size: Long?,
        digest: MessageDigest,
        head: ByteArray,
    ): Copy.Failed? {
        digest.update(head)
        out.write(head)
        val buffer = ByteArray(BUFFER_BYTES)
        var total = head.size.toLong()
        var nextSpaceCheck = SPACE_CHECK_BYTES
        var failed: Copy.Failed? = null
        while (failed == null) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) {
                currentCoroutineContext().ensureActive()
                break
            }
            total += n
            failed = when {
                total > limits.maxBytes -> Copy.Failed(ImportOutcome.Rejected(Failure.LARGE, "over the cap"))
                total >= nextSpaceCheck -> {
                    nextSpaceCheck += SPACE_CHECK_BYTES
                    lowSpace(size, total)?.let(Copy::Failed)
                }
                else -> null
            }
            if (failed == null) {
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
            }
        }
        currentCoroutineContext().ensureActive()
        if (failed == null) out.fd.sync()
        return failed
    }

    /** SPACE when less than the free margin is left (a provider without a size, or space used by others meanwhile). */
    private fun lowSpace(size: Long?, copied: Long): ImportOutcome.Rejected? {
        val left = limits.usableSpace(paths.notes)
        return if (left < limits.minFreeBytes) {
            // The copied part is deleted, so a retry needs the whole file plus the margin, as the check before the copy.
            val needed = maxOf(size ?: copied, copied) + limits.minFreeBytes
            ImportOutcome.Rejected(Failure.SPACE, neededBytes = needed)
        } else {
            null
        }
    }

    private sealed interface Classified {
        class Audio(val fileName: String, val kind: AudioKind, val origin: Origin, val durationMs: Long) : Classified

        class Rejected(val outcome: ImportOutcome.Rejected) : Classified
    }

    /** Step 5: decides what the copied file is and moves it to its name in the note directory. */
    private fun classify(tmp: File, description: ImportSource.Description): Classified {
        val result = when (AudioFiles.sniff(tmp)) {
            AudioContainer.OGG_OPUS -> when (AudioFiles.isCassini(tmp)) {
                true -> Classified.Audio(NoteFiles.DOCUMENT, AudioKind.DOCUMENT, Origin.IMPORTED_DOCUMENT, 0)
                false -> AudioFiles.opusDurationMs(tmp)?.let { plain(SOURCE_OPUS, it) } ?: rejected(Failure.OPEN)
                null -> rejected(Failure.OPEN)
            }
            AudioContainer.WAV -> AudioFiles.wavDurationMs(tmp)?.let { plain(SOURCE_WAV, it) } ?: rejected(Failure.WAV)
            AudioContainer.OTHER -> probe.probe(tmp)?.let { track ->
                plain("source.${extensionOf(description.displayName, track.mime)}", track.durationMs)
            } ?: rejected(Failure.OPEN)
        }
        if (result is Classified.Audio) {
            NoteFiles.replace(tmp, File(tmp.parentFile, result.fileName))
            NoteFiles.syncDirectory(checkNotNull(tmp.parentFile))
        }
        return result
    }

    private fun plain(fileName: String, durationMs: Long) =
        Classified.Audio(fileName, AudioKind.PLAIN, Origin.IMPORTED_AUDIO, durationMs)

    private fun rejected(failure: Failure) = Classified.Rejected(ImportOutcome.Rejected(failure))

    companion object {
        /** The English title when the file has no usable name. */
        const val FALLBACK_TITLE = "Imported audio"

        private const val SOURCE_TMP = "source.tmp"
        private const val SOURCE_OPUS = "source.opus"
        private const val SOURCE_WAV = "source.wav"
        private const val BUFFER_BYTES = 64 * 1024
        private const val SPACE_CHECK_BYTES = 8L shl 20

        private val EXTENSION = Regex("[a-z0-9]{1,5}")

        /** Extensions that name a container this class reads itself, or the staging file. */
        private val RESERVED = setOf("opus", "wav", "tmp")

        private val MIME_EXTENSIONS = mapOf(
            "audio/mpeg" to "mp3",
            "audio/mp4a-latm" to "m4a",
            "audio/mp4" to "m4a",
            "audio/aac" to "aac",
            "audio/flac" to "flac",
            "audio/x-flac" to "flac",
            "audio/vorbis" to "ogg",
            "audio/3gpp" to "3gp",
            "audio/amr-wb" to "awb",
        )

        /**
         * The note title from a display name: the name without its extension ([ImportTypes.extensionOf]: a title
         * such as "Voice 08.15" keeps its dot), trimmed and cut to 200 characters; [fallback] when nothing is left.
         */
        fun titleOf(displayName: String?, fallback: String = FALLBACK_TITLE): String {
            val name = displayName?.trim().orEmpty()
            val extension = ImportTypes.extensionOf(name)
            val stem = if (extension == null) name else name.dropLast(extension.length).ifBlank { name }
            return NoteTitles.fit(stem) ?: fallback
        }

        /**
         * The extension of a `source.*` file: the display name's when it is `[a-z0-9]{1,5}` and not reserved, else
         * one from the track's MIME type, else `bin`.
         */
        fun extensionOf(displayName: String?, mime: String?): String {
            val fromName = displayName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
                ?.takeIf { EXTENSION.matches(it) && it !in RESERVED }
            return fromName ?: MIME_EXTENSIONS[mime?.lowercase(Locale.ROOT)] ?: "bin"
        }
    }
}

/**
 * The name whose extension decides the type: the display name when it has one; else the Uri's last segment when that
 * has one (`…/voice.wav`; an opaque id such as `msf:24` has none); else the display name. It never gives the title.
 */
private fun typeName(displayName: String?, uri: Uri): String? =
    displayName?.takeIf { ImportTypes.extensionOf(it) != null }
        ?: uri.lastPathSegment?.takeIf { ImportTypes.extensionOf(it) != null }
        ?: displayName

/** [head] of [input] when it passes [ImportTypes.contentValidated], else null. */
private fun oggOpusHead(input: InputStream): ByteArray? = head(input).takeIf { ImportTypes.contentValidated(it) }

/** The first [ImportTypes.MIN_PROBE_BYTES] of [input], or all of it when it is shorter; nothing more is read. */
private fun head(input: InputStream, bytes: Int = ImportTypes.MIN_PROBE_BYTES): ByteArray {
    val head = ByteArray(bytes)
    var n = 0
    while (n < head.size) {
        val read = input.read(head, n, head.size - n)
        if (read < 0) break
        n += read
    }
    return head.copyOf(n)
}
