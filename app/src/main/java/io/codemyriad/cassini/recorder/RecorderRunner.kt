package io.codemyriad.cassini.recorder

import android.util.Log
import io.codemyriad.cassini.core.recorder.XrecFailure
import io.codemyriad.cassini.data.recorder.RecorderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Starts the recorder sync's foreground service; false when Android refused it. */
fun interface RecorderServiceStarter {
    fun start(): Boolean
}

/**
 * Runs [sync] in the application scope, with the foreground service of type `connectedDevice` around the active part.
 *
 * - "Sync now" ([syncNow]) comes from the visible Recorder screen, where Android lets the app start the service: the
 *   service starts first, then the sync. It starts only when a sync can connect ([RecorderSync.canSync]): a service
 *   that cannot become a foreground service (no Bluetooth permission on Android 14) must not start.
 * - An automatic attempt ([attempt]) starts the service only when the sync has files to move, so the attempts that
 *   find no recorder or nothing new show no notification. Android 12 and later refuse that start in the background
 *   unless the recorder is a companion device of the app; then the sync goes on without the service, while the
 *   process lives.
 */
class RecorderRunner(
    private val scope: CoroutineScope,
    private val sync: RecorderSync,
    private val onRebindNeeded: () -> Unit = {},
    private val service: RecorderServiceStarter,
) {
    private val serviceRuns = MutableStateFlow(0)
    private val serviceRunning = MutableStateFlow(false)

    /** True while at least one run has started the foreground service and has not finished yet. */
    val serviceActive: StateFlow<Boolean> = serviceRunning

    fun syncNow(): Deferred<SyncOutcome> = scope.async {
        var started = false
        if (sync.canSync()) {
            started = acquireService()
        }
        try {
            sync.sync()
        } finally {
            if (started) releaseService()
        }
    }

    fun attempt(mode: SyncMode = SyncMode.FULL): Deferred<SyncOutcome> = scope.async {
        var started = false
        try {
            sync.sync(mode) {
                if (!started) {
                    started = acquireService()
                    if (!started) Log.i(TAG, "No foreground service for this sync; it goes on while the process lives")
                }
            }.also { outcome ->
                if (outcome == SyncOutcome.RebindNeeded) onRebindNeeded()
            }
        } finally {
            if (started) releaseService()
        }
    }

    /**
     * Runs a full sync triggered by companion presence or `ACTION_ACL_CONNECTED`. Starts the `connectedDevice`
     * foreground service up front while the companion wakeup exemption is active, retries once if RFCOMM raced the
     * recorder's SDP poke teardown, and keeps the foreground service alive while newly imported notes transcribe and
     * push their `.cwx` sidecars back.
     */
    fun wakeAttempt(
        retryDelayMs: Long = POKE_RETRY_MS,
        roundTripHoldMs: Long = ROUND_TRIP_HOLD_MS,
    ): Deferred<SyncOutcome> = scope.async {
        if (!sync.canAcceptFullSync()) return@async SyncOutcome.Busy
        var started = false
        if (sync.canSync()) {
            started = acquireService()
            if (!started) Log.i(TAG, "Android refused foreground service on wakeAttempt; continuing in process")
        }
        try {
            var outcome = sync.sync(SyncMode.FULL)
            if (retryDelayMs > 0 && isTransientConnectRace(outcome)) {
                Log.i(TAG, "First wake sync ended with $outcome; retrying after $retryDelayMs ms")
                delay(retryDelayMs)
                outcome = sync.sync(SyncMode.FULL)
            }
            Log.i(TAG, "Wake sync finished: $outcome")
            if (outcome == SyncOutcome.RebindNeeded) onRebindNeeded()
            if (started && roundTripHoldMs > 0 && outcome is SyncOutcome.Done) {
                holdForPendingRoundTrip(roundTripHoldMs)
            }
            outcome
        } finally {
            if (started) releaseService()
        }
    }

    private fun isTransientConnectRace(outcome: SyncOutcome): Boolean =
        outcome == SyncOutcome.Unreachable ||
            (outcome is SyncOutcome.Failed && outcome.failure == XrecFailure.DISCONNECTED)

    private suspend fun holdForPendingRoundTrip(limitMs: Long) {
        var waited = 0L
        while (waited < limitMs) {
            if (sync.activeTranscriptions() == 0 && sync.pendingUploads().isEmpty()) return
            delay(ROUND_TRIP_POLL_MS)
            waited += ROUND_TRIP_POLL_MS
        }
    }

    private fun acquireService(): Boolean {
        updateServiceRuns(1)
        val ok = service.start()
        if (!ok) updateServiceRuns(-1)
        return ok
    }

    private fun releaseService() = updateServiceRuns(-1)

    private fun updateServiceRuns(delta: Int) {
        serviceRuns.update { count ->
            val next = (count + delta).coerceAtLeast(0)
            serviceRunning.value = next > 0
            next
        }
    }

    /** Returns when no sync and no binding runs. */
    suspend fun awaitIdle() = sync.awaitIdle()

    private companion object {
        const val TAG = "RecorderRunner"
        const val POKE_RETRY_MS = 1_500L
        const val ROUND_TRIP_HOLD_MS = 5 * 60_000L
        const val ROUND_TRIP_POLL_MS = 500L
    }
}

