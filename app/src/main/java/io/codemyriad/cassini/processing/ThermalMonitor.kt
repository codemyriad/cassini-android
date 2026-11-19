package io.codemyriad.cassini.processing

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observes device thermal throttling so recording-time precomputation yields immediately at or above
 * `PowerManager.THERMAL_STATUS_SEVERE` without ever affecting `OpusCapture` (D-1124).
 */
interface ThermalMonitor {
    val throttled: StateFlow<Boolean>

    fun close() = Unit

    companion object {
        val NONE: ThermalMonitor = object : ThermalMonitor {
            override val throttled: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        }

        /** True when [status] is at or above `PowerManager.THERMAL_STATUS_SEVERE`. */
        fun isThrottled(status: Int): Boolean = status >= PowerManager.THERMAL_STATUS_SEVERE
    }
}

/** [ThermalMonitor] backed by `PowerManager.OnThermalStatusChangedListener` on API 29+. */
class AndroidThermalMonitor(context: Context) : ThermalMonitor {
    private val app = context.applicationContext
    private val state = MutableStateFlow(false)
    private var unregister: (() -> Unit)? = null

    override val throttled: StateFlow<Boolean> = state.asStateFlow()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            attachQ()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun attachQ() {
        val manager = app.getSystemService(PowerManager::class.java) ?: return
        state.value = ThermalMonitor.isThrottled(manager.currentThermalStatus)
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            state.value = ThermalMonitor.isThrottled(status)
        }
        try {
            manager.addThermalStatusListener(listener)
            unregister = {
                try {
                    manager.removeThermalStatusListener(listener)
                } catch (_: RuntimeException) {
                    // Best-effort cleanup on shutdown.
                }
            }
        } catch (_: RuntimeException) {
            // Some stripped emulators reject thermal listeners; keep the initial status safely.
        }
    }

    override fun close() {
        unregister?.invoke()
        unregister = null
    }
}
