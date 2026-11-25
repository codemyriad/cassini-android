package io.codemyriad.cassini.recorder

import android.util.Log
import io.codemyriad.cassini.data.recorder.RecorderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val service: RecorderServiceStarter,
) {
    fun syncNow(): Deferred<SyncOutcome> = scope.async {
        if (sync.canSync()) service.start()
        sync.sync()
    }

    fun attempt(mode: SyncMode = SyncMode.FULL): Deferred<SyncOutcome> = scope.async {
        val holder = launch {
            sync.activity.first { it is RecorderActivity.Syncing && it.step != SyncStep.CHECKING }
            if (!service.start()) Log.i(TAG, "No foreground service for this sync; it goes on while the process lives")
        }
        try {
            sync.sync(mode)
        } finally {
            holder.cancel()
        }
    }

    private companion object {
        const val TAG = "RecorderRunner"
    }
}

/**
 * "Auto sync" (docs/recorder-sync.md): while it is on and a recorder is bound (and not waiting to be bound again), an
 * attempt runs at once and then after each wait of [AutoSyncBackoff]. A failed connection is normal (the recorder's
 * radio is on only in its Bluetooth phone mode), so the waits grow quietly; a sync with nothing to move makes them grow
 * too. A new finished transcript goes at once through [RecorderTranscriptPush], with or without "Auto sync". Nothing
 * runs after the process ends: the app does not use WorkManager
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
                }
                SyncOutcome.Busy -> Unit
                else -> failures++
            }
            wait(AutoSyncBackoff.delayMs(failures, idle))
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
 * attempt runs again after the waits of [TranscriptPushBackoff], as long as a transcript waits and the process lives.
 * A sync that connected ends the retries: what it did not send cannot go now (the recording went, or the recorder
 * refused it), and the next sync tries again. A sync that cannot connect before the user acts (no permission, no
 * Bluetooth, a refused key) ends them too.
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

    /** Pushes at the start when a transcript waits, and again each time a note of a recording gets its transcript. */
    private suspend fun follow() {
        var known: Set<String>? = null
        val mapped = settings.map { settings ->
            settings.current?.recordings.orEmpty().mapNotNull { it.recording.noteId }.toSet()
        }
        combine(mapped, transcribed) { notes, done -> notes intersect done }.distinctUntilChanged()
            .collectLatest { ready ->
                val previous = known
                known = ready
                if (previous == null || (ready - previous).isNotEmpty()) push()
            }
    }

    /** Upload-only attempts while a transcript waits and the last one ended in a way that can pass by itself. */
    private suspend fun push() {
        var failures = 0
        while (true) {
            val outcome = attempt() ?: return
            if (!retry(outcome)) return
            failures++
            Log.i(TAG, "The transcript waits for the recorder ($outcome); try $failures")
            wait(TranscriptPushBackoff.delayMs(failures))
        }
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
