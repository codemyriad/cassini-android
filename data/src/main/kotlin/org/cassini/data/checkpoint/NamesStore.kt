package org.cassini.data.checkpoint

import android.util.Log
import androidx.annotation.WorkerThread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.cassini.core.checkpoint.CheckpointNames
import org.cassini.data.AppPaths
import org.cassini.data.CassiniDispatchers
import org.cassini.data.NoteIds
import org.cassini.data.NoteLocks
import org.cassini.data.Threads
import org.cassini.data.db.JobDao
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

/**
 * The one owner of the draft speaker state of every running or paused note (docs/architecture.md, section 3.6).
 *
 * Each note that is open in this process has one [AtomicReference] of [CheckpointNames], loaded from
 * `work/names.json` the first time it is needed and then kept for the life of the process. Every change, from the UI
 * or from the job, is [AtomicReference.updateAndGet] with a pure reducer of [CheckpointNames] (`confirm`, `reject`,
 * `rename`, `merge`, `remember`, `applyLive`), so no change is lost when two threads change the names at the same
 * time. The file write that follows takes the note's lock and writes the value the reference holds at that moment,
 * never a value a caller read earlier, so the last write always holds the latest state.
 *
 * A reducer can run more than once (a compare-and-set retry), so it must be pure.
 *
 * `names.json` is written only while the note has a job row or a `work/` directory: every work directory is created
 * after its job row (architecture 5.3), so a late UI change after adopt never creates a stray `work/`.
 */
// One owner with an entry point per kind of caller (blocking, suspend, observe, all drafts); each is a few lines.
@Suppress("TooManyFunctions")
class NamesStore internal constructor(
    private val paths: AppPaths,
    private val dispatchers: CassiniDispatchers,
    private val locks: NoteLocks,
    private val jobs: JobDao,
) {
    private class Entry(initial: CheckpointNames) {
        val ref = AtomicReference(initial)
    }

    private val open = ConcurrentHashMap<String, Entry>()

    /** One flow per note for the life of the process: a collector keeps getting values after [release]. */
    private val states = ConcurrentHashMap<String, MutableStateFlow<CheckpointNames>>()

    /** The current draft names of [noteId], loading `names.json` once per process. */
    @WorkerThread
    fun currentBlocking(noteId: String): CheckpointNames {
        Threads.assertOffMain("NamesStore.currentBlocking")
        return entry(noteId).ref.get()
    }

    suspend fun current(noteId: String): CheckpointNames = withContext(dispatchers.io) { entry(noteId).ref.get() }

    /** The draft names of [noteId] after every write; the first value is the current one. */
    fun observe(noteId: String): Flow<CheckpointNames> = flow<CheckpointNames> {
        entry(noteId) // Loads names.json, which sets the first value.
        val state: StateFlow<CheckpointNames> = stateOf(noteId)
        emitAll(state)
    }.flowOn(dispatchers.io)

    /**
     * Applies [reducer] to the draft names of [noteId] and writes the result to `names.json`. Returns the value the
     * reducer produced. For the inference thread.
     */
    @WorkerThread
    fun mutateBlocking(noteId: String, reducer: (CheckpointNames) -> CheckpointNames): CheckpointNames {
        Threads.assertOffMain("NamesStore.mutateBlocking")
        return mutateNow(noteId, reducer)
    }

    /** [mutateBlocking] for coroutine callers: the same lock, taken on the IO dispatcher. */
    suspend fun mutate(noteId: String, reducer: (CheckpointNames) -> CheckpointNames): CheckpointNames =
        withContext(dispatchers.io) { mutateNow(noteId, reducer) }

    /**
     * Applies [reducer] to every draft: the notes open in memory and every `names.json` on disk. For "Forget" and
     * "Forget everyone", which scrub voices from every draft. Returns the note ids it changed.
     */
    suspend fun mutateAll(reducer: (CheckpointNames) -> CheckpointNames): List<String> = withContext(dispatchers.io) {
        val onDisk = paths.notes.listFiles().orEmpty()
            .filter { NoteIds.isValid(it.name) && WorkFiles.exists(File(paths.work(it.name), WorkFiles.NAMES)) }
            .map { it.name }
        (open.keys + onDisk).toSortedSet().filter { id ->
            val before = entry(id).ref.get()
            mutateNow(id, reducer) != before
        }
    }

    /**
     * Forgets the in-memory names of [noteId], so the next use reads `names.json` again. Called after adopt, on purge
     * and when the work directory is discarded, so a later job of the same note starts from what is on disk. Observers
     * get [EMPTY]: the draft they showed is gone.
     */
    fun release(noteId: String) {
        locks.lockFor(noteId).withLock {
            open.remove(noteId)
            states[noteId]?.value = EMPTY
        }
    }

    private fun mutateNow(noteId: String, reducer: (CheckpointNames) -> CheckpointNames): CheckpointNames {
        val entry = entry(noteId)
        val result = entry.ref.updateAndGet(reducer)
        locks.lockFor(noteId).withLock {
            // A change to a draft that was released meanwhile is dropped: it must not bring the old names back.
            if (open[noteId] !== entry) return result
            // Read inside the lock: a later change that already wrote is never overwritten by an older value.
            val latest = entry.ref.get()
            if (writable(noteId)) {
                try {
                    WorkFiles.write(File(paths.work(noteId), WorkFiles.NAMES), CheckpointNames.encode(latest))
                } catch (error: IOException) {
                    // The value in memory stays the truth; the next change writes it again.
                    Log.w(NAMES_TAG, "Cannot write the draft names of $noteId", error)
                }
            }
            stateOf(noteId).value = latest
        }
        return result
    }

    private fun stateOf(noteId: String): MutableStateFlow<CheckpointNames> =
        states.computeIfAbsent(noteId) { MutableStateFlow(EMPTY) }

    /** Every work directory is created after its job row (architecture 5.3). */
    private fun writable(noteId: String): Boolean =
        paths.work(noteId).isDirectory || (paths.note(noteId).isDirectory && jobs.jobBlocking(noteId) != null)

    private fun entry(noteId: String): Entry {
        require(NoteIds.isValid(noteId)) { "Not a note id: $noteId" }
        open[noteId]?.let { return it }
        val file = File(paths.work(noteId), WorkFiles.NAMES)
        return locks.lockFor(noteId).withLock {
            open.getOrPut(noteId) {
                val names = readNames(file, noteId)
                stateOf(noteId).value = names
                Entry(names)
            }
        }
    }

    companion object {
        /** The names of a draft that nobody has named yet. */
        val EMPTY = CheckpointNames(labels = emptyMap(), merges = emptyMap())
    }
}

