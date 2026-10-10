package org.cassini.data.notes

import android.database.SQLException
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.withContext
import org.cassini.core.checkpoint.CheckpointState
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.library.Origin
import org.cassini.core.library.PauseReason
import org.cassini.data.AppPaths
import org.cassini.data.CassiniDispatchers
import org.cassini.data.NoteIds
import org.cassini.data.NoteLocks
import org.cassini.data.Sha256
import org.cassini.data.db.CassiniDatabase
import org.cassini.data.db.JobEntity
import org.cassini.data.db.NoteEntity
import org.cassini.data.voices.VoiceRepository
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The title of a note rebuilt from plain audio: "Recovered recording <d MMM>" for its recording time. */
fun interface RecoveredTitle {
    fun of(createdAt: Long): String

    companion object {
        /** The English title; the app passes one built from its string resources. */
        fun english(zone: ZoneId = ZoneId.systemDefault()): RecoveredTitle {
            val date = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(zone)
            return RecoveredTitle { "Recovered recording ${date.format(Instant.ofEpochMilli(it))}" }
        }
    }
}

/**
 * Rebuilds the Room catalogue from the note directories and voice files (docs/architecture.md, sections 3.1, 4.8
 * step 1 and 4.9) when the database was set aside as corrupt, or when a note directory with audio has no row (a
 * missing or emptied database, or a rebuild that stopped partway).
 *
 * For each `notes/<id>/` without a row:
 * - `document.opus` gives a DOCUMENT row with every cache row, verified once (RECORDED when the Opus vendor is
 *   cassini-android, else IMPORTED_DOCUMENT; the title is `meeting.title`);
 * - otherwise `capture.*` or `source.*` gives a RECOVERED row titled "Recovered recording <d MMM>": a capture that
 *   did not end cleanly is repaired, and the row is PLAIN, or UNPLAYABLE when nothing plays;
 * - a `work/sealed` marker is finished by [SealedAdopter.of]; a `work/checkpoint.json` that decodes gives a PAUSED
 *   job with reason INTERRUPTED (`reconcileOnStart` validates it later).
 *
 * Then `VoiceRepository.syncLinks` rebuilds `note_voice_links` from `voices/notes/<noteId>.json` and deletes the
 * voice files of notes that have neither a row nor a directory. The rebuild is idempotent (notes that have a row are
 * left alone), never deletes audio, and a storage error in one directory does not stop the others. What only Room
 * held is lost: a rename not yet written into a document, trash state, playback positions and the queue.
 */
