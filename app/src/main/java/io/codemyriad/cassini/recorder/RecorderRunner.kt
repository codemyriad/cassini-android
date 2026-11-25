package io.codemyriad.cassini.recorder

import android.util.Log
import io.codemyriad.cassini.data.recorder.RecorderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 *   service starts first, then the sync.
 * - An automatic attempt ([attempt]) starts the service only when the link is up, so the attempts that find no
 *   recorder show no notification. Android 12 and later refuse that start in the background unless the recorder is a
 *   companion device of the app; then the sync goes on without the service, while the process lives.
 */
class RecorderRunner(
    private val scope: CoroutineScope,
    private val sync: RecorderSync,
    private val service: RecorderServiceStarter,
) {
    fun syncNow(): Deferred<SyncOutcome> {
        service.start()
        return scope.async { sync.sync() }
    }

    fun attempt(): Deferred<SyncOutcome> = scope.async {
        val holder = launch {
            sync.activity.first { it is RecorderActivity.Syncing }
            if (!service.start()) Log.i(TAG, "No foreground service for this sync; it goes on while the process lives")
        }
        try {
            sync.sync()
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
 * radio is on only in its Bluetooth phone mode), so the waits grow quietly. A new finished transcript of an imported
 * recording ([transcribed] gains a note) starts an attempt at once, so the recorder gets it soon. Nothing runs after
 * the process ends: the app does not use WorkManager (docs/architecture.md section 11).
 */
class RecorderAutoSync(
    private val scope: CoroutineScope,
    private val settings: Flow<RecorderSettings>,
    private val runner: RecorderRunner,
    /** The notes with a finished transcript. */
    private val transcribed: Flow<Set<String>>,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    fun start() {
        val enabled = settings.map { it.autoSync && it.binding != null && it.current?.rebindNeeded != true }
            .distinctUntilChanged()
        scope.launch {
            enabled.collectLatest { on ->
                if (on) {
                    coroutineScope {
                        launch { loop() }
                        followTranscripts()
                    }
                }
            }
        }
    }

    private suspend fun loop() {
        var failures = 0
        while (true) {
            failures = when (runner.attempt().await()) {
                is SyncOutcome.Done -> 0
                SyncOutcome.Busy -> failures
                else -> failures + 1
            }
            wait(AutoSyncBackoff.delayMs(failures))
        }
    }

    /** Starts an attempt when a note of an imported recording gets its finished transcript. */
    private suspend fun followTranscripts() {
        var known: Set<String>? = null
        val mapped = settings.map { settings ->
            settings.current?.recordings.orEmpty().mapNotNull { it.recording.noteId }.toSet()
        }
        combine(mapped, transcribed) { notes, done -> notes intersect done }.distinctUntilChanged().collect { ready ->
            val previous = known
            known = ready
            if (previous != null && (ready - previous).isNotEmpty()) runner.attempt()
        }
    }
}
