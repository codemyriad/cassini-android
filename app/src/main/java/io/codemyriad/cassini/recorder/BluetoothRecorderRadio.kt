package io.codemyriad.cassini.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.codemyriad.cassini.core.recorder.StreamXrecInput
import io.codemyriad.cassini.core.recorder.XrecInput
import io.codemyriad.cassini.data.recorder.RecorderDevice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/** The runtime permission that the recorder needs: "Nearby devices" (BLUETOOTH_CONNECT) from Android 12. */
object RecorderPermissions {
    /** The permissions to request; empty below Android 12, where BLUETOOTH is granted at install. */
    fun required(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyArray()
    }

    fun granted(context: Context): Boolean = required().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * [RecorderRadio] over the phone's Bluetooth adapter: bonds with `createBond`, and opens a secure RFCOMM socket to the
 * Serial Port Profile service of the recorder (`createRfcommSocketToServiceRecord`: the recorder accepts only an
 * authenticated, encrypted MITM bond). The app does not scan; the companion device chooser of Android finds the
 * recorder ([RecorderCompanion]).
 */
@SuppressLint("MissingPermission") // readiness() checks BLUETOOTH_CONNECT; RecorderSync calls nothing else before it
class BluetoothRecorderRadio(context: Context, private val io: CoroutineDispatcher) : RecorderRadio {
    private val app = context.applicationContext

    private val adapter: BluetoothAdapter?
        get() = app.getSystemService(BluetoothManager::class.java)?.adapter

    override fun readiness(): RadioReadiness {
        val adapter = adapter
        return when {
            !app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) || adapter == null ->
                RadioReadiness.NO_BLUETOOTH
            !RecorderPermissions.granted(app) -> RadioReadiness.NO_PERMISSION
            !adapter.isEnabled -> RadioReadiness.OFF
            else -> RadioReadiness.READY
        }
    }

    override suspend fun bond(address: String): BondResult = withContext(io) {
        val device = remote(address)
        if (device.bondState == BluetoothDevice.BOND_BONDED) return@withContext BondResult.BONDED_BEFORE
        // Android shows its pairing dialog; the bond ends BONDED, or NONE when the user or the recorder refuses.
        var seenBonding = false
        val end = withTimeoutOrNull(BOND_WAIT_MS) {
            bondStates(device).first { state ->
                if (state == BluetoothDevice.BOND_BONDING) seenBonding = true
                state == BluetoothDevice.BOND_BONDED || state == NOT_STARTED ||
                    (state == BluetoothDevice.BOND_NONE && seenBonding)
            }
        }
        if (end == BluetoothDevice.BOND_BONDED) BondResult.BONDED else BondResult.FAILED
    }

    /**
     * The bond states of [device] from `ACTION_BOND_STATE_CHANGED`, after `createBond()`. The receiver is registered
     * before the bond starts, so a quick refusal (BONDING then NONE) is not missed. [NOT_STARTED] comes when the bond
     * did not start, or is still NONE after [START_GRACE_MS].
     */
    private fun bondStates(device: BluetoothDevice): Flow<Int> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val changed = IntentCompat.getParcelableExtra(
                    intent,
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java,
                )
                if (changed?.address == device.address) {
                    trySend(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR))
                }
            }
        }
        // The Bluetooth stack sends the broadcast from its own process.
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val state = device.bondState
        if (state != BluetoothDevice.BOND_BONDING && !device.createBond()) trySend(NOT_STARTED) else trySend(state)
        launch {
            delay(START_GRACE_MS)
            if (device.bondState == BluetoothDevice.BOND_NONE) trySend(NOT_STARTED)
        }
        awaitClose { app.unregisterReceiver(receiver) }
    }

    override suspend fun open(address: String): RecorderLink = openClosing(
        io = io,
        create = { remote(address).createRfcommSocketToServiceRecord(SPP) },
        // connect() blocks and ignores interrupts: closing the socket is the only way to stop it.
        connect = { it.connect() },
        close = ::closeQuietly,
        wrap = ::SocketLink,
    )

    /** The bonded devices whose name is `Cassini_…`: the choice when the companion device chooser is missing. */
    fun bondedRecorders(): List<RecorderDevice> {
        if (readiness() != RadioReadiness.READY) return emptyList()
        return adapter?.bondedDevices.orEmpty()
            .mapNotNull { device ->
                device.name?.takeIf { RecorderCompanion.NAME.matches(it) }?.let { RecorderDevice(device.address, it) }
            }
            .sortedBy { it.name }
    }

    private fun remote(address: String): BluetoothDevice =
        adapter?.getRemoteDevice(address) ?: throw IOException("This phone has no Bluetooth")

    private class SocketLink(private val socket: BluetoothSocket) : RecorderLink {
        override val output: OutputStream = socket.outputStream
        private val stream = StreamXrecInput(socket.inputStream, threadName = "recorder-link")
        override val input: XrecInput = stream

        override fun close() {
            try {
                stream.close()
            } catch (_: IOException) {
                // Already closed.
            }
            closeQuietly(socket)
        }
    }

    private companion object {
        /** The Serial Port Profile service class: the recorder's XREC server (RFCOMM channel 1). */
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val BOND_WAIT_MS = 60_000L

        /** A bond that is still NONE this long after `createBond()` did not start. */
        const val START_GRACE_MS = 5_000L

        /** Not a bond state of Android: the bond did not start. */
        const val NOT_STARTED = -1

        fun closeQuietly(socket: BluetoothSocket) {
            try {
                socket.close()
            } catch (_: IOException) {
                // Already closed.
            }
        }
    }
}
