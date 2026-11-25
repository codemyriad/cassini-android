package io.codemyriad.cassini.recorder

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.MacAddress
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.data.recorder.RecorderDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class RecorderAndroidAdaptersTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var adapter: BluetoothAdapter

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
    }

    @Suppress("DEPRECATION")
    @Test fun companionResultFallsBackToBluetoothDeviceNameWhenAssociationDisplayNameIsNull() {
        val btDevice = adapter.getRemoteDevice("00:11:22:33:44:55")
        shadowOf(btDevice).setName("Cassini_55")

        // Non-self-managed BluetoothDeviceFilter associations on Android 13-16 have displayName == null.
        val association = ReflectionHelpers.newInstance(AssociationInfo::class.java)
        ReflectionHelpers.setField(association, "mDeviceMacAddress", MacAddress.fromString("00:11:22:33:44:55"))
        ReflectionHelpers.setField(association, "mDisplayName", null)

        val resultWithExtraDevice = Intent()
            .putExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, association)
            .putExtra(CompanionDeviceManager.EXTRA_DEVICE, btDevice)
        assertEquals(
            RecorderDevice("00:11:22:33:44:55", "Cassini_55"),
            RecorderCompanion.deviceOf(app, resultWithExtraDevice),
        )

        // Even when EXTRA_DEVICE is absent, resolving the MAC via the adapter preserves the Cassini_XX name.
        val resultWithAssociationOnly = Intent().putExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, association)
        assertEquals(
            RecorderDevice("00:11:22:33:44:55", "Cassini_55"),
            RecorderCompanion.deviceOf(app, resultWithAssociationOnly),
        )
    }

    @Test fun bondSucceedsWhenDeviceBecomesBondedBeforeCreateBondRuns() = runBlocking {
        val btDevice = adapter.getRemoteDevice("00:11:22:33:44:55")
        shadowOf(btDevice).setBondState(BluetoothDevice.BOND_NONE)
        // Real Android AdapterService.createBond() returns false when bondState != BOND_NONE.
        shadowOf(btDevice).setCreatedBond(false)

        val racingContext = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this

            override fun registerReceiver(
                receiver: BroadcastReceiver?,
                filter: IntentFilter?,
                flags: Int,
            ): Intent? {
                if (filter?.hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED) == true) {
                    shadowOf(btDevice).setBondState(BluetoothDevice.BOND_BONDED)
                }
                return super.registerReceiver(receiver, filter, flags)
            }

            override fun registerReceiver(
                receiver: BroadcastReceiver?,
                filter: IntentFilter?,
                broadcastPermission: String?,
                scheduler: android.os.Handler?,
                flags: Int,
            ): Intent? {
                if (filter?.hasAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED) == true) {
                    shadowOf(btDevice).setBondState(BluetoothDevice.BOND_BONDED)
                }
                return super.registerReceiver(receiver, filter, broadcastPermission, scheduler, flags)
            }
        }

        val radio = BluetoothRecorderRadio(racingContext, Dispatchers.IO)
        assertEquals(BondResult.BONDED, radio.bond("00:11:22:33:44:55"))
    }
}
