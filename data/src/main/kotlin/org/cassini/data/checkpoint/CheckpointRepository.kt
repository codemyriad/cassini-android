package org.cassini.data.checkpoint

import androidx.annotation.WorkerThread
import kotlinx.coroutines.withContext
import org.cassini.core.audio.Limits
import org.cassini.core.checkpoint.CheckpointState
import org.cassini.core.format.SpeakerMerges
import org.cassini.core.format.Transcript
import org.cassini.core.speakers.DefaultLabels
import org.cassini.data.AppPaths
import org.cassini.data.CassiniDispatchers
import org.cassini.data.NoteIds
import org.cassini.data.NoteLocks
import org.cassini.data.Threads
import org.cassini.data.db.CassiniDatabase
import org.cassini.data.notes.NoteFiles
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The resumable state of a note's transcription in `notes/<id>/work/` (docs/architecture.md, sections 3.5, 3.6 and
 * 4.4): `checkpoint.json` and `names.json` written through `AtomicFile`, the append-only `audio.opus.part` and
 * `clips.pcm` that the job writes itself, and the `sealed.opus` + `sealed` marker of a finished job.
 *
 * Every write holds the note's lock from [NoteLocks]. The `…Blocking` entry points are for the inference thread: they
 * refuse the main thread and take the lock directly. The suspend ones take the same lock inside the IO dispatcher and
 * never suspend while they hold it.
 *
 * The checkpoint commits the lengths of the two append-only files, so [saveBlocking] fsyncs them before it writes
 * `checkpoint.json`: after a crash the files are at least as long as the checkpoint says.
 */
