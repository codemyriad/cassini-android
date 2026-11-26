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
        val app = application as? CassiniApplication ?: return
        app.graph.recorder.onDevicePresent(address, "companion_appeared")
    }

    @Deprecated("Replaced by AssociationInfo overload in API 33")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        Log.i(TAG, "Companion recorder appeared: $address")
        val app = application as? CassiniApplication ?: return
        app.graph.recorder.onDevicePresent(address, "companion_appeared")
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
 * Secondary zero-polling wakeup:
 * - [BluetoothDevice.ACTION_ACL_CONNECTED]: triggers a sync when the connected classic Bluetooth peer matches the bound
 *   recorder (covers pre-API 31 phones and devices bound without a companion association).
 * - [Intent.ACTION_BOOT_COMPLETED] and [Intent.ACTION_MY_PACKAGE_REPLACED]: re-registers companion presence observation
 *   when a recorder is bound and "Auto sync" is enabled.
 */
class RecorderBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action?.takeIf { it in ALLOWED_ACTIONS }
        val app = context.applicationContext as? CassiniApplication
        if (action == null || app == null) return
        val pending = goAsync()
        val graph = app.graph
        graph.appScope.launch(graph.dispatchers.io) {
            try {
                withTimeoutOrNull(RECEIVER_BUDGET_MS) {
                    when (action) {
                        BluetoothDevice.ACTION_ACL_CONNECTED -> {
                            val device = IntentCompat.getParcelableExtra(
                                intent,
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java,
                            )
                            val address = device?.address ?: return@withTimeoutOrNull
                            graph.recorder.onDevicePresent(address, "acl_connected")
                        }
                        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                            Log.i(TAG, "Refreshing recorder presence observation after $action")
                            graph.recorder.ensurePresenceObserved(action)
                        }
                    }
                }
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        const val TAG = "RecorderCompanion"
        const val RECEIVER_BUDGET_MS = 8_000L
        val ALLOWED_ACTIONS = setOf(
            BluetoothDevice.ACTION_ACL_CONNECTED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
