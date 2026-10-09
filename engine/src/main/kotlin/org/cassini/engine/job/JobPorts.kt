package org.cassini.engine.job

import org.cassini.core.checkpoint.CheckpointNames
import org.cassini.core.checkpoint.CheckpointState
import org.cassini.core.format.CassiniDocument
import org.cassini.core.models.ModelPart
import org.cassini.core.models.ModelPlan
import org.cassini.core.speakers.SpeakerPrint
import org.cassini.core.speakers.Voice
import java.io.File
import java.util.concurrent.atomic.AtomicReference

// The storage ports of TranscriptionJob (architecture section 5.4). :engine does not depend on :data: :app implements
// these interfaces with adapters over the :data repositories, and JVM tests use fakes.
//
// Threading: a `suspend` function may be called from any coroutine and moves to IO itself. Every other function blocks
// and is called from a worker thread only, in practice the inference thread (InferenceDispatcher); functions named
// `…Blocking` exist for that thread. None of them is ever called on the main thread, and none suspends while holding a
// lock (architecture section 7).

/** What [JobStorage.loadCheckpoint] found in `notes/<id>/work/`. */
sealed interface CheckpointLoad {
    /** No checkpoint: the job starts from the beginning. */
    data object Missing : CheckpointLoad

    /** A checkpoint that passed `CheckpointState.validate` against the note row and the installed models. */
    data class Valid(val state: CheckpointState) : CheckpointLoad

    /** A checkpoint that exists but no longer matches; the job fails with CHECKPOINT_MISMATCH. */
    data class Mismatch(val reason: String) : CheckpointLoad
}

/**
 * What adopt needs besides the sealed file: the final speaker [labels], the draft "Yes, Luca" choices to enrol
 * ([remembered], speaker to voice id, "" for a new voice) with the voices [generation] they were made in, and the
 * measured [finalizationMs] (matching plus saving) for the running estimate.
 */
data class Adoption(
    val labels: Map<String, String>,
    val remembered: Map<String, String>,
    val generation: Long,
    val finalizationMs: Long,
)

/** The files a job writes in `notes/<id>/work/`. The directory exists when [JobStorage.workFiles] returns. */
data class WorkFiles(
    /** `audio.opus.part`: the libopus re-encode of a non-Opus source. */
    val encodedAudio: File,
    /** `clips.pcm`: the selected voice clips, raw 16 kHz f32le. */
    val clips: File,
)

/** A note's files and draft state as one job sees them. Every function takes the note id. */
@Suppress("TooManyFunctions") // one port per architecture section 5.4; splitting it would only spread the note's files
interface JobStorage {
    /** Loads and validates `work/checkpoint.json` (IO). */
    suspend fun loadCheckpoint(noteId: String): CheckpointLoad

    /**
     * Writes [state] as `work/checkpoint.json` (AtomicFile, with `.bak`). Blocking, inference thread. The job calls it
     * only after `audio.opus.part` and `clips.pcm` are fsynced.
     */
    fun saveCheckpoint(noteId: String, state: CheckpointState)

    /**
     * The note's draft names (`DraftNames`, architecture section 3.6). The job only reads the reference; every change
     * goes through [mutateNames]. Any thread.
     */
    fun names(noteId: String): AtomicReference<CheckpointNames>

    /**
     * Applies [reducer] with `updateAndGet` and writes `names.json` under the note's lock, reading the reference inside
     * the lock. Returns the new value. Blocking, inference thread. [reducer] is pure and may run more than once.
     */
    fun mutateNames(noteId: String, reducer: (CheckpointNames) -> CheckpointNames): CheckpointNames

    /**
     * Closes naming (step 6, `DraftNames.close()`): later changes from the UI are refused, and the returned value is
     * final for this run. Blocking, inference thread.
     */
    fun closeNames(noteId: String): CheckpointNames