// Manual DI: the repositories take their collaborators explicitly.
@Suppress("LongParameterList")
class CatalogueRepository(
    database: CassiniDatabase,
    private val paths: AppPaths,
    private val dispatchers: CassiniDispatchers,
    private val locks: NoteLocks,
    private val documents: DocumentRepository,
    /** Finishes the voices of a sealed job and rebuilds `note_voice_links` from the voice files. */
    private val voices: VoiceRepository,
    private val probe: AudioProbe = AudioProbe.Extractor,
    private val recoveredTitle: RecoveredTitle = RecoveredTitle.english(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val notes = database.notes()
    private val jobs = database.jobs()
    private val adopter = SealedAdopter.of(documents, voices)

    /**
     * Rebuilds when the database at start was set aside ([setAside], from `CassiniDatabaseOpener`), and whenever a
     * note directory that holds audio has no row: a missing or emptied database, a rebuild that a crash or an error
     * stopped partway, or an import that died between its rename and its insert. A rebuild only adds what is missing,
     * so the next start finishes an unfinished one. Returns null when no rebuild was needed.
     */
    suspend fun rebuildIfNeeded(setAside: File?): RebuildReport? {
        val needed = setAside != null || uncatalogued().isNotEmpty()
        return if (needed) rebuild() else null
    }

    suspend fun rebuild(): RebuildReport {
        val known = notes.ids().toHashSet()
        val created = mutableListOf<String>()
        val adopted = mutableListOf<String>()
        val paused = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (dir in noteDirectories().filter { it.name !in known }) {
            val id = dir.name
            // One bad directory must not stop the others. Without a row it keeps its files and the next start tries it
            // again; with a row, `reconcileOnStart` handles what is left in its work directory.
            val added = attempt(dir) { catalogue(dir) }
            if (added == null) failed += id
            if (added != true) continue
            created += id
            when (attempt(dir) { finishWork(id) }) {
                Work.ADOPTED -> adopted += id
                Work.PAUSED -> paused += id
                Work.NONE -> Unit
                null -> failed += id
            }
        }
        val links = voices.syncLinks()
        return RebuildReport(created, adopted, paused, links.links, links.deleted, failed)
    }

    private suspend fun noteDirectories(): List<File> = withContext(dispatchers.io) {
        paths.notes.listFiles().orEmpty().filter { it.isDirectory && NoteIds.isValid(it.name) }.sortedBy { it.name }
    }

    /** The note directories that hold audio (a document, a capture or an import) and have no row. */
    private suspend fun uncatalogued(): List<File> {
        val directories = noteDirectories()
        if (directories.isEmpty()) return emptyList()
        val known = notes.ids().toHashSet()
        return withContext(dispatchers.io) {
            directories.filter { dir ->
                dir.name !in known &&
                    dir.list().orEmpty().any { it == NoteFiles.DOCUMENT || NoteFiles.isPreviousAudio(it) }
            }
        }
    }

    /** Adds the row of one note directory; false when it holds no audio (an interrupted import, an empty folder). */
    private suspend fun catalogue(dir: File): Boolean {
        val document = File(dir, NoteFiles.DOCUMENT)
        val documentRow = withContext(dispatchers.io) { if (document.isFile) documentRow(dir.name, document) else null }
        if (documentRow != null) {
            documents.catalogueDocument(documentRow)
        } else {
            val plain = locks.withNoteLock(dir.name, dispatchers.io) { plainRow(dir, clock()) }
            // A sealed output awaiting adopt carries the title the note had when the job sealed it.
            val sealedTitle = plain?.let { documents.sealedTitle(it.id) }
            plain?.let { notes.insert(if (sealedTitle == null) it else it.copy(title = sealedTitle)) }
        }
        return documentRow != null || notes.note(dir.name) != null
    }

    /** The row of a document note before its cache is derived; the document's manifest fills the title and time. */
    private fun documentRow(id: String, document: File): NoteEntity {
        val now = clock()
        val createdAt = document.lastModified()
        val vendor = AudioFiles.vendor(document)
        return NoteEntity(
            id = id,
            title = recoveredTitle.of(createdAt),
            createdAt = createdAt,
            addedAt = now,
            origin = if (vendor?.startsWith(APP_VENDOR) == true) Origin.RECORDED else Origin.IMPORTED_DOCUMENT,
            audioFile = NoteFiles.DOCUMENT,
            audioKind = AudioKind.DOCUMENT,
            updatedAt = now,
        )
    }

    /** The row of a note whose audio is a capture or an imported file; null without one. Under the note's lock. */
    private fun plainRow(dir: File, now: Long): NoteEntity? {
        // Only one should exist; a capture comes first.
        val audio = dir.listFiles().orEmpty().filter { it.isFile && NoteFiles.isPreviousAudio(it.name) }
            .minByOrNull { it.name } ?: return null
        val modified = audio.lastModified()
        val capture = audio.name.startsWith(CAPTURE_PREFIX)
        val (kind, durationMs) = if (capture) {
            AudioFiles.finishCapture(audio, probe)
        } else {
            AudioFiles.inspectSource(audio, probe)
        }
        // A capture's file was last written when the recording ended; an import's when it was copied.
        val createdAt = if (capture) (modified - durationMs).coerceAtLeast(0) else modified
        return NoteEntity(
            id = dir.name,
            title = recoveredTitle.of(createdAt),
            createdAt = createdAt,
            addedAt = now,
            origin = Origin.RECOVERED,
            audioFile = audio.name,
            audioKind = kind,
            durationMs = durationMs,
            sourceSha256 = if (capture) null else sha256(audio),
            updatedAt = now,
        )
    }

    private enum class Work { NONE, ADOPTED, PAUSED }

    /** Finishes a sealed output, else turns a saved checkpoint into a PAUSED(INTERRUPTED) job. */
    private suspend fun finishWork(id: String): Work {
        val work = paths.work(id)
        val sealed = withContext(dispatchers.io) { File(work, NoteFiles.SEALED_MARKER).isFile }
        if (sealed && adopter.adopt(id) is AdoptOutcome.Adopted) return Work.ADOPTED
        val checkpoint = locks.withNoteLock(id, dispatchers.io) { readCheckpoint(work) }
        if (checkpoint != null) {
            val now = clock()
            jobs.insert(
                JobEntity(
                    noteId = id,
                    seq = (jobs.maxSeq() ?: 0) + 1,
                    kind = if (checkpoint.source == NoteFiles.DOCUMENT) JobKind.TRANSCRIBE_AGAIN else JobKind.TRANSCRIBE,
                    state = JobState.PAUSED,
                    pauseReason = PauseReason.INTERRUPTED,
                    doneMs = checkpoint.doneMs,
                    totalMs = checkpoint.durationMs,
                    words = checkpoint.recognition.chunks.words.size,
                    enqueuedAt = now,
                    updatedAt = now,
                ),
            )
        }
        return if (checkpoint != null) Work.PAUSED else Work.NONE
    }

    /** `work/checkpoint.json` through `AtomicFile` (which falls back to an interrupted write's backup), or null. */
    private fun readCheckpoint(work: File): CheckpointState? {
        val file = File(work, CHECKPOINT)
        if (!file.isFile && !File(work, "$CHECKPOINT.bak").isFile) return null
        return try {
            CheckpointState.decode(JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8)))
        } catch (error: IOException) {
            Log.w(TAG, "Cannot read $file", error)
            null
        } catch (error: JSONException) {
            Log.w(TAG, "Cannot decode $file", error)
            null
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Cannot decode $file", error)
            null
        }
    }

    private fun sha256(file: File): String? = try {
        Sha256.of(file)
    } catch (_: IOException) {
        null
    }

    private companion object {
        const val TAG = "CatalogueRepository"
        const val APP_VENDOR = "cassini-android"
        const val CAPTURE_PREFIX = "capture."
        const val CHECKPOINT = "checkpoint.json"
    }
}

/** [block]'s result, or null after a storage error in [dir], which is logged. */
private suspend fun <T : Any> attempt(dir: File, block: suspend () -> T): T? = try {
    block()
} catch (error: IOException) {
    Log.w("CatalogueRepository", "Cannot catalogue $dir", error)
    null
} catch (error: SQLException) {
    Log.w("CatalogueRepository", "Cannot catalogue $dir", error)
    null
}
