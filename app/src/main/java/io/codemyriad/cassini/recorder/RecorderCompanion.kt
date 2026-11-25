package io.codemyriad.cassini.recorder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.IntentCompat
import io.codemyriad.cassini.data.recorder.RecorderDevice
import java.util.regex.Pattern

/**
 * The companion device chooser of Android (CompanionDeviceManager): the system finds the recorders by name
 * (`Cassini_XX`) and the user picks one. The app needs no scan or location permission for it, and an associated app
 * may start its foreground service from the background (with `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_
 * BACKGROUND`), which "Auto sync" uses. Devices without the chooser use the bonded recorders instead.
 */
object RecorderCompanion {
    /** The name that every recorder announces: `Cassini_` and two uppercase hex digits. */
    val NAME = Regex("Cassini_.*")

    private const val TAG = "RecorderCompanion"

    fun supported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP) &&
            context.getSystemService(CompanionDeviceManager::class.java) != null

    /**
     * Asks Android to find recorders. [onChooser] gets the chooser to launch for a result; [onFailure] the reason when
     * Android cannot show it.
     */
    fun associate(context: Context, onChooser: (IntentSender) -> Unit, onFailure: (CharSequence?) -> Unit) {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return onFailure(null)
        val filter = BluetoothDeviceFilter.Builder().setNamePattern(Pattern.compile(NAME.pattern)).build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
        val callback = object : CompanionDeviceManager.Callback() {
            // From API 33 the default onAssociationPending calls this method.
            @Deprecated("Replaced by onAssociationPending in API 33")
            override fun onDeviceFound(chooserLauncher: IntentSender) = onChooser(chooserLauncher)

            override fun onFailure(error: CharSequence?) = onFailure(error)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.associate(request, context.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            manager.associate(request, callback, Handler(Looper.getMainLooper()))
        }
    }

    /** The recorder of the chooser's result, or null when the user picked nothing. */
    fun deviceOf(context: Context, result: Intent?): RecorderDevice? {
        val associated = if (result != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            associationDevice(context, result)
        } else {
            null
        }
        return associated ?: result?.let { extraDevice(it) }?.let {
            RecorderDevice(it.address, bluetoothName(context, it) ?: DEFAULT_NAME)
        }
    }

    @Suppress("DEPRECATION") // CompanionDeviceManager still populates EXTRA_DEVICE for classic Bluetooth results
    private fun extraDevice(result: Intent): BluetoothDevice? =
        IntentCompat.getParcelableExtra(result, CompanionDeviceManager.EXTRA_DEVICE, BluetoothDevice::class.java)

    /** Ends the association with [address] (Forget recorder). */
    fun disassociate(context: Context, address: String) {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.myAssociations.filter { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }
                    .forEach { manager.disassociate(it.id) }
            } else {
                @Suppress("DEPRECATION")
                if (manager.associations.any { it.equals(address, ignoreCase = true) }) manager.disassociate(address)
            }
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "No association to end", error)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun associationDevice(context: Context, result: Intent): RecorderDevice? {
        val info = IntentCompat.getParcelableExtra(
            result,
            CompanionDeviceManager.EXTRA_ASSOCIATION,
            AssociationInfo::class.java,
        )
        val address = info?.deviceMacAddress?.toString()?.uppercase() ?: return null
        val associated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.associatedDevice?.bluetoothDevice
        } else {
            null
        }
        val name = info.displayName?.toString()?.takeIf { it.isNotBlank() }
            ?: associated?.let { bluetoothName(context, it) }
            ?: extraDevice(result)?.let { bluetoothName(context, it) }
            ?: remoteDevice(context, address)?.let { bluetoothName(context, it) }
            ?: DEFAULT_NAME
        return RecorderDevice(address, name)
    }

    private fun remoteDevice(context: Context, address: String): BluetoothDevice? = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)
    } catch (_: IllegalArgumentException) {
        null
    }

    @SuppressLint("MissingPermission") // RecorderPermissions.granted checks BLUETOOTH_CONNECT first
    private fun bluetoothName(context: Context, device: BluetoothDevice): String? =
        if (RecorderPermissions.granted(context)) {
            try {
                device.name?.takeIf { it.isNotBlank() }
            } catch (_: SecurityException) {
                null
            }
        } else {
            null
        }

    private const val DEFAULT_NAME = "Cassini"
}