// Manual DI: the repositories take their collaborators explicitly; one API per area of the work directory.
@Suppress("TooManyFunctions")
class CheckpointRepository(
    database: CassiniDatabase,
    private val paths: AppPaths,
    private val dispatchers: CassiniDispatchers,
    private val locks: NoteLocks,
    /** Flushes a file to the disk; a seam for the test of the order in [saveBlocking]. */
    private val syncFile: (File) -> Unit = NoteFiles::syncFile,
) {
    private val notes = database.notes()

    /** The owner of every draft's names (architecture 3.6). */
    val names = NamesStore(paths, dispatchers, locks, database.jobs())

    /** The draft names of [noteId]; `names.json` is loaded once per process. */
    fun draftNames(noteId: String): DraftNames = DraftNames(noteId, names)

    /** `work/audio.opus.part`: the libopus output for a non-Opus source, appended by the job. */
    fun audioPart(noteId: String): File = File(work(noteId), WorkFiles.AUDIO_PART)

    /** `work/clips.pcm`: the selected voice clips, raw 16 kHz f32le, appended by the job. */
    fun clips(noteId: String): File = File(work(noteId), WorkFiles.CLIPS)

    /** Creates `work/` for a job that starts. The caller has created the job row first (architecture 5.3). */
    @WorkerThread
    fun openWorkBlocking(noteId: String): File {
        Threads.assertOffMain("CheckpointRepository.openWorkBlocking")
        return locks.withNoteLockBlocking(noteId) { work(noteId).apply { mkdirs() } }
    }

    /** Loads and validates the checkpoint of [noteId] against [expected] and the on-disk file lengths. */
    @WorkerThread
    fun loadBlocking(noteId: String, expected: CheckpointIdentity): CheckpointLoad {
        Threads.assertOffMain("CheckpointRepository.loadBlocking")
        return locks.withNoteLockBlocking(noteId) { loadLocked(noteId, expected) }
    }

    /** [loadBlocking] with the identity built from the note row; [CheckpointLoad.Missing] when the row is gone. */
    suspend fun load(noteId: String): CheckpointLoad {
        val identity = identity(noteId) ?: return CheckpointLoad.Missing
        return locks.withNoteLock(noteId, dispatchers.io) { loadLocked(noteId, identity) }
    }

    /** The identity a checkpoint of [noteId] must have now: the row's audio file and this build's models. */
    suspend fun identity(noteId: String): CheckpointIdentity? = withContext(dispatchers.io) {
        notes.note(noteId)?.let { CheckpointIdentity.of(paths.note(noteId), it.audioFile) }
    }

    /**
     * Commits [state] as the checkpoint of [noteId]: fsyncs `audio.opus.part` and `clips.pcm` (whose lengths the
     * state commits), then writes `checkpoint.json` atomically.
     */
    @WorkerThread
    fun saveBlocking(noteId: String, state: CheckpointState) {
        Threads.assertOffMain("CheckpointRepository.saveBlocking")
        require(state.version == CheckpointState.VERSION) { "Checkpoint version ${state.version}" }
        locks.withNoteLockBlocking(noteId) {
            val work = work(noteId).apply { mkdirs() }
            listOf(WorkFiles.AUDIO_PART, WorkFiles.CLIPS).map { File(work, it) }.filter { it.isFile }
                .forEach(syncFile)
            WorkFiles.write(File(work, WorkFiles.CHECKPOINT), CheckpointState.encode(state))
        }
    }

    /**
     * Deletes the whole work directory of [noteId] (checkpoint, names, clips, audio part and any sealed output) and
     * forgets its draft names in memory: "Start over", the end of a cancelled job, and purge. `voices/pending/` is
     * `VoiceRepository`'s: the caller also runs `VoiceRepository.discardPending` (architecture 5.2 rule 8).
     *
     * The `sealed` marker goes first and durably: a marker without `sealed.opus` means "the rename happened" to adopt,
     * so a crash after `sealed.opus` went but before the marker went would install the old document as the output.
     */
    suspend fun discard(noteId: String) {
        require(NoteIds.isValid(noteId)) { "Not a note id: $noteId" }
        locks.withNoteLock(noteId, dispatchers.io) {
            names.releaseLocked(noteId)
            val work = work(noteId)
            if (File(work, WorkFiles.SEALED_MARKER).delete()) NoteFiles.syncDirectory(work)
            work.deleteRecursively()
        }
    }

    /** Forgets the in-memory draft names of [noteId] after adopt deleted `work/`. */
    suspend fun release(noteId: String) = names.release(noteId)

    /** Whether `work/sealed` marks a finished `sealed.opus` that waits for adopt. */
    suspend fun sealedMarker(noteId: String): Boolean =
        withContext(dispatchers.io) { File(work(noteId), WorkFiles.SEALED_MARKER).isFile }

    /** Whether `work/` exists. */
    suspend fun hasWork(noteId: String): Boolean = withContext(dispatchers.io) { work(noteId).isDirectory }

    /** What the work directory of [noteId] holds: a sealed output, a resumable checkpoint, a mismatch, or nothing. */
    suspend fun status(noteId: String): CheckpointStatus {
        val identity = identity(noteId)
        return when {
            sealedMarker(noteId) -> CheckpointStatus.SealedAwaitingAdopt
            identity == null -> CheckpointStatus.NoNote
            else -> when (val load = locks.withNoteLock(noteId, dispatchers.io) { loadLocked(noteId, identity) }) {
                CheckpointLoad.Missing -> CheckpointStatus.None
                is CheckpointLoad.Mismatch -> CheckpointStatus.Mismatch(load.reason)
                is CheckpointLoad.Valid -> CheckpointStatus.Saved(
                    load.state.doneMs,
                    load.state.durationMs,
                    load.state.recognition.chunks.words.size,
                )
            }
        }
    }

    /**
     * The saved words of a paused note with its draft names: the speakers with the merges resolved, and the labels.
     * Null without a checkpoint that decodes. It does not validate the checkpoint: the words are shown, not resumed.
     */
    suspend fun loadDraft(noteId: String): DraftTranscript? {
        val state = locks.withNoteLock(noteId, dispatchers.io) { decodeLocked(noteId) } ?: return null
        val names = names.current(noteId)
        val words = SpeakerMerges.apply(Transcript(state.recognition.chunks.words), names.merges).words
        // A stored placeholder ("Speaker 2", "Voce 2") is not a name: the screen shows its own localized placeholder.
        val labels = words.map { it.speaker }.distinct().mapNotNull { id ->
            listOfNotNull(names.labels[id], names.automatic[id])
                .firstOrNull { !DefaultLabels.isDefault(id, it) }
                ?.let { id to it }
        }.toMap()
        return DraftTranscript(words, labels, names, state.doneMs, state.durationMs)
    }

    private fun work(noteId: String): File {
        require(NoteIds.isValid(noteId)) { "Not a note id: $noteId" }
        return paths.work(noteId)
    }

    private fun decodeLocked(noteId: String): CheckpointState? {
        val file = File(work(noteId), WorkFiles.CHECKPOINT)
        if (!WorkFiles.exists(file)) return null
        return try {
            CheckpointState.decode(JSONObject(WorkFiles.read(file)))
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun loadLocked(noteId: String, identity: CheckpointIdentity): CheckpointLoad {
        val work = work(noteId)
        val file = File(work, WorkFiles.CHECKPOINT)
        return if (WorkFiles.exists(file)) validate(file, expected(work, identity)) else CheckpointLoad.Missing
    }

    private fun expected(work: File, identity: CheckpointIdentity) = CheckpointState.Expected(
        identity.source,
        identity.signature,
        identity.modelRevision,
        identity.speakersSha256,
        File(work, WorkFiles.CLIPS).length(),
        File(work, WorkFiles.AUDIO_PART).length(),
    )

    /** Reads [file] and checks it with `CheckpointState.validate`; every failure is a [CheckpointLoad.Mismatch]. */
    private fun validate(file: File, expected: CheckpointState.Expected): CheckpointLoad = try {
        val json = JSONObject(WorkFiles.read(file))
        try {
            CheckpointState.validate(json, expected)
            CheckpointLoad.Valid(CheckpointState.decode(json))
        } catch (error: IllegalArgumentException) {
            CheckpointLoad.Mismatch(reason(json, expected), error.message)
        }
    } catch (error: IOException) {
        CheckpointLoad.Mismatch(MismatchReason.UNREADABLE, error.message)
    } catch (error: JSONException) {
        CheckpointLoad.Mismatch(MismatchReason.UNREADABLE, error.message)
    }

    /**
     * Which rule of `CheckpointState.validate` failed, checked in its order. Validate stays the authority on whether a
     * checkpoint resumes; this only names the reason for diagnostics and tests.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun reason(json: JSONObject, expected: CheckpointState.Expected): MismatchReason {
        val clipSamples = json.optLong("clipSamples", -1)
        val encoderLength = json.optJSONObject("encoder")?.optLong("length", -1)
        return when {
            json.optInt("version", -1) != CheckpointState.VERSION -> MismatchReason.VERSION
            json.optString("source") != expected.source -> MismatchReason.SOURCE
            json.optString("signature") != expected.signature -> MismatchReason.SIGNATURE
            json.optString("model") != expected.modelRevision -> MismatchReason.MODEL
            json.optString("speakers") != expected.speakersSha256 -> MismatchReason.SPEAKERS
            clipSamples !in 0L..CheckpointState.MAX_CLIP_SAMPLES.toLong() ||
                expected.clipsBytes < clipSamples * Float.SIZE_BYTES -> MismatchReason.CLIPS
            json.optLong("positionSamples", -1) !in 0L..Limits.MAX_SAMPLES.toLong() ||
                json.optLong("doneMs", -1) !in 0..Limits.MAX_RECORDING_MS -> MismatchReason.RANGE
            encoderLength != null && (encoderLength < 0 || expected.audioBytes < encoderLength) -> MismatchReason.AUDIO
            else -> MismatchReason.UNREADABLE
        }
    }
}
