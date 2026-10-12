package org.cassini.android.recording

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.cassini.android.AppGraph
import org.cassini.android.CassiniApplication
import org.cassini.android.processing.ProcessingService
import org.cassini.android.startForegroundWithType

/**
 * The microphone foreground lifetime of a recording (architecture 5.8): the notification and its actions. The
 * recording itself is [RecordingController]'s; this service keeps the process in the foreground while it records,
 * and leaves only after the controller has ended, which includes the hand-off to processing.
 *
 * - Intents: [ACTION_START] (from [RecordingController.start], through `startForegroundService`), [ACTION_PAUSE],
 *   [ACTION_RESUME] and [ACTION_FINISH] (the notification actions).
 * - `startForeground(MICROPHONE)` comes first, inside the same catch as `ProcessingService`. When Android refuses
 *   it, the start fails (CouldNotStart) and the service stops.
 * - START_NOT_STICKY: after a crash, `RecoveryRepository` repairs the capture at the next start.
 */
class RecordingService : LifecycleService() {
    private val graph: AppGraph get() = (application as CassiniApplication).graph
    private val controller: RecordingController get() = graph.recording
    private val notifications by lazy { RecordingNotifications(this) }

    private var foreground = false
    private var lastStartId = 0
    private var watcher: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        val action = intent?.action
        if (!foreground && !enterForeground(action)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        controller.onServiceForeground()
        when (action) {
            ACTION_PAUSE -> controller.pause()
            ACTION_RESUME -> controller.resume()
            // The notification's Finish keeps the title in the state; the hand-off may start processing now,
            // within the temporary allow-list of the notification action.
            ACTION_FINISH -> controller.finish()
            else -> Unit
        }
        if (watcher?.isActive != true) watcher = lifecycleScope.launch { watch() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Stopped by the system while recording: save what was captured.
        if (controller.state.value is RecordingState.Active) controller.finish()
        if (foreground) leaveForeground()
        super.onDestroy()
    }

    /**
     * Updates the notification until the recording ends, then tells the user (when hidden) and stops. The
     * notification is built off the main thread (each build reads the app locale: a file below API 33, `LocaleManager`
     * from API 33) and posted on it, so it is never posted after the service left the foreground.
     */
    private suspend fun watch() {
        var shown: RecordingNotifications.Content? = null
        val ended = controller.state
            .map { state ->
                val content = if (state.inProgress) notifications.content(state) else null
                val changed = content != null && content != shown
                if (changed) shown = content
                state to if (changed) notifications.ongoing(state, controller.elapsedNowMs) else null
            }
            .flowOn(Dispatchers.Default)
            .first { (state, notification) ->
                if (notification != null) post(notification)
                !state.inProgress
            }
            .first
        val outcome = (ended as? RecordingState.Ended)?.outcome
        val noteId = (ended as? RecordingState.Ended)?.noteId
        if (noteId != null && !graph.visibility.visible.value) {
            when (outcome) {
                RecordingOutcome.StoppedForSpace -> notifications.stoppedForSpace(noteId)
                RecordingOutcome.StoppedAtFileLimit -> notifications.stoppedAtFileLimit(noteId)
                RecordingOutcome.MicrophoneFailed -> notifications.microphoneFailed(noteId)
                else -> Unit
            }
        }
        leaveForeground()
        stopSelf(lastStartId)
    }

    /**
     * Enters the foreground for [action]. A start through startForegroundService ([ACTION_START]) must reach
     * startForeground, also when the recording has ended meanwhile; a stale notification action without a recording
     * does not. False when the service should stop.
     */
    private fun enterForeground(action: String?): Boolean {
        if (action != ACTION_START && !controller.state.value.inProgress) return false
        foreground = startMicrophoneForeground()
        if (!foreground) controller.onServiceRefused()
        return foreground
    }

    private fun post(notification: Notification) {
        if (!foreground) return
        getSystemService(NotificationManager::class.java)?.notify(RecordingNotifications.RECORDING_ID, notification)
    }

    /** `startForeground` with the microphone type, inside the catch of architecture 5.7; false when refused. */
    private fun startMicrophoneForeground(): Boolean {
        val notification = notifications.ongoing(controller.state.value, controller.elapsedNowMs)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        return try {
            startForegroundWithType(RecordingNotifications.RECORDING_ID, notification, type)
            true
        } catch (
            @Suppress("TooGenericExceptionCaught") // the refusals have no common type below RuntimeException
            error: RuntimeException,
        ) {
            if (!ProcessingService.isForegroundRefusal(error)) throw error
            Log.w(TAG, "Android refused startForeground for the microphone", error)
            false
        }
    }

    private fun leaveForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    companion object {
        const val ACTION_START = "org.cassini.android.recording.START"
        const val ACTION_PAUSE = "org.cassini.android.recording.PAUSE"
        const val ACTION_RESUME = "org.cassini.android.recording.RESUME"
        const val ACTION_FINISH = "org.cassini.android.recording.FINISH"

        private const val TAG = "RecordingService"

        fun intent(context: Context, action: String): Intent =
            Intent(context, RecordingService::class.java).setAction(action)

        fun startIntent(context: Context): Intent = intent(context, ACTION_START)
    }
}
