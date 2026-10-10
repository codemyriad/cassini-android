package org.cassini.android.processing

import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cassini.android.AppGraph
import org.cassini.android.Catalogue
import org.cassini.core.Failure
import org.cassini.core.checkpoint.CheckpointNames
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.library.PauseReason
import org.cassini.core.models.ModelPart
import org.cassini.data.checkpoint.CheckpointStatus
import org.cassini.data.db.QueueRow
import org.cassini.data.jobs.ClaimOutcome
import org.cassini.data.jobs.EnqueueOutcome
import org.cassini.data.jobs.EnqueueRefusal
import org.cassini.data.settings.QueueHold
import org.cassini.engine.job.BatterySource
import org.cassini.engine.job.DraftSources
import org.cassini.engine.job.JobFailure
import org.cassini.engine.job.JobOutcome
import org.cassini.engine.job.JobPhase
import org.cassini.engine.job.ProgressTracker
import java.util.concurrent.atomic.AtomicReference

/** Where a Transcribe, Resume or Start over came from; it decides which queue hold it clears (architecture 5.2). */
enum class StartOrigin {
    /** A visible screen: clears USER, and SYSTEM on a Resume. */
    SCREEN,

    /** A notification action: clears USER. */
    NOTIFICATION,

    /** The app itself (the enqueue after Finish): clears nothing. */
    AUTOMATIC,
}

/** The result of [ProcessingCoordinator.transcribe]. */
sealed interface TranscribeOutcome {
    /** The note is QUEUED (or it was QUEUED or RUNNING already). */
    data class Queued(val changed: Boolean) : TranscribeOutcome

    /** No model download consent: the job is NEEDS_CONSENT, no network call was made. */
    data object NeedsConsent : TranscribeOutcome

    data class Refused(val reason: EnqueueRefusal) : TranscribeOutcome
}

/** The result of [ProcessingCoordinator.dequeue] ("Remove from queue", architecture 5.2 rule 7). */
enum class DequeueOutcome {
    /** The row was deleted: the note is "Audio only" again. */
    REMOVED,

    /** The note has a checkpoint: PAUSED(USER). */
    PAUSED,

    /** The note has no QUEUED job (it may have started meanwhile). */
    NOT_QUEUED,
}

/** The result of a draft-name action. */
sealed interface DraftOutcome {
    data class Changed(val names: CheckpointNames) : DraftOutcome

    /** Naming is closed from the attribute step on ("Saving — you can rename when it's done"). */
    data object Closed : DraftOutcome
}

/**
 * The transcription queue of architecture 5.2-5.6: queue transitions, the drain loop, [state], draft actions and
 * holds. One instance per process (`AppGraph.processing`).
 *
 * - The drain loop ([drain]) claims one job at a time in FIFO order and runs it with its `ProgressTracker`, then
 *   writes the outcome and claims the next, until the queue is empty or held. It runs on the application scope; the
 *   foreground service only keeps the process in the foreground while it runs.
 * - Every queue transition (claim, outcome, dequeue, trash) runs under one coroutine [Mutex] over Room calls. The
 *   draft names are not under it: each draft action is one `NamesStore.mutate` with a pure `:core` reducer, so a
 *   change from the UI and a live update from the job never lose each other (architecture 3.6).
 * - The startup work (`AppGraph.ready`) runs `reconcileOnStart` before anything here reads the queue; [state] is
 *   [ProcessingState.loading] until then.
 */
@Suppress("TooManyFunctions") // the queue API of architecture 5.2: one function per user action and service call
class ProcessingCoordinator internal constructor(private val graph: AppGraph, private val parts: Parts) {
    /** The seams of the coordinator; [AppGraph] passes the app's implementations, tests pass fakes. */
    @Suppress("LongParameterList") // one seam per collaborator of architecture 5.1
    class Parts(
        val jobs: ProcessingJobFactory,
        val starter: ServiceStarter,
        val notices: ProcessingNotices,
        val visibility: AppVisibility,
        val battery: BatterySource,
        val placeholderLabel: (Int) -> String,
        val sdkInt: Int = Build.VERSION.SDK_INT,
    )

