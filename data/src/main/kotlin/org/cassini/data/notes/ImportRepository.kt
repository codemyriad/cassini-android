package org.cassini.data.notes

import android.content.ContentResolver
import android.database.SQLException
import android.database.sqlite.SQLiteFullException
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.cassini.core.Failure
import org.cassini.core.audio.Limits
import org.cassini.core.library.AudioKind
import org.cassini.core.library.Origin
import org.cassini.data.AppPaths
import org.cassini.data.CassiniDispatchers
import org.cassini.data.NoteLocks
import org.cassini.data.Sha256
import org.cassini.data.db.CassiniDatabase
import org.cassini.data.db.NoteEntity
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
    /** `OpenableColumns.DISPLAY_NAME` and `SIZE`; either is null when the provider does not say. */
    data class Description(val displayName: String?, val size: Long?)

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
            val file = uri.path?.let(::File)
            return ImportSource.Description(file?.name, file?.takeIf { it.isFile }?.length())
        }
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
)

/**
 * "Import audio" (docs/architecture.md, section 4.2; features.md sections 1 and 13.5). An import:
 *
 * 1. reads the display name and size, and rejects LARGE above 4 GiB (`Limits.MAX_IMPORT_BYTES`);
 * 2. requires the size plus `Limits.MIN_FREE_BYTES` free, else SPACE with the bytes needed;
 * 3. copies the bytes into `notes/<newId>/source.tmp` while it hashes them (SHA-256) and enforces the cap and the free
 *    space, which also covers a provider that gave no size;
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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val notes = database.notes()

    suspend fun import(uri: Uri): ImportOutcome = withContext(dispatchers.io) {
        val description = source.describe(uri)
        val size = description.size?.takeIf { it >= 0 }
        paths.notes.mkdirs()
        when {
            size != null && size > limits.maxBytes -> ImportOutcome.Rejected(Failure.LARGE, "size $size")
            size != null && limits.usableSpace(paths.notes) < size + limits.minFreeBytes ->
                ImportOutcome.Rejected(Failure.SPACE, neededBytes = size + limits.minFreeBytes)
            else -> importNew(uri, description, size)
        }
    }

    private suspend fun importNew(uri: Uri, description: ImportSource.Description, size: Long?): ImportOutcome {
        val id = UUID.randomUUID().toString()
        val dir = paths.note(id)
        var outcome: ImportOutcome = ImportOutcome.Rejected(Failure.SAVE, "cannot create the note directory")
        try {
            // Nobody knows the new id yet, so the copy runs without the note's lock: a lock stripe is shared with
            // other notes, and a long copy must not hold up their writes.
            if (dir.mkdirs()) outcome = stage(uri, id, description, size)
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

    /** Steps 3 to 6 for the new note [id], whose directory exists. */
    private suspend fun stage(uri: Uri, id: String, description: ImportSource.Description, size: Long?): ImportOutcome {
        val tmp = File(paths.note(id), SOURCE_TMP)
        return when (val copied = copy(uri, tmp, size)) {
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

    /** Step 3: copies the bytes into [tmp] with their SHA-256, enforcing the size cap and the free space. */
    private fun copy(uri: Uri, tmp: File, size: Long?): Copy = try {
        val input = source.open(uri) ?: throw FileNotFoundException("no stream")
        val digest = MessageDigest.getInstance("SHA-256")
        val failed = input.use { tmp.outputStream().use { out -> pump(input, out, size, digest) } }
        failed ?: Copy.Done(Sha256.hex(digest), tmp.length())
    } catch (error: FileNotFoundException) {
        Copy.Failed(ImportOutcome.Rejected(Failure.OPEN, error.message))
    } catch (error: SecurityException) {
        Copy.Failed(ImportOutcome.Rejected(Failure.OPEN, error.message))
    } catch (error: IOException) {
        Copy.Failed(lowSpace(size, tmp.length()) ?: ImportOutcome.Rejected(Failure.OPEN, error.message))
    }

    private fun pump(input: InputStream, out: FileOutputStream, size: Long?, digest: MessageDigest): Copy.Failed? {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        var nextSpaceCheck = SPACE_CHECK_BYTES
        var failed: Copy.Failed? = null
        while (failed == null) {
            val n = input.read(buffer)
            if (n < 0) break
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
            "audio/vorbis" to "ogg",
            "audio/3gpp" to "3gp",
            "audio/amr-wb" to "awb",
        )

        /**
         * The note title from a display name: the name without its extension, trimmed and cut to 200 characters;
         * [fallback] when nothing is left.
         */
        fun titleOf(displayName: String?, fallback: String = FALLBACK_TITLE): String {
            val name = displayName?.trim().orEmpty()
            val stem = name.substringBeforeLast('.', name).ifBlank { name }
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