/** `names.json` at [file]; a missing or unreadable file reads as [NamesStore.EMPTY] (the next write replaces it). */
private fun readNames(file: File, noteId: String): CheckpointNames {
    if (!WorkFiles.exists(file)) return NamesStore.EMPTY
    return try {
        CheckpointNames.decode(JSONObject(WorkFiles.read(file)))
    } catch (error: IOException) {
        Log.w(NAMES_TAG, "Cannot read the draft names of $noteId", error)
        NamesStore.EMPTY
    } catch (error: JSONException) {
        Log.w(NAMES_TAG, "Cannot parse the draft names of $noteId", error)
        NamesStore.EMPTY
    } catch (error: IllegalArgumentException) {
        Log.w(NAMES_TAG, "Cannot parse the draft names of $noteId", error)
        NamesStore.EMPTY
    }
}

private const val NAMES_TAG = "NamesStore"

/**
 * The draft names of one note (architecture 3.6): a handle on [NamesStore] for the job's `JobStorage.names` port and
 * the Note screen. P5 adds `close()`, which ends naming at the attribution step.
 */
class DraftNames internal constructor(val noteId: String, private val store: NamesStore) {
    @WorkerThread
    fun currentBlocking(): CheckpointNames = store.currentBlocking(noteId)

    suspend fun current(): CheckpointNames = store.current(noteId)

    fun observe(): Flow<CheckpointNames> = store.observe(noteId)

    @WorkerThread
    fun mutateBlocking(reducer: (CheckpointNames) -> CheckpointNames): CheckpointNames =
        store.mutateBlocking(noteId, reducer)

    suspend fun mutate(reducer: (CheckpointNames) -> CheckpointNames): CheckpointNames = store.mutate(noteId, reducer)
}
