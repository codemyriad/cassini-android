package org.cassini.android.processing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.ContextCompat
import org.cassini.android.R

/**
 * The notification channels (architecture 5.10): [RECORDING] and [PROCESSING] are LOW, [RESULTS] is DEFAULT and
 * silent. Created at every process start, which also renames them after a language change. The names come from
 * `ContextCompat.getContextForLanguage`, so they follow the app language also below API 33.
 */
object NotificationChannels {
    const val RECORDING = "recording"
    const val PROCESSING = "processing"
    const val RESULTS = "results"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val names = ContextCompat.getContextForLanguage(context)
        val results = NotificationChannel(
            RESULTS,
            names.getString(R.string.notif_channel_results),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    RECORDING,
                    names.getString(R.string.recording_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
                NotificationChannel(
                    PROCESSING,
                    names.getString(R.string.notif_channel_processing),
                    NotificationManager.IMPORTANCE_LOW,
                ),
                results,
            ),
        )
    }
}
