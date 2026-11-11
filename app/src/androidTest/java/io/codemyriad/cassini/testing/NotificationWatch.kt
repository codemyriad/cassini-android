package io.codemyriad.cassini.testing

import android.content.ComponentName
import android.os.Build
import io.codemyriad.cassini.background.Background
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.ExternalResource

/** Enables the instrumented build's listener for a test and restores its original access afterward. */
class NotificationWatch : ExternalResource() {
    private val listener get() = ComponentName(Background.context, NotificationEvents::class.java)
    private val component get() = listener.flattenToString()
    private var restoreAccess = false

    override fun before() = runBlocking {
        val enabled = Background.shell("settings get secure enabled_notification_listeners").trim().split(':')
        restoreAccess = enabled.none { ComponentName.unflattenFromString(it) == listener }
        try {
            setAccess(true)
            withTimeoutOrNull(CONNECT_MS) { NotificationEvents.events.first { it.connected } }
                ?: throw AssertionError("The notification listener did not connect")
        } catch (failure: Throwable) {
            restore()
            throw failure
        }
        Unit
    }

    override fun after() = runBlocking { restore() }

    suspend fun <T : Any> await(message: String, timeoutMs: Long, query: () -> T?): T =
        withTimeoutOrNull(timeoutMs) {
            var value: T? = null
            while (value == null) {
                val before = NotificationEvents.events.value
                if (!before.connected) throw AssertionError("The notification listener disconnected while $message")
                value = query()
                if (value == null) NotificationEvents.events.first { it.revision != before.revision }
            }
            value
        } ?: throw AssertionError(message)

    private fun setAccess(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val command = if (enabled) "allow_listener" else "disallow_listener"
            Background.shell("cmd notification $command $component")
        } else {
            // API 26–27 bind listeners when this secure setting changes; their shell has no allow_listener.
            val listeners = Background.shell("settings get secure enabled_notification_listeners").trim().split(':')
                .filter { it.isNotEmpty() && it != "null" && ComponentName.unflattenFromString(it) != listener }.toMutableList()
            if (enabled) listeners += component
            if (listeners.isEmpty()) {
                Background.shell("settings delete secure enabled_notification_listeners")
            } else {
                Background.shell("settings put secure enabled_notification_listeners ${listeners.joinToString(":")}")
            }
        }
    }

    private suspend fun restore() {
        if (restoreAccess) {
            setAccess(false)
            restoreAccess = false
            withTimeoutOrNull(CONNECT_MS) { NotificationEvents.events.first { !it.connected } }
                ?: throw AssertionError("The notification listener did not disconnect")
        }
    }

    private companion object {
        const val CONNECT_MS = 30_000L
    }
}