    /** The note's current audio file (`capture.*`, `source.*` or `document.opus`), from the row (IO). */
    suspend fun audioFile(noteId: String): File

    /** Creates `work/` when needed and names its files. Blocking, inference thread. */
    fun workFiles(noteId: String): WorkFiles

    /** `work/sealed.opus`, the only document file a job writes. Never `document.opus`. Any thread. */
    fun sealTarget(noteId: String): File

    /**
     * Runs [block] holding the note's lock (`NoteLocks`), so the existing document and the title read for the seal
     * cannot change meanwhile. Blocking, inference thread; [block] must not suspend.
     */
    fun <T> withNoteLockBlocking(noteId: String, block: () -> T): T

    /** The sealed document a Transcribe again adds a variant to, or null for a plain note. Blocking. */
    fun existingDocumentBlocking(noteId: String): CassiniDocument?

    /** The note's title (`notes.title`), for a fresh seal. Blocking. */
    fun titleBlocking(noteId: String): String

    /**
     * Writes the `work/sealed` marker, fsynced, after the job verified [sealTarget]. From then on adopt may install it,
     * also after a crash. Blocking, inference thread.
     */
    fun markSealedBlocking(noteId: String)

    /** Deletes the marker, then [sealTarget] (a stop during finalization); the checkpoint stays. Blocking. */
    fun discardSealedBlocking(noteId: String)

    /**
     * Installs the sealed file as `document.opus`, updates the row and the cache, retitles a dirty title, merges the
     * pending prints when the voices generation is unchanged, enrols the draft choices of [adoption], stores the
     * finalization estimate, then deletes `work/`. Idempotent. The job runs it in `NonCancellable` (IO).
     */
    suspend fun adopt(noteId: String, adoption: Adoption)
}

/** Saved voices as the job uses them (`VoiceRepository`). */
interface JobVoices {
    /** The saved people (`voices/people.json`). Blocking. */
    fun peopleBlocking(): List<Voice>

    /** The voices generation, bumped by "Forget everyone". Blocking. */
    fun generationBlocking(): Long

    /**
     * The sealed prints and matches of the note's selected variant (`voices/notes/<noteId>.json`), keyed by speaker
     * id; empty for a plain note. Transcribe again turns the named speakers among them into note-local voices, so
     * that speaker names carry over (0.1.0 `voicesForRecognition`). Blocking.
     */
    fun noteVoicesBlocking(noteId: String): Map<String, SpeakerPrint>

    /**
     * Writes `voices/pending/<noteId>.json`: the final [prints] and matches of [variantId] for print [model], with the
     * [generation] they were matched in. Adopt merges them only if the generation is still the same. Blocking,
     * inference thread; called before the sealed marker.
     */
    fun savePendingBlocking(
        noteId: String,
        variantId: String,
        model: String,
        prints: Map<String, SpeakerPrint>,
        generation: Long,
    )
}

/** Models as the job sees them (`ModelRepository` plus the app's one install owner). */
interface ModelAccess {
    /** Consent recorded, or Parakeet installed (architecture section 5.6). Reads DataStore (IO). */
    suspend fun consented(): Boolean

    /** Whether [part]'s primary model is installed and verified. Blocking file checks; not on the main thread. */
    fun ready(part: ModelPart): Boolean

    /** The missing parts and their sizes. Blocking file checks; not on the main thread. */
    fun plan(): ModelPlan

    /** Whether the active network has `NET_CAPABILITY_VALIDATED`. Any thread. */
    fun hasValidatedNetwork(): Boolean

    /**
     * Installs the missing [parts] through `ModelInstallOwner`, the only caller of [ModelInstaller]: a request while an
     * install runs joins it. [onProgress] is called on a background thread. Cancelling the caller stops waiting and
     * keeps the `.part` files.
     */
    suspend fun install(parts: Set<ModelPart>, onProgress: (DownloadProgress) -> Unit): InstallOutcome
}
