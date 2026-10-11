package org.cassini.android.processing

import android.app.InvalidForegroundServiceTypeException
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cassini.android.CassiniApplication
import org.cassini.android.startForegroundWithType
import org.cassini.engine.job.JobPhase

/**
 * The foreground lifetime of transcription (architecture 5.7): type, wake lock, notification and `onTimeout`. The
 * queue itself is [ProcessingCoordinator]'s; this service only keeps the process in the foreground while the
 * coordinator works, and stops after its drain loop has joined.
 *
 * - Intents: [ACTION_DRAIN] (no note id; the queue is in Room), [ACTION_STOP_ALL], [ACTION_RESUME] (the
 *   "Transcription stopped" notification) and [ACTION_CANCEL_DOWNLOAD]. An intent with START_FLAG_REDELIVERY (or a
 *   null one) is only a drain: its command ran before the process died, and running it again would undo later state.
 * - Every `startForeground` call is wrapped in a catch of the refusals of Android 12-16. When the first one fails the
 *   rows stay QUEUED, "Ready to transcribe" is posted and the service stops (START_NOT_STICKY); a refused type change
 *   keeps the previous type.
 * - The service never bumps `attempts`: only `reconcileOnStart` does.
 */
@Suppress("TooManyFunctions") // the service callbacks plus one follower per thing it keeps in step (5.7)
class ProcessingService : LifecycleService() {
    private val coordinator: ProcessingCoordinator get() = (application as CassiniApplication).graph.processing
    private val notifications by lazy { ProcessingNotifications(this) }

    /** The current service type; null while the service is not in the foreground. */
    private var foregroundType: Int? = null
    private var lastStartId = 0
    private var watcher: Job? = null

    /** The command coroutines of this start; the service stops only after they ended (main thread only). */
    private val commands = mutableSetOf<Job>()
    private var wakeLock: PowerManager.WakeLock? = null