    /**
     * How an interrupted job ends. DISCARD: Start over (the work goes, for any outcome but a sealed one). KEEP:
     * PAUSED(USER) with the checkpoint. RESTORE: Cancel of a download, the note goes back to its state from before.
     */
    private enum class Cancel { DISCARD, KEEP, RESTORE }

    /** The job that runs now. [run] starts when it is claimed; [finished] completes after its outcome is written. */
    private class Active(val noteId: String, val title: String, val job: ProcessingJob, val run: Deferred<JobOutcome>) {
        val finished = CompletableDeferred<Unit>()

        /** How the job ends when it is interrupted; null for a plain stop. Read once per outcome, in [record]. */
        val cancel = AtomicReference<Cancel?>(null)

        @Volatile var tracker: ProgressTracker? = null
    }

    private data class Work(
        val draining: Boolean = false,
        val downloading: Boolean = false,
        val foreground: Boolean = false,
    )

    private val scope = graph.appScope
    private val settings = graph.settings

    /** Guards the queue transitions; never held while a job runs. */
    private val queueLock = Mutex()

    /** Guards [drainJob], [rerun] and [downloadJob]. */
    private val workLock = Any()
    private var drainJob: Job? = null
    private var rerun = false
    private var downloadJob: Job? = null

    @Volatile private var active: Active? = null

    private val running = MutableStateFlow<RunningJob?>(null)
    private val work = MutableStateFlow(Work())
    private val voiceNames = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Every job of a live note in queue order; null until the startup work has ended. */
    private val rows: Flow<List<QueueRow>?> = flow<List<QueueRow>?> {
        emit(null)
        graph.ready.await()
        emitAll(graph.awaitCatalogue().jobs.queue())
    }.catch { error -> Log.e(TAG, "The queue cannot be read", error) }

    val state: StateFlow<ProcessingState> = combine(
        rows,
        running,
        settings.queueHold,
        graph.installOwner.state,
        work,
    ) { rows, running, hold, install, work ->
        build(rows, running, hold, install, work)
    }.stateIn(scope, SharingStarted.Eagerly, ProcessingState())

    /** The foreground service type the current work needs (architecture 5.7). */
    val foregroundNeed: StateFlow<ForegroundNeed> = state
        .map { ProcessingState.foregroundNeed(it, parts.sdkInt) }
        .stateIn(scope, SharingStarted.Eagerly, ForegroundNeed.NONE)

    init {
        scope.launch { watchVoices() }
        scope.launch { parts.visibility.visible.collect { visible -> if (visible) onAppStarted() } }
        scope.launch { announceInterrupted() }
    }

    // Queue actions (architecture 5.2). ---------------------------------------------------------------------------

    /**
     * Transcribe, Transcribe again, Resume and Try again: puts the note at the back of the queue and starts the
     * service when nothing holds the queue. Without consent the job becomes NEEDS_CONSENT and nothing is downloaded
     * (architecture 5.6). [startService] is false for the recording hand-off, which starts the service itself.
     */
    suspend fun transcribe(
        noteId: String,
        kind: JobKind = JobKind.TRANSCRIBE,
        origin: StartOrigin = StartOrigin.SCREEN,
        startService: Boolean = true,
    ): TranscribeOutcome {
        graph.ready.await()
        val catalogue = graph.awaitCatalogue()
        // A job stopped by Android still finishes in the background; its outcome must not overwrite this request.
        active?.takeIf { it.noteId == noteId && catalogue.jobs.job(noteId)?.state != JobState.RUNNING }
            ?.finished?.await()
        if (!graph.models.consented()) return queueLock.withLock { needConsent(catalogue, noteId, kind) }
        val resume = catalogue.jobs.job(noteId)?.state == JobState.PAUSED
        clearHold(origin, resume)
        val outcome = when (val enqueued = queueLock.withLock { catalogue.jobs.enqueue(noteId, kind) }) {
            is EnqueueOutcome.Queued -> TranscribeOutcome.Queued(changed = true)
            is EnqueueOutcome.Unchanged -> TranscribeOutcome.Queued(changed = false)
            is EnqueueOutcome.Refused -> TranscribeOutcome.Refused(enqueued.reason)
        }
        if (outcome is TranscribeOutcome.Queued && startService) startService()
        return outcome
    }

