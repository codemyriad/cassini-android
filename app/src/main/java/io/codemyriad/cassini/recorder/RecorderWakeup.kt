package io.codemyriad.cassini.recorder

import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.IntentCompat
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.CassiniApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bound by [android.companion.CompanionDeviceManager] (API 31+) when the associated recorder connects over Bluetooth
 * (for example via its post-recording SDP doorbell poke). Wakes [RecorderFeature] without polling or scanning.
 */
@RequiresApi(Build.VERSION_CODES.S)
class RecorderCompanionService : CompanionDeviceService() {
    @Suppress("OVERRIDE_DEPRECATION") // Default API 36 onDevicePresenceEvent forwards to this callback for API 33-36.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val address = associationInfo.deviceMacAddress?.toString()
        Log.i(TAG, "Companion recorder appeared: $address (id=${associationInfo.id})")
        wake(address)
    }

    @Deprecated("Replaced by AssociationInfo overload in API 33")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        Log.i(TAG, "Companion recorder appeared: $address")
        wake(address)
    }

    /** Wakes the recorder sync only while "Voice recorder" is on (D-1178); otherwise the feature is not created. */
    private fun wake(address: String?) {
        val graph = (application as? CassiniApplication)?.graph ?: return
        graph.appScope.launch(graph.dispatchers.io) {
            wakeCompanionDevice(graph, address)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.i(TAG, "Companion recorder disappeared: ${associationInfo.deviceMacAddress} (id=${associationInfo.id})")
    }

    @Deprecated("Replaced by AssociationInfo overload in API 33")
    override fun onDeviceDisappeared(address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        Log.i(TAG, "Companion recorder disappeared: $address")
    }

    private companion object {
        const val TAG = "RecorderCompanion"
    }
}

/**
 * Admits a companion presence wakeup through [AppGraph.wakeRecorderOnDevicePresent], rejecting stale wakeups if
 * "Voice recorder" was turned off before admission completed.
 */
internal suspend fun wakeCompanionDevice(
    graph: AppGraph,
    address: String?,
    afterEnabledCheck: suspend () -> Unit = {},
): Boolean {
    val admitted = graph.wakeRecorderOnDevicePresent(address, "companion_appeared", afterEnabledCheck)
    if (!admitted) {
        Log.i("RecorderCompanion", "Voice recorder is off; ignoring companion_appeared for $address")
    }
    return admitted
}

/**
 * Secondary zero-polling wakeup:
 * - [BluetoothDevice.ACTION_ACL_CONNECTED]: triggers a sync when the connected classic Bluetooth peer matches the bound
 *   recorder (covers pre-API 31 phones and devices bound without a companion association).
 * - [Intent.ACTION_BOOT_COMPLETED] and [Intent.ACTION_MY_PACKAGE_REPLACED]: re-registers companion presence observation
 *   when a recorder is bound and "Auto sync" is enabled.
 *
 * All of them are ignored while "Voice recorder" (Settings › Experimental features, D-1178) is off.
 */
class RecorderBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action?.takeIf { it in ALLOWED_ACTIONS }
        val app = context.applicationContext as? CassiniApplication
        val address = if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            IntentCompat.getParcelableExtra(
                intent,
                BluetoothDevice.EXTRA_DEVICE,
                BluetoothDevice::class.java,
            )?.address
        } else {
            null
        }
        val missingAddress = action == BluetoothDevice.ACTION_ACL_CONNECTED && address == null
        if (action == null || app == null || missingAddress) return
        val pending = goAsync()
        val graph = app.graph
        graph.appScope.launch(graph.dispatchers.io) {
            try {
                withTimeoutOrNull(RECEIVER_BUDGET_MS) {
                    // D-1178: with "Voice recorder" off no wakeup creates or starts the recorder sync.
                    handleAction(graph, action, address)
                }
            } finally {
                pending?.finish()
            }
        }
    }

    internal companion object {
        private const val TAG = "RecorderCompanion"
        private const val RECEIVER_BUDGET_MS = 8_000L
        private val ALLOWED_ACTIONS = setOf(
            BluetoothDevice.ACTION_ACL_CONNECTED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )

        internal suspend fun handleAction(
            graph: AppGraph,
            action: String,
            address: String?,
            afterEnabledCheck: suspend () -> Unit = {},
        ): Boolean = when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (address == null) {
                    false
                } else {
                    graph.wakeRecorderOnDevicePresent(address, "acl_connected", afterEnabledCheck).also { admitted ->
                        if (!admitted) Log.i(TAG, "Voice recorder is off; ignoring $action")
                    }
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                graph.refreshRecorderPresence(action, afterEnabledCheck).also { admitted ->
                    if (!admitted) {
                        Log.i(TAG, "Voice recorder is off; ignoring $action")
                    } else {
                        Log.i(TAG, "Refreshed recorder presence observation after $action")
                    }
                }
            }
            else -> false
        }
    }
}