    /** The last ongoing notification that [followNotification] built; null before the first one (main thread). */
    private var lastNotification: Notification? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        val first = foregroundType == null
        val type = typeOf(coordinator.foregroundNeed.value)
        if (!enterForeground(type)) {
            if (first) {
                coordinator.onServiceRefused()
                stopSelf(startId)
                return START_NOT_STICKY
            }
            Log.w(TAG, "Android refused the service type $type; the service keeps $foregroundType")
        } else {
            foregroundType = type
        }
        if (first) coordinator.onServiceForeground(true)
        // After process death Android sends every pending intent again. The queue and the holds are in Room and the
        // settings, so a sent-again command only drains: it must not queue, stop or hold anything a second time.
        val redelivered = intent == null || (flags and START_FLAG_REDELIVERY) != 0
        when (if (redelivered) ACTION_DRAIN else intent.action) {
            // No drain here: the hold must be written before the loop looks at the queue again.
            ACTION_STOP_ALL -> command { coordinator.stopAll() }
            ACTION_CANCEL_DOWNLOAD -> coordinator.cancelDownload()
            ACTION_RESUME -> {
                val noteId = intent?.getStringExtra(EXTRA_NOTE_ID)
                command {
                    if (noteId != null) {
                        coordinator.transcribe(noteId, origin = StartOrigin.NOTIFICATION, startService = false)
                    }
                    coordinator.drain()
                }
            }
            // ACTION_DRAIN, and any intent sent again after process death.
            else -> coordinator.drain()
        }
        if (watcher?.isActive != true) watcher = lifecycleScope.launch { watch() }
        return START_REDELIVER_INTENT
    }

    /**
     * API 35+: the 6 h budget of the service type is used up. In this order: "Transcription paused by Android", leave
     * the foreground and stop without waiting for the job, then the coordinator stops the job, holds the queue
     * (SYSTEM) and writes the row PAUSED(SYSTEM). It is never shown as an error.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val state = coordinator.state.value
        val running = state.running
        if (running != null) {
            // Outside inference (Starting, a download, Stopping) the row has the numbers of the saved checkpoint.
            val progress = (running.phase as? JobPhase.Transcribing)?.progress
            val row = state.notes[running.noteId]?.job
            val doneMs = progress?.doneMs ?: row?.doneMs ?: 0
            val totalMs = progress?.totalMs ?: row?.totalMs ?: 0
            notifications.pausedByAndroid(running.noteId, running.title, doneMs, totalMs)
        }
        leaveForeground()
        stopSelf()
        coordinator.systemStopAsync(running?.noteId)
    }

    override fun onDestroy() {
        if (foregroundType != null) leaveForeground()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun command(block: suspend () -> Unit) {
        val job = lifecycleScope.launch { block() }
        commands += job
        job.invokeOnCompletion { commands -= job }
    }

    /** Follows the coordinator until it is idle, then leaves the foreground and stops. */
    private suspend fun watch() {
        val followers = listOf(
            lifecycleScope.launch { followNotification() },
            lifecycleScope.launch { followType() },
            lifecycleScope.launch { followWakeLock() },
        )
        while (true) {
            commands.toList().joinAll()
            coordinator.awaitIdle()
            if (commands.isEmpty()) break
        }
        followers.forEach(Job::cancel)
        leaveForeground()
        stopSelf(lastStartId)
    }

    /**
     * The ongoing notification, at most once a second (architecture 5.10). The text is built off the main thread:
     * each build reads the app locale (a file below API 33, `LocaleManager` from API 33).
     */
    private suspend fun followNotification() {
        var posted = 0L
        coordinator.state.map(notifications::content).distinctUntilChanged().flowOn(Dispatchers.Default).conflate()
            .collect {
                val wait = posted + MIN_UPDATE_MS - SystemClock.elapsedRealtime()
                if (wait > 0) delay(wait)
                posted = SystemClock.elapsedRealtime()
                val notification = withContext(Dispatchers.Default) { notifications.ongoing(coordinator.state.value) }
                lastNotification = notification
                if (foregroundType != null) {
                    getSystemService(NotificationManager::class.java)
                        ?.notify(ProcessingNotifications.PROCESSING_ID, notification)
                }
            }
    }

    /** A new service type when the work changes: dataSync while downloading, mediaProcessing from inference on. */
    private suspend fun followType() {
        coordinator.foregroundNeed.collect { need ->
            val type = typeOf(need)
            if (need != ForegroundNeed.NONE && foregroundType != null && type != foregroundType) {
                if (enterForeground(type)) {
                    foregroundType = type
                } else {
                    Log.w(TAG, "Android refused the service type $type; the service keeps $foregroundType")
                }
            }
        }
    }

    /**
     * PARTIAL_WAKE_LOCK while inference runs: acquired with a 10 min timeout and again at every window, and while a
     * cancelled job still decodes (WaitingForPrevious). Stopping keeps the lock as it is: after a Stop the job decodes
     * up to its next block, but a stopped download holds none. Released on any other phase and when the service stops.
     */
    private suspend fun followWakeLock() {
        coordinator.state
            .map { state ->
                when (val phase = state.running?.phase) {
                    is JobPhase.Transcribing -> phase.progress.doneMs
                    JobPhase.WaitingForPrevious -> WAKE_HOLD
                    JobPhase.Stopping -> WAKE_KEEP
                    else -> null
                }
            }
            .distinctUntilChanged()
            .collect { tick ->
                when (tick) {
                    null -> releaseWakeLock()
                    WAKE_KEEP -> Unit
                    else -> acquireWakeLock()
                }
            }
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        lock?.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    /**
     * `startForeground` with [type] inside the catch of architecture 5.7; false when Android refused it. It takes the
     * last notification that [followNotification] built, which updates it within a second. Only the first call of a
     * start builds one here, on the main thread, because `startForeground` must come at once; this build reads the
     * app locale (a file below API 33, `LocaleManager` from API 33).
     */
    private fun enterForeground(type: Int): Boolean {
        val notification: Notification = lastNotification ?: notifications.ongoing(coordinator.state.value)
        return try {
            startForegroundWithType(ProcessingNotifications.PROCESSING_ID, notification, type)
            true
        } catch (
            @Suppress("TooGenericExceptionCaught") // the refusals have no common type below RuntimeException
            error: RuntimeException,
        ) {
            if (!isForegroundRefusal(error)) throw error
            Log.w(TAG, "Android refused startForeground", error)
            false
        }
    }

    private fun leaveForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundType = null
        lastNotification = null
        coordinator.onServiceForeground(false)
        releaseWakeLock()
    }

    companion object {
        const val ACTION_DRAIN = "org.cassini.android.processing.DRAIN"
        const val ACTION_STOP_ALL = "org.cassini.android.processing.STOP_ALL"
        const val ACTION_RESUME = "org.cassini.android.processing.RESUME"
        const val ACTION_CANCEL_DOWNLOAD = "org.cassini.android.processing.CANCEL_DOWNLOAD"
        const val EXTRA_NOTE_ID = "noteId"

        private const val TAG = "ProcessingService"
        private const val WAKE_LOCK_TAG = "Cassini:processing"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val MIN_UPDATE_MS = 1_000L

        /** [followWakeLock] ticks that are no window position: acquire the lock, or keep it as it is. */
        private const val WAKE_HOLD = -1L
        private const val WAKE_KEEP = -2L

        fun drainIntent(context: Context): Intent = processingIntent(context, ACTION_DRAIN)

        fun resumeIntent(context: Context, noteId: String): Intent =
            processingIntent(context, ACTION_RESUME).putExtra(EXTRA_NOTE_ID, noteId)

        /** The service type for [need]: untyped below API 29, where services have no type. */
        fun typeOf(need: ForegroundNeed): Int = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> 0
            need == ForegroundNeed.MEDIA_PROCESSING && Build.VERSION.SDK_INT >= ProcessingState.MEDIA_PROCESSING_API ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

        /**
         * ForegroundServiceStartNotAllowedException (an IllegalStateException), SecurityException and, from API 34,
         * InvalidForegroundServiceTypeException.
         */
        fun isForegroundRefusal(error: RuntimeException): Boolean {
            val invalidType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                error is InvalidForegroundServiceTypeException
            return error is IllegalStateException || error is SecurityException || invalidType
        }
    }
}