/**
 * "Auto sync" (docs/recorder-sync.md): while it is on and a recorder is bound (and not waiting to be bound again), an
 * attempt runs at once and then after each wait of [AutoSyncBackoff]. A failed connection is normal (the recorder's
 * radio is on only in its Bluetooth phone mode), so the waits grow quietly; a sync with nothing to move makes them grow
 * too. While the recorder records and "Sync while the recorder records" is on, a sync that ends with files waiting for
 * the recording ([SyncOutcome.Done.recordingPoll]) is followed by another after the fixed wait of [RecordingPoll], for
 * about 2 h at most. A new finished transcript goes at once through [RecorderTranscriptPush], with or without "Auto
 * sync". Nothing runs after the process ends: the app does not use WorkManager
 * (docs/architecture.md section 11). The loop survives any failure of an attempt: it runs in the application scope,
 * where an exception would end the process.
 */
class RecorderAutoSync(
    private val scope: CoroutineScope,
    private val settings: Flow<RecorderSettings>,
    private val runner: RecorderRunner,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    fun start() {
        val enabled = settings.map { it.autoSync && it.binding != null && it.current?.rebindNeeded != true }
            .distinctUntilChanged()
        scope.launch {
            enabled.collectLatest { on -> if (on) loop() }
        }
    }

    @Suppress("TooGenericExceptionCaught") // see the class comment
    private suspend fun loop() {
        var failures = 0
        var idle = 0
        var polls = 0
        while (true) {
            val outcome = try {
                runner.attempt().await()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "An automatic sync failed", error)
                SyncOutcome.Failed(null, error.javaClass.simpleName)
            }
            when (outcome) {
                is SyncOutcome.Done -> {
                    failures = 0
                    idle = if (outcome.idle) idle + 1 else 0
                    polls = if (outcome.recordingPoll) polls + 1 else 0
                }
                SyncOutcome.Busy -> Unit
                else -> failures++
            }
            wait(RecordingPoll.delayMs(failures, idle, polls))
        }
    }

    private companion object {
        const val TAG = "RecorderAutoSync"
    }
}

/**
 * Sends a new finished transcript to the recorder at once (docs/recorder-sync.md), whatever "Auto sync" is, while a
 * recorder is bound (and not waiting to be bound again). When a note of an imported recording gets its transcript
 * ([transcribed] gains a note), and once at the start when a transcript waits ([pending]), an upload-only attempt
 * ([SyncMode.UPLOADS]) runs. While the recorder does not answer (its radio is on only in its Bluetooth phone mode), the
 * attempt runs again after the waits of [TranscriptPushBackoff], while a transcript waits and the process lives, up to
 * [TranscriptPushBackoff.MAX_FAILURES] failures in a row; [TranscriptPushBackoff.MAX_SYNC_FAILURES] failed syncs in a
 * row end them sooner. A transcript that the recorder takes only after its recording
 * ([SyncOutcome.Done.deferredUploads]) is no failure either. With "Sync while the recorder records" on, the push tries
 * again after [RecordingPoll.INTERVAL_MS], at most [RecordingPoll.MAX_POLLS] times in a row, unless "Auto sync" is on
 * (its full syncs send it, without a second loop). With that setting off (the default), no sync calls the recorder
 * while it records: the push ends, and the transcript goes with the next sync or the next transcript after the
 * recording. A running sync or binding ([SyncOutcome.Busy]) is no failure: the push waits for its end and tries again at
 * once (a full sync sends the transcripts that finished during its downloads, so usually nothing is
 * left). A sync that connected ends the retries: what it did not send cannot go now (the recording went, or the
 * recorder refused it), and the next sync tries again. A sync that cannot connect before the user acts (no permission,
 * no Bluetooth, a refused key) ends them too. [RecorderSync.pendingUploads] leaves out what no sync can send, so a new
 * process does not start the attempts for it.
 */