    /** "Stop and keep progress" on the note that runs now (rule 5). False when [noteId] does not run. */
    fun stop(noteId: String): Boolean {
        val current = active?.takeIf { it.noteId == noteId } ?: return false
        current.job.stop(PauseReason.USER)
        return true
    }

    /** "Stop" in the notification (rule 6): holds the queue (USER), then stops the running note. */
    suspend fun stopAll() {
        settings.setQueueHold(QueueHold.USER)
        active?.job?.stop(PauseReason.USER)
    }

    /** Continue: clears the hold and starts the queue. */
    suspend fun continueQueue() {
        settings.setQueueHold(null)
        startService()
    }

    /** "Remove from queue" on a QUEUED note (rule 7). */
    suspend fun dequeue(noteId: String): DequeueOutcome {
        val catalogue = graph.awaitCatalogue()
        return queueLock.withLock {
            val job = catalogue.jobs.job(noteId)
            when {
                job?.state != JobState.QUEUED -> DequeueOutcome.NOT_QUEUED
                markPausedIfSaved(catalogue, noteId, PauseReason.USER) -> DequeueOutcome.PAUSED
                else -> {
                    catalogue.jobs.remove(noteId)
                    DequeueOutcome.REMOVED
                }
            }
        }
    }

    /** Start over: cancels the job if it runs, discards the checkpoint and the pending prints, then enqueues. */
    suspend fun startOver(noteId: String, origin: StartOrigin = StartOrigin.SCREEN): TranscribeOutcome {
        val kind = graph.awaitCatalogue().jobs.job(noteId)?.kind ?: JobKind.TRANSCRIBE
        cancel(noteId, discard = true)
        return transcribe(noteId, kind, origin)
    }

    /**
     * The internal Cancel of rule 8: interrupts the job of [noteId] and waits for its end. With [discard] the work
     * directory and `voices/pending/<id>.json` go after `join()` and the row is removed; without it the row becomes
     * PAUSED(USER) with its checkpoint. A note that does not run only loses its work with [discard]. Returns whether
     * the note was running.
     */
    suspend fun cancel(noteId: String, discard: Boolean): Boolean {
        val catalogue = graph.awaitCatalogue()
        val current = queueLock.withLock {
            // The flag is set under the lock: the outcome is written under it too, so it always sees the flag.
            active?.takeIf { it.noteId == noteId }?.also { it.cancel.set(if (discard) Cancel.DISCARD else Cancel.KEEP) }
                ?: run {
                    if (discard) discardWork(catalogue, noteId)
                    null
                }
        } ?: return false
        current.run.cancel(CancellationException("Cancelled by the coordinator"))
        current.finished.await()
        return true
    }

    /** `RunningJobCanceller` for the purge (architecture 4.10 step 1). */
    suspend fun cancelAndJoin(noteId: String) {
        cancel(noteId, discard = false)
    }

    /**
     * Before Move to trash (rule 9): a RUNNING job is stopped as "Stop and keep progress" and this waits until it is
     * PAUSED(USER). A QUEUED or PAUSED job needs nothing here; the trash itself deletes a QUEUED row.
     */
    suspend fun prepareTrash(noteId: String) {
        val current = active?.takeIf { it.noteId == noteId } ?: return
        current.job.stop(PauseReason.USER)
        current.finished.await()
    }

    /** [prepareTrash], then `NoteRepository.moveToTrash` under the queue lock, so the note cannot start meanwhile. */
    suspend fun moveToTrash(noteId: String): Boolean {
        val catalogue = graph.awaitCatalogue()
        while (true) {
            prepareTrash(noteId)
            val trashed = queueLock.withLock {
                if (active?.noteId == noteId) null else catalogue.notes.moveToTrash(noteId)
            }
            if (trashed != null) return trashed
        }
    }

    /**
     * Undo and Restore of a trashed note (rule 2): `NoteRepository.restore` under the queue lock. A job that was
     * QUEUED when the note was trashed is QUEUED again, and the queue starts as after a Transcribe.
     */
    suspend fun restore(noteId: String): Boolean {
        val catalogue = graph.awaitCatalogue()
        val restored = queueLock.withLock { catalogue.notes.restore(noteId) }
        if (restored && catalogue.jobs.job(noteId)?.state == JobState.QUEUED && graph.models.consented()) {
            startService()
        }
        return restored
    }

