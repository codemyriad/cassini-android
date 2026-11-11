package io.codemyriad.cassini.testing

import android.content.ComponentName
import io.codemyriad.cassini.background.Background
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.ExternalResource

/** Enables the instrumented build's listener for a test and restores its original access afterward. */
internal class NotificationWatch : ExternalResource() {
    private val component get() = ComponentName(Background.context, NotificationEvents::class.java).flattenToString()
    private var restoreAccess = false

    override fun before() = runBlocking {
        val enabled = Background.shell("settings get secure enabled_notification_listeners").trim().split(':')
        restoreAccess = component !in enabled
        try {
            Background.shell("cmd notification allow_listener $component")
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

    private suspend fun restore() {
        if (restoreAccess) {
            Background.shell("cmd notification disallow_listener $component")
            restoreAccess = false
            withTimeoutOrNull(CONNECT_MS) { NotificationEvents.events.first { !it.connected } }
                ?: throw AssertionError("The notification listener did not disconnect")
        }
    }

    private companion object {
        const val CONNECT_MS = 30_000L
    }
}
