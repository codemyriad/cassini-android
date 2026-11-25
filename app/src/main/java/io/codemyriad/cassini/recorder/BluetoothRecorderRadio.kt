package io.codemyriad.cassini.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.codemyriad.cassini.core.recorder.StreamXrecInput
import io.codemyriad.cassini.core.recorder.XrecInput
import io.codemyriad.cassini.data.recorder.RecorderDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

    override suspend fun bond(address: String): Boolean = withContext(io) {
        val device = remote(address)
        if (device.bondState == BluetoothDevice.BOND_BONDED) return@withContext true
        if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) return@withContext false
        // Android shows its pairing dialog; the bond ends BONDED, or NONE when the user or the recorder refuses.
        withTimeoutOrNull(BOND_WAIT_MS) { awaitBond(device) } ?: false
    }

    override suspend fun open(address: String): RecorderLink = withContext(io) {
        val socket = remote(address).createRfcommSocketToServiceRecord(SPP)
        coroutineScope {
            // connect() blocks and ignores interrupts: closing the socket is the only way to stop it.
            val connecting = async { socket.connect() }
            try {
                connecting.await()
            } catch (error: CancellationException) {
                closeQuietly(socket)
                throw error
            } catch (error: IOException) {
                closeQuietly(socket)
                throw error
            }
        }
        SocketLink(socket)
    }

    /** The bonded devices whose name is `Cassini_…`: the choice when the companion device chooser is missing. */
    fun bondedRecorders(): List<RecorderDevice> {
        if (readiness() != RadioReadiness.READY) return emptyList()
        return adapter?.bondedDevices.orEmpty()
            .mapNotNull { device ->
                device.name?.takeIf { RecorderCompanion.NAME.matches(it) }?.let { RecorderDevice(device.address, it) }
            }
            .sortedBy { it.name }
    }

    private suspend fun awaitBond(device: BluetoothDevice): Boolean {
        var seenBonding = false
        while (true) {
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> return true
                BluetoothDevice.BOND_BONDING -> seenBonding = true
                else -> if (seenBonding) return false
            }
            delay(BOND_POLL_MS)
        }
    }

    private fun remote(address: String): BluetoothDevice =
        adapter?.getRemoteDevice(address) ?: throw IOException("This phone has no Bluetooth")

    private class SocketLink(private val socket: BluetoothSocket) : RecorderLink {
        override val input: XrecInput = StreamXrecInput(socket.inputStream, threadName = "recorder-link")
        override val output: OutputStream = socket.outputStream

        override fun close() = closeQuietly(socket)
    }

    private companion object {
        /** The Serial Port Profile service class: the recorder's XREC server (RFCOMM channel 1). */
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val BOND_WAIT_MS = 60_000L
        const val BOND_POLL_MS = 500L

        fun closeQuietly(socket: BluetoothSocket) {
            try {
                socket.close()
            } catch (_: IOException) {
                // Already closed.
            }
        }
    }
}