    /**
     * `onTimeout` of the service (architecture 5.7): holds the queue (SYSTEM), stops the running job (it saves a
     * checkpoint at its next block) and writes its row PAUSED(SYSTEM) at once, so every screen shows Paused.
     */
    suspend fun systemStop(noteId: String?) {
        settings.setQueueHold(QueueHold.SYSTEM)
        val current = active?.takeIf { noteId == null || it.noteId == noteId } ?: return
        current.job.stop(PauseReason.SYSTEM)
        val catalogue = graph.awaitCatalogue()
        // Under the lock, and only while the job runs: an outcome that record() wrote meanwhile stays.
        queueLock.withLock {
            if (active === current && catalogue.jobs.job(current.noteId)?.state == JobState.RUNNING) {
                markPaused(catalogue, current.noteId, PauseReason.SYSTEM)
            }
        }
    }

    /** [systemStop] from `onTimeout`, which must return at once. */
    fun systemStopAsync(noteId: String?) {
        scope.launch { guarded("stop for Android") { systemStop(noteId) } }
    }

    /** The draft-name actions of [noteId] (features.md section 3.1 and 4). */
    fun draft(noteId: String): DraftActions = DraftActions(noteId)

    // Models (architecture 5.6). ------------------------------------------------------------------------------------

    /** The standalone download through `ModelInstallOwner`, inside the foreground service. */
    fun downloadModels(parts: Set<ModelPart> = ModelPart.entries.toSet()): Job {
        val job = graph.installOwner.startDownload(parts)
        synchronized(workLock) { downloadJob = job }
        work.update { it.copy(downloading = true) }
        job.invokeOnCompletion {
            val idle = synchronized(workLock) {
                if (downloadJob === job) downloadJob = null
                downloadJob == null
            }
            if (idle) work.update { it.copy(downloading = false) }
        }
        this.parts.starter.start()
        return job
    }

    /**
     * Cancel in the download banner or notification (features.md 13.4 DL_DOWNLOADING): a job that downloads is
     * stopped, its `.part` files stay, and the note goes back to its state from before: PAUSED(USER) when it has a
     * checkpoint, else the row goes ("Audio only" for a first Transcribe). Otherwise the shared install is cancelled.
     */
    fun cancelDownload() {
        val current = active
        if (current != null && running.value?.phase is JobPhase.Downloading) {
            // Atomic: a Start over at the same moment keeps its DISCARD.
            current.cancel.compareAndSet(null, Cancel.RESTORE)
            current.job.stop(PauseReason.USER)
        } else {
            graph.installOwner.cancel()
        }
    }

    // The service side. -------------------------------------------------------------------------------------------