class RecorderTranscriptPush(
    private val scope: CoroutineScope,
    private val settings: Flow<RecorderSettings>,
    private val runner: RecorderRunner,
    /** The notes with a finished transcript. */
    private val transcribed: Flow<Set<String>>,
    /** The notes whose transcript the recorder does not have yet ([RecorderSync.pendingUploads]). */
    private val pending: suspend () -> Set<String>,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    fun start() {
        val bound = settings.map { it.binding != null && it.current?.rebindNeeded != true }.distinctUntilChanged()
        scope.launch {
            bound.collectLatest { on -> if (on) follow() }
        }
    }

    /**
     * Pushes at the start when a transcript waits, and again, at once, each time a note of a recording gets its
     * transcript. A note that leaves the set (deleted, or no longer transcribed) does not stop the attempts for the
     * others.
     */
    private suspend fun follow() = coroutineScope {
        var known: Set<String>? = null
        var pushing: Job? = null
        val mapped = settings.map { settings ->
            settings.current?.recordings.orEmpty().mapNotNull { it.recording.noteId }.toSet()
        }
        combine(mapped, transcribed) { notes, done -> notes intersect done }.distinctUntilChanged()
            .collect { ready ->
                val previous = known
                known = ready
                if (previous == null || (ready - previous).isNotEmpty()) {
                    pushing?.cancelAndJoin()
                    pushing = launch { push() }
                }
            }
    }

    /** Upload-only attempts while a transcript waits and the last one ended in a way that can pass by itself. */
    // The push ends after a failure that cannot pass, when "Auto sync" takes over, or when the recording holds it.
    @Suppress("ReturnCount")
    private suspend fun push() {
        var failures = 0
        var syncFailures = 0
        var deferrals = 0
        var outcome = attempt()
        while (outcome != null) {
            if (outcome == SyncOutcome.Busy) {
                // Another sync or a binding has the recorder: no failure, and no wait after it ends.
                runner.awaitIdle()
            } else if (outcome is SyncOutcome.Done && outcome.deferredUploads > 0) {
                // The recorder records and takes the transcript after: no failure. The 2-minute poll during a recording
                // belongs to "Sync while the recorder records"; "Auto sync" sends it if it is on.
                val now = settings.first()
                if (!now.syncWhileRecording || now.autoSync || ++deferrals > RecordingPoll.MAX_POLLS) {
                    Log.i(TAG, "The transcript waits for a sync after the recording")
                    return
                }
                failures = 0
                syncFailures = 0
                Log.i(TAG, "The transcript waits for the end of the recording; try $deferrals")
                wait(RecordingPoll.INTERVAL_MS)
            } else {
                failures++
                syncFailures = if (outcome is SyncOutcome.Failed) syncFailures + 1 else 0
                if (!tryAgain(outcome, failures, syncFailures)) return
                Log.i(TAG, "The transcript waits for the recorder ($outcome); try $failures")
                wait(TranscriptPushBackoff.delayMs(failures))
            }
            outcome = attempt()
        }
    }

    private fun tryAgain(outcome: SyncOutcome, failures: Int, syncFailures: Int): Boolean {
        val giveUp = failures >= TranscriptPushBackoff.MAX_FAILURES ||
            syncFailures >= TranscriptPushBackoff.MAX_SYNC_FAILURES
        if (giveUp) Log.i(TAG, "The transcript waits for the next sync ($outcome, $failures failures)")
        return retry(outcome) && !giveUp
    }

    /** One upload-only attempt, or null when no transcript waits or the attempt failed in the phone. */
    @Suppress("TooGenericExceptionCaught") // runs in the application scope, where an exception ends the process
    private suspend fun attempt(): SyncOutcome? = try {
        if (pending().isEmpty()) null else runner.attempt(SyncMode.UPLOADS).await()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.e(TAG, "A transcript upload failed", error)
        null
    }

    private fun retry(outcome: SyncOutcome): Boolean = when (outcome) {
        SyncOutcome.Unreachable, SyncOutcome.Busy, is SyncOutcome.Failed -> true
        is SyncOutcome.Unavailable -> outcome.readiness == RadioReadiness.OFF
        is SyncOutcome.Done, SyncOutcome.NotBound, SyncOutcome.RebindNeeded -> false
    }

    private companion object {
        const val TAG = "RecorderTranscriptPush"
    }
}
