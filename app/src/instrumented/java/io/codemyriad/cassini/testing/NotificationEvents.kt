package io.codemyriad.cassini.testing

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.codemyriad.cassini.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Notification callbacks of the instrumented build types, in the same process as the device tests. */
class NotificationEvents : NotificationListenerService() {
    override fun onListenerConnected() {
        changes.update { Signal(connected = true, revision = it.revision + 1) }
    }

    override fun onListenerDisconnected() = disconnected()

    override fun onDestroy() {
        disconnected()
        super.onDestroy()
    }

    override fun onNotificationPosted(notification: StatusBarNotification) = changed(notification)

    override fun onNotificationRemoved(notification: StatusBarNotification) = changed(notification)

    private fun changed(notification: StatusBarNotification) {
        if (notification.packageName == BuildConfig.APPLICATION_ID) {
            changes.update { it.copy(revision = it.revision + 1) }
        }
    }

    private fun disconnected() {
        changes.update { Signal(connected = false, revision = it.revision + 1) }
    }

    data class Signal(val connected: Boolean, val revision: Long)

    companion object {
        private val changes = MutableStateFlow(Signal(connected = false, revision = 0))
        val events = changes.asStateFlow()
    }
}