    /**
     * Starts the drain loop, or returns the one that runs (it then looks at the queue again before it ends). The
     * service calls it for ACTION_DRAIN and for a redelivered intent.
     */
    fun drain(): Job = synchronized(workLock) {
        drainJob?.let { running ->
            rerun = true
            return running
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { drainLoop() }
        drainJob = job
        work.update { it.copy(draining = true) }
        job.invokeOnCompletion { endDrain(job) }
        job.start()
        job
    }

    /** Suspends until no drain loop and no standalone download runs. The service stops only after it returns. */
    suspend fun awaitIdle() {
        while (true) {
            val pending = synchronized(workLock) { listOfNotNull(drainJob, downloadJob) }
            if (pending.isEmpty()) return
            pending.joinAll()
        }
    }

    /** Set by the service right after `startForeground` returned, and cleared when it leaves the foreground. */
    fun onServiceForeground(foreground: Boolean) {
        work.update { it.copy(foreground = foreground) }
    }

    /** Android refused the service start: the queue waits, and the user is told ("Ready to transcribe"). */
    fun onServiceRefused() {
        scope.launch {
            guarded("tell that the queue waits") {
                // A refused first start in a new process comes before the startup work has read the queue.
                val head = state.first { !it.loading }.queue.firstOrNull() ?: return@guarded
                notifyHidden { readyToTranscribe(head.noteId, head.title) }
            }
        }
    }

    /**
     * ON_START of the process (architecture 5.3): the visible app starts the service when QUEUED rows exist,
     * nothing runs, consent exists and no hold is set. PAUSED jobs never resume on their own.
     */
    fun onAppStarted() {
        scope.launch {
            guarded("start the queue") {
                graph.ready.await()
                val queued = graph.awaitCatalogue().jobs.queue().first().any { it.job.state == JobState.QUEUED }
                val idle = active == null && synchronized(workLock) { drainJob == null }
                val allowed = graph.models.consented() && settings.queueHold.first() == null
                if (queued && idle && allowed) parts.starter.start()
            }
        }
    }

    // Draft names. --------------------------------------------------------------------------------------------------

    /**
     * The UI's changes of a draft's names. Each one is a single `NamesStore.mutate` with the `:core` reducer, guarded
     * by the naming gate; the running job's tracker publishes at once afterwards.
     */
    inner class DraftActions internal constructor(val noteId: String) {
        /** "Yes, Luca": the label, the Remember choice with the voices generation, and the live match CONFIRMED. */
        suspend fun confirm(speakerId: String, voiceId: String, name: String, score: Float): DraftOutcome {
            val generation = graph.awaitCatalogue().voices.generation()
            return change { it.confirm(speakerId, voiceId, name, generation, score) }
        }

        /** "Not Luca": the voice is not suggested again for that speaker. */
        suspend fun reject(speakerId: String, voiceId: String, voiceName: String? = null): DraftOutcome =
            change { it.reject(speakerId, voiceId, voiceName) }

        suspend fun rename(speakerId: String, name: String): DraftOutcome = change { it.rename(speakerId, name) }

        suspend fun merge(source: String, target: String, targetLabel: String? = null): DraftOutcome =
            change { it.merge(source, target, targetLabel) }

        /** Remember into [voiceId] ("" for a new voice), or forget the choice with null. */
        suspend fun remember(speakerId: String, voiceId: String?): DraftOutcome {
            val generation = graph.awaitCatalogue().voices.generation()
            return change { it.remember(speakerId, voiceId, generation) }
        }

        private suspend fun change(reducer: (CheckpointNames) -> CheckpointNames): DraftOutcome {
            val gate = graph.namingGate
            if (!gate.isOpen(noteId)) return DraftOutcome.Closed
            val names = graph.awaitCatalogue().checkpoints.names.mutate(noteId, gate.guard(noteId, reducer))
            active?.takeIf { it.noteId == noteId }?.tracker?.refresh()
            return if (gate.isOpen(noteId)) DraftOutcome.Changed(names) else DraftOutcome.Closed
        }
    }

    // The drain loop. ---------------------------------------------------------------------------------------------

    private suspend fun drainLoop() {
        val self = currentCoroutineContext()[Job]
        while (true) {
            guarded("run the queue") { runQueue() }
            val again = synchronized(workLock) {
                if (rerun) {
                    rerun = false
                    true
                } else {
                    // From here a new drain() starts a new loop instead of joining this one.
                    if (drainJob === self) drainJob = null
                    false
                }
            }
            if (!again) break
        }
    }

    private fun endDrain(job: Job) {
        val idle = synchronized(workLock) {
            if (drainJob === job) drainJob = null
            drainJob == null
        }
        if (idle) work.update { it.copy(draining = false) }
    }

    /** Claims and runs QUEUED jobs one at a time until the queue is empty or held. */
    private suspend fun runQueue() {
        graph.ready.await()
        val catalogue = graph.awaitCatalogue()
        while (settings.queueHold.first() == null) {
            val next = queueLock.withLock { claim(catalogue) } ?: return
            runOne(catalogue, next)
        }
    }

    /** One claim (under the queue lock): the next QUEUED job becomes RUNNING and [active]. */
    private suspend fun claim(catalogue: Catalogue): Active? {
        var next: Active? = null
        var claim = catalogue.jobs.claimNext()
        while (next == null && claim is ClaimOutcome.Claimed) {
            next = start(catalogue, claim.job.noteId)
            if (next == null) claim = catalogue.jobs.claimNext()
        }
        if (claim is ClaimOutcome.Busy) Log.w(TAG, "A RUNNING row without a job here: ${claim.runningNoteId}")
        return next
    }

    /** Builds and starts the job of the claimed [noteId]; a job that cannot be built fails, and null is returned. */
    @OptIn(DelicateCoroutinesApi::class) // CoroutineStart.ATOMIC, see below
    private suspend fun start(catalogue: Catalogue, noteId: String): Active? {
        val title = catalogue.database.notes().note(noteId)?.title.orEmpty()
        val processing = try {
            parts.jobs.create(noteId)
        } catch (
            @Suppress("TooGenericExceptionCaught") // a job that cannot be built fails; the queue goes on
            error: Exception,
        ) {
            Log.e(TAG, "Cannot create the job of $noteId", error)
            catalogue.jobs.markFailed(noteId, Failure.UNKNOWN)
            null
        } ?: return null
        // ATOMIC: the job always enters run(), so its event channel is closed on every path, also after a cancel.
        val run = scope.async(start = CoroutineStart.ATOMIC) { processing.run() }
        return Active(noteId, title, processing, run).also { active = it }
    }

    /**
     * Follows the job of [current] to its end, then writes its outcome, clears [active] and completes
     * [Active.finished]. These last steps run on every path, so a failure around the job never blocks the queue.
     */
    private suspend fun runOne(catalogue: Catalogue, current: Active) {
        var outcome: JobOutcome? = null
        try {
            outcome = follow(catalogue, current)
        } catch (cancelled: CancellationException) {
            // The application scope ends: the row stays RUNNING, and the next start reconciles it.
            current.run.cancel(cancelled)
            throw cancelled
        } catch (
            @Suppress("TooGenericExceptionCaught") // a failed step around the job ends the job; the queue goes on
            error: Exception,
        ) {
            Log.e(TAG, "Cannot follow the job of ${current.noteId}", error)
            outcome = withContext(NonCancellable) { endAfterFailure(current) }
        } finally {
            val result = outcome
            withContext(NonCancellable) {
                queueLock.withLock {
                    if (result != null) {
                        guarded("write the outcome of ${current.noteId}") { record(catalogue, current, result) }
                    }
                    running.value = null
                    active = null
                }
                current.finished.complete(Unit)
                if (result != null) {
                    guarded("tell the outcome of ${current.noteId}") { announce(catalogue, current, result) }
                }
            }
        }
    }

    /** Stops the job after a failure around it; the outcome is the job's own, or FAILED(UNKNOWN) when cancelled. */
    private suspend fun endAfterFailure(current: Active): JobOutcome {
        current.run.cancel(CancellationException("The job could not be followed"))
        val outcome = outcomeOf(current)
        val unexpected = outcome == JobOutcome.Cancelled && current.cancel.get() == null
        return if (unexpected) JobOutcome.Failed(JobFailure.Processing(Failure.UNKNOWN)) else outcome
    }

    /** Runs the tracker and the names watcher of [current] until its job ends; returns the job's outcome. */
    private suspend fun follow(catalogue: Catalogue, current: Active): JobOutcome = coroutineScope {
        val noteId = current.noteId
        val names = AtomicReference(catalogue.checkpoints.names.current(noteId))
        val tracker = ProgressTracker(
            DraftSources({ names.get() }, { voiceNames.value }, parts.placeholderLabel),
            settings.finalizationMs.first() ?: 0L,
            parts.battery,
            SystemClock::elapsedRealtime,
        )
        current.tracker = tracker
        running.value = RunningJob(noteId, current.title, JobPhase.Starting)
        val watchNames = launch {
            catalogue.checkpoints.names.observe(noteId).collect {
                names.set(it)
                tracker.refresh()
            }
        }
        val phases = launch { tracker.phase.collect { running.value = RunningJob(noteId, current.title, it) } }
        val tracked = async { tracker.run(current.job.events) }
        val outcome = outcomeOf(current)
        // The job closed its channel when run() ended; the tracker ends right after.
        withTimeoutOrNull(TRACKER_END_MS) { tracked.await() } ?: tracked.cancel()
        phases.cancelAndJoin()
        watchNames.cancelAndJoin()
        outcome
    }

    private suspend fun outcomeOf(current: Active): JobOutcome = try {
        current.run.await()
    } catch (cancelled: CancellationException) {
        if (!currentCoroutineContext().isActive) throw cancelled
        JobOutcome.Cancelled
    } catch (
        // An Error such as UnsatisfiedLinkError: the job fails, the queue goes on.
        @Suppress("TooGenericExceptionCaught")
        error: Throwable,
    ) {
        Log.e(TAG, "The job of ${current.noteId} failed", error)
        JobOutcome.Failed(JobFailure.Processing(Failure.UNKNOWN))
    }

    /** Writes the row for [outcome] (under the queue lock). */
    private suspend fun record(catalogue: Catalogue, current: Active, outcome: JobOutcome) {
        // One read: a Cancel of a download can set the flag outside the lock while this runs.
        val cancel = current.cancel.get()
        if (recordCancel(catalogue, current.noteId, cancel, outcome)) return
        val noteId = current.noteId
        val jobs = catalogue.jobs
        when (outcome) {
            // Adopt removed the row; this covers a race with a row written meanwhile.
            JobOutcome.Done -> jobs.remove(noteId)
            JobOutcome.AdoptPending -> {
                // As reconcileOnStart does. When it still cannot finish, the marker stays and the row goes: the next
                // enqueue or start finishes the adopt first. The note is never FAILED for it.
                catalogue.adopter.adopt(noteId)
                jobs.remove(noteId)
            }
            is JobOutcome.Paused -> markPaused(catalogue, noteId, outcome.reason)
            is JobOutcome.Failed -> jobs.markFailed(noteId, outcome.failure.code)
            is JobOutcome.NeedsConsent -> jobs.markNeedsConsent(noteId)
            JobOutcome.Cancelled -> when (cancel) {
                Cancel.DISCARD, Cancel.RESTORE -> Unit // handled above
                Cancel.KEEP -> markPaused(catalogue, noteId, PauseReason.USER)
                null -> markPaused(catalogue, noteId, PauseReason.INTERRUPTED)
            }
        }
    }

    /**
     * Writes the row for a Start over (DISCARD) or a Cancel of a download (RESTORE); false when [outcome] is not for
     * them. Start over can come after the job's run ended but before this write: it still discards the work.
     */
    private suspend fun recordCancel(
        catalogue: Catalogue,
        noteId: String,
        cancel: Cancel?,
        outcome: JobOutcome,
    ): Boolean {
        val sealed = outcome == JobOutcome.Done || outcome == JobOutcome.AdoptPending
        val stopped = outcome is JobOutcome.Paused || outcome == JobOutcome.Cancelled
        return when {
            cancel == Cancel.DISCARD && !sealed -> {
                discardWork(catalogue, noteId)
                true
            }
            cancel == Cancel.RESTORE && stopped -> {
                if (!markPausedIfSaved(catalogue, noteId, PauseReason.USER)) catalogue.jobs.remove(noteId)
                true
            }
            else -> false
        }
    }

    /** The results notification for [outcome], only while the app is not visible (features.md section 12). */
    private suspend fun announce(catalogue: Catalogue, current: Active, outcome: JobOutcome) {
        val noteId = current.noteId
        when (outcome) {
            JobOutcome.Done -> {
                val note = catalogue.database.notes().note(noteId) ?: return
                val variants = catalogue.database.cache().variants(noteId)
                val shown = variants.firstOrNull { it.variantId == note.selectedVariant }
                    ?: variants.firstOrNull { it.isDefault }
                notifyHidden { transcribed(noteId, note.title, shown?.speakerCount ?: 0, note.durationMs) }
            }
            is JobOutcome.Failed -> notifyHidden { failed(noteId, current.title, outcome.failure.code) }
            is JobOutcome.NeedsConsent -> notifyHidden { readyToTranscribe(noteId, current.title) }
            else -> Unit
        }
    }

    // Helpers. ----------------------------------------------------------------------------------------------------

    private suspend fun needConsent(catalogue: Catalogue, noteId: String, kind: JobKind): TranscribeOutcome =
        when (val enqueued = catalogue.jobs.enqueue(noteId, kind)) {
            is EnqueueOutcome.Refused -> TranscribeOutcome.Refused(enqueued.reason)
            is EnqueueOutcome.Unchanged -> if (enqueued.job.state == JobState.RUNNING) {
                TranscribeOutcome.Queued(changed = false)
            } else {
                catalogue.jobs.markNeedsConsent(noteId)
                TranscribeOutcome.NeedsConsent
            }
            is EnqueueOutcome.Queued -> {
                catalogue.jobs.markNeedsConsent(noteId)
                TranscribeOutcome.NeedsConsent
            }
        }

    private suspend fun clearHold(origin: StartOrigin, resume: Boolean) {
        val clears = when (settings.queueHold.first()) {
            QueueHold.USER -> origin != StartOrigin.AUTOMATIC
            QueueHold.SYSTEM -> origin == StartOrigin.SCREEN && resume
            null -> false
        }
        if (clears) settings.setQueueHold(null)
    }

    /** Asks for the service when the queue is not held. */
    private suspend fun startService() {
        if (settings.queueHold.first() == null) parts.starter.start()
    }

    /** PAUSED([reason]) with the numbers of the saved checkpoint (0 without one). */
    private suspend fun markPaused(catalogue: Catalogue, noteId: String, reason: PauseReason) {
        if (markPausedIfSaved(catalogue, noteId, reason)) return
        val totalMs = catalogue.jobs.job(noteId)?.totalMs ?: 0L
        catalogue.jobs.markPaused(noteId, reason, doneMs = 0, totalMs = totalMs, words = 0)
    }

    private suspend fun markPausedIfSaved(catalogue: Catalogue, noteId: String, reason: PauseReason): Boolean {
        val saved = catalogue.checkpoints.status(noteId) as? CheckpointStatus.Saved ?: return false
        catalogue.jobs.markPaused(noteId, reason, saved.doneMs, saved.durationMs, saved.words)
        return true
    }

    /** Rule 8 after `join()`: the work directory, then `voices/pending/<id>.json`, then the row. */
    private suspend fun discardWork(catalogue: Catalogue, noteId: String) {
        catalogue.checkpoints.discard(noteId)
        catalogue.voices.discardPending(noteId)
        catalogue.jobs.remove(noteId)
    }

    private fun build(
        rows: List<QueueRow>?,
        running: RunningJob?,
        hold: QueueHold?,
        install: InstallState,
        work: Work,
    ): ProcessingState {
        val all = rows.orEmpty()
        val queued = all.filter { it.job.state == JobState.QUEUED }
        val ahead = queued.withIndex().associate { (index, row) -> row.job.noteId to index }
        return ProcessingState(
            loading = rows == null,
            running = running,
            queue = queued.map { QueuedNote(it.job.noteId, it.title) },
            hold = hold,
            notes = all.associate { row ->
                val id = row.job.noteId
                id to NoteJobState(row.job, ahead[id] ?: 0, running?.takeIf { it.noteId == id }?.phase)
            },
            install = install,
            downloading = work.downloading,
            draining = work.draining,
            serviceForeground = work.foreground,
        )
    }

    private suspend fun watchVoices() = guarded("read the saved voices") {
        graph.ready.await()
        graph.awaitCatalogue().voices.people().collect { saved ->
            voiceNames.value = saved.voices.associate { it.id to it.name }
        }
    }

    /** "Transcription stopped" for the jobs that the startup paused after their automatic attempts. */
    private suspend fun announceInterrupted() = guarded("tell the interrupted jobs") {
        val report = graph.startupReport.filterNotNull().first()
        val interrupted = report.reconcile?.interrupted.orEmpty()
        if (interrupted.isEmpty()) return@guarded
        val notes = graph.awaitCatalogue().database.notes()
        interrupted.forEach { id -> notes.note(id)?.let { note -> notifyHidden { interrupted(id, note.title) } } }
    }

    private fun notifyHidden(post: ProcessingNotices.() -> Unit) {
        if (!parts.visibility.visible.value) parts.notices.post()
    }

    /** Runs [block]; a failure is logged, never thrown into the application scope (it would end the process). */
    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (
            @Suppress("TooGenericExceptionCaught") // the queue survives a failed step; the next drain tries again
            error: Exception,
        ) {
            Log.e(TAG, "Cannot $what", error)
        }
    }

    private companion object {
        const val TAG = "ProcessingCoordinator"

        /** How long the tracker may take to finish after the job closed its events. */
        const val TRACKER_END_MS = 5_000L
    }
}
