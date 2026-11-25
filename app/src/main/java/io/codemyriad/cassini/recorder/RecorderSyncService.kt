package io.codemyriad.cassini.recorder

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
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
    private val sync: RecorderSync get() = (application as CassiniApplication).graph.recorder.sync
    private var follower: Job? = null
    private var foreground = false

    /** The last start request, and whether it came while [follow] ran: all on the main thread. */
    private var lastStartId = 0
    private var restarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        if (!foreground) {
            foreground = enterForeground(RecorderNotifications.build(this, sync.activity.value))
            if (!foreground) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        if (follower?.isActive == true) restarted = true else follower = lifecycleScope.launch { follow() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * Waits for the sync to start (the service can start first), shows its steps, then stops when it is idle, unless a
     * new start request came meanwhile (then it follows the next sync).
     */
    private suspend fun follow() {
        do {
            restarted = false
            withTimeoutOrNull(START_GRACE_MS) { sync.activity.first { it != RecorderActivity.Idle } }
            // A StateFlow keeps only its last value: while the collector waits, the steps in between are skipped.
            sync.activity.takeWhile { it != RecorderActivity.Idle }.collect { activity ->
                post(RecorderNotifications.build(this, activity))
                delay(POST_INTERVAL_MS)
            }
        } while (restarted)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelfResult(lastStartId)
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

    companion object {
        private const val TAG = "RecorderSyncService"
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

    fun build(context: Context, activity: RecorderActivity): Notification {
        val res = context.inAppLanguage()
        val title = res.getString(R.string.recorder_notif_title)
        val public = NotificationCompat.Builder(context, NotificationChannels.RECORDER)
            .setSmallIcon(UiR.drawable.ui_ic_stat_logo)
            .setContentTitle(title)
            .build()
        val builder = NotificationCompat.Builder(context, NotificationChannels.RECORDER)
            .setSmallIcon(UiR.drawable.ui_ic_stat_logo)
            .setContentTitle(title)
            .setContentText(RecorderTexts.activity(res, activity))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(openIntent(context))
        val step = activity as? RecorderActivity.Syncing
        if (step != null && step.total > 0) {
            val permille = (step.done * PERMILLE / step.total).toInt().coerceIn(0, PERMILLE)
            builder.setProgress(PERMILLE, permille, false)
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
