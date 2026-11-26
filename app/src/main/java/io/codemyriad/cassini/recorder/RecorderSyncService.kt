package io.codemyriad.cassini.recorder

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.codemyriad.cassini.CassiniApplication
import io.codemyriad.cassini.R
import io.codemyriad.cassini.inAppLanguage
import io.codemyriad.cassini.processing.NotificationChannels
import io.codemyriad.cassini.startForegroundWithType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.codemyriad.cassini.ui.R as UiR

/**
 * The foreground lifetime of a recorder sync (type `connectedDevice`): it holds the process while
 * [RecorderSync.activity] is not idle, shows the step in its notification, and stops after the sync. The sync itself
 * runs in the application scope ([RecorderRunner]); a refused start does not stop it. The notification changes at
 * most every [POST_INTERVAL_MS]: Android drops the updates of a package that posts more than a few each second.
 */
class RecorderSyncService : LifecycleService() {
    private val recorder: RecorderFeature get() = (application as CassiniApplication).graph.recorder
    private val sync: RecorderSync get() = recorder.sync
    private val runner: RecorderRunner get() = recorder.runner
    private var follower: Job? = null
    private var foreground = false
    private var wakeLock: PowerManager.WakeLock? = null

    /** The last start request, and whether it came while [follow] ran: all on the main thread. */
    private var lastStartId = 0
    private var restarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        val first = !foreground
        // Android requires startForeground on every startForegroundService delivery, even when already foregrounded.
        if (enterForeground(RecorderNotifications.build(this, sync.activity.value))) {
            foreground = true
            acquireWakeLock()
        } else if (first) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (follower?.isActive == true) restarted = true else follower = lifecycleScope.launch { follow() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * Waits for an in-flight service run to enter [RecorderSync.activity], shows its steps, then stops as soon as the
     * sync is idle and no service run remains, unless a new start request came meanwhile.
     */
    private suspend fun follow() {
        do {
            restarted = false
            if (sync.activity.value == RecorderActivity.Idle && runner.serviceActive.value) {
                val changed = withTimeoutOrNull(START_GRACE_MS) {
                    combine(sync.activity, runner.serviceActive) { activity, active ->
                        activity != RecorderActivity.Idle || !active
                    }.first { it }
                }
                if (changed == null && !runner.serviceActive.value) break
            }
            followActiveSync()
            if (runner.serviceActive.value && sync.activity.value == RecorderActivity.Idle) {
                // Waiting for background transcription + .cwx upload round-trip: stay foregrounded with a clear status.
                val waitingTx = sync.activeTranscriptions()
                val waitingUp = sync.pendingUploads().size
                if (waitingTx > 0 || waitingUp > 0) {
                    post(RecorderNotifications.buildWaiting(this, waitingTx, waitingUp))
                }
                withTimeoutOrNull(POST_INTERVAL_MS) {
                    combine(sync.activity, runner.serviceActive) { activity, active ->
                        activity != RecorderActivity.Idle || !active
                    }.first { it }
                }
            }
        } while (restarted || runner.serviceActive.value)
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelfResult(lastStartId)
    }

    private suspend fun followActiveSync() {
        var activity = sync.activity.value
        while (activity != RecorderActivity.Idle) {
            post(RecorderNotifications.build(this, activity))
            withTimeoutOrNull(POST_INTERVAL_MS) { sync.activity.first { it == RecorderActivity.Idle } }
            if (sync.activity.value != RecorderActivity.Idle) {
                sync.activity.first { it != activity }
            }
            activity = sync.activity.value
        }
    }

    private fun post(notification: Notification) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) NotificationManagerCompat.from(this).notify(RecorderNotifications.ID, notification)
    }

    private fun enterForeground(notification: Notification): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        startForegroundWithType(RecorderNotifications.ID, notification, type)
        true
    } catch (error: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (API 31+) and the start limits are IllegalStateException.
        Log.w(TAG, "Android refused the recorder sync service", error)
        false
    } catch (error: SecurityException) {
        // API 34+: no BLUETOOTH_CONNECT grant and no companion association.
        Log.w(TAG, "Android refused the recorder sync service", error)
        false
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

    companion object {
        private const val TAG = "RecorderSyncService"
        private const val WAKE_LOCK_TAG = "cassini:recorder_sync"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
        private const val START_GRACE_MS = 10_000L
        private const val POST_INTERVAL_MS = 500L

        fun intent(context: Context): Intent = Intent(context, RecorderSyncService::class.java)
    }
}

/** [RecorderServiceStarter] with `startForegroundService`, inside the catch of the Android 12+ refusals. */
class AndroidRecorderServiceStarter(context: Context) : RecorderServiceStarter {
    private val app = context.applicationContext

    override fun start(): Boolean = try {
        ContextCompat.startForegroundService(app, RecorderSyncService.intent(app))
        true
    } catch (error: IllegalStateException) {
        Log.w(TAG, "Android refused to start the recorder sync service", error)
        false
    } catch (error: SecurityException) {
        Log.w(TAG, "Android refused to start the recorder sync service", error)
        false
    }

    private companion object {
        const val TAG = "RecorderSyncService"
    }
}

/** The ongoing notification of a recorder sync, in the app language (architecture 5.10). */
object RecorderNotifications {
    /** After recording (1), processing (2) and results (3). */
    const val ID = 4
    const val REBIND_ID = 5

    fun notifyRebindNeeded(context: Context) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val res = context.inAppLanguage()
        val notification = NotificationCompat.Builder(context, NotificationChannels.RECORDER)
            .setSmallIcon(UiR.drawable.ui_ic_stat_logo)
            .setContentTitle(res.getString(R.string.recorder_title))
            .setContentText(res.getString(R.string.recorder_rebind_needed))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openIntent(context))
            .build()
        NotificationManagerCompat.from(context).notify(REBIND_ID, notification)
    }

    fun build(context: Context, activity: RecorderActivity): Notification {
        val lang = context.inAppLanguage()
        val step = activity as? RecorderActivity.Syncing
        val progress = if (step != null && step.total > 0) {
            (step.done * PERMILLE / step.total).toInt().coerceIn(0, PERMILLE)
        } else {
            null
        }
        return build(context, lang.resources, RecorderTexts.activity(lang, activity), progress)
    }

    fun buildWaiting(context: Context, transcribing: Int, pendingUploads: Int): Notification {
        val res = context.inAppLanguage().resources
        val text = when {
            transcribing > 0 ->
                res.getQuantityString(R.plurals.recorder_notif_waiting, transcribing, transcribing)
            pendingUploads > 0 ->
                res.getQuantityString(R.plurals.recorder_notif_sending_queued, pendingUploads, pendingUploads)
            else -> ""
        }
        return build(context, res, text, progress = null)
    }

    private fun build(
        context: Context,
        res: android.content.res.Resources,
        text: String,
        progress: Int?,
    ): Notification {
        val title = res.getString(R.string.recorder_notif_title)
        val public = NotificationCompat.Builder(context, NotificationChannels.RECORDER)
            .setSmallIcon(UiR.drawable.ui_ic_stat_logo)
            .setContentTitle(title)
            .build()
        val builder = NotificationCompat.Builder(context, NotificationChannels.RECORDER)
            .setSmallIcon(UiR.drawable.ui_ic_stat_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(openIntent(context))
        if (progress != null) {
            builder.setProgress(PERMILLE, progress, false)
        }
        return builder.build()
    }

    private fun openIntent(context: Context): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setPackage(context.packageName)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            ID,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private const val PERMILLE = 1000
}
