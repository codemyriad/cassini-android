package io.codemyriad.cassini.testing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.codemyriad.cassini.background.Background
import io.codemyriad.cassini.test.CiSmoke
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Changes the real notification only after a retained-state query, so a callback must wake the observer. */
@CiSmoke
@RunWith(AndroidJUnit4::class)
class NotificationWatchTest {
    @get:Rule val notifications = NotificationWatch()

    private val manager get() = Background.context.getSystemService(NotificationManager::class.java)

    @Before fun setUp() {
        Background.grant()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, CHANNEL, NotificationManager.IMPORTANCE_LOW))
    }

    @After fun cleanUp() {
        manager.cancel(ID)
        manager.deleteNotificationChannel(CHANNEL)
    }

    @Test fun aPostAfterTheFirstQueryWakesTheObserver() = runBlocking {
        var queried = false
        val posted = notifications.await("The posted notification callback wakes the query", WAIT_MS) {
            val current = manager.activeNotifications.firstOrNull { it.id == ID }
            if (!queried) {
                queried = true
                manager.notify(ID, notification())
            }
            current
        }
        assertNotNull(posted)
    }

    @Test fun aRemovalAfterTheFirstQueryWakesTheObserver() = runBlocking {
        val before = NotificationEvents.events.value.revision
        manager.notify(ID, notification())
        // Consume the seed post's callback before testing the independent removal callback.
        withTimeout(WAIT_MS) { NotificationEvents.events.first { it.revision != before } }
        check(manager.activeNotifications.any { it.id == ID })
        var queried = false
        notifications.await("The removed notification callback wakes the query", WAIT_MS) {
            val absent = manager.activeNotifications.none { it.id == ID }
            if (!queried) {
                queried = true
                manager.cancel(ID)
            }
            Unit.takeIf { absent }
        }
    }

    private fun notification(): Notification = Notification.Builder(Background.context, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Notification observer check")
        .build()

    private companion object {
        const val CHANNEL = "notification-observer-test"
        const val ID = 24791
        const val WAIT_MS = 20_000L
    }
}
