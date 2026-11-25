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
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.UnrecoverableKeyException
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

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

    @After fun tearDown() {
        Security.removeProvider("AndroidKeyStore")
    }

    @Test fun keystoreVaultRecoversWhenExistingKeyIsUnusable() {
        val provider = FakeAndroidKeyStoreProvider()
        Security.addProvider(provider)
        val vault = KeystoreKeyVault("test.recorder.binding")
        val firstKey = ByteArray(32) { (it + 1).toByte() }
        val wrappedFirst = vault.wrap(firstKey)
        assertArrayEquals(firstKey, vault.unwrap(wrappedFirst))

        // Simulate an invalidated / unrecoverable Keystore entry: unwrap fails so sync asks to re-bind, and wrap
        // during re-binding must replace the broken Keystore key instead of failing permanently.
        provider.corruptKey = true
        assertThrows(GeneralSecurityException::class.java) { vault.unwrap(wrappedFirst) }

        val reboundKey = ByteArray(32) { (0x80 - it).toByte() }
        val wrappedSecond = vault.wrap(reboundKey)
        assertArrayEquals(reboundKey, vault.unwrap(wrappedSecond))

        vault.clear()
        assertThrows(GeneralSecurityException::class.java) { vault.unwrap(wrappedSecond) }
    }

    private class FakeAndroidKeyStoreProvider : Provider("AndroidKeyStore", 1.0, "Fake AndroidKeyStore") {
        val storedKeys = LinkedHashMap<String, SecretKey>()

        @Volatile var corruptKey = false

        init {
            putService(
                object : Service(this, "KeyStore", "AndroidKeyStore", FakeKeyStoreSpi::class.java.name, null, null) {
                    override fun newInstance(constructorParameter: Any?): Any =
                        FakeKeyStoreSpi(this@FakeAndroidKeyStoreProvider)
                },
            )
            putService(
                object : Service(this, "KeyGenerator", "AES", FakeAesGeneratorSpi::class.java.name, null, null) {
                    override fun newInstance(constructorParameter: Any?): Any =
                        FakeAesGeneratorSpi(this@FakeAndroidKeyStoreProvider)
                },
            )
        }
    }

    private class FakeAesGeneratorSpi(private val state: FakeAndroidKeyStoreProvider) : KeyGeneratorSpi() {
        private var alias: String = "default"

        override fun engineInit(random: SecureRandom?) = Unit

        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            val spec = params as? android.security.keystore.KeyGenParameterSpec
            if (spec != null) alias = spec.keystoreAlias
        }

        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit

        override fun engineGenerateKey(): SecretKey {
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return SecretKeySpec(raw, "AES").also {
                state.corruptKey = false
                state.storedKeys[alias] = it
            }
        }
    }

    private class FakeKeyStoreSpi(private val state: FakeAndroidKeyStoreProvider) : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? {
            if (state.corruptKey && alias in state.storedKeys) throw UnrecoverableKeyException("Key invalidated")
            return state.storedKeys[alias]
        }

        override fun engineDeleteEntry(alias: String) {
            state.storedKeys.remove(alias)
            state.corruptKey = false
        }

        override fun engineContainsAlias(alias: String): Boolean = alias in state.storedKeys
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(state.storedKeys.keys)
        override fun engineSize(): Int = state.storedKeys.size
        override fun engineIsKeyEntry(alias: String): Boolean = alias in state.storedKeys
        override fun engineIsCertificateEntry(alias: String): Boolean = false
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>?) = Unit
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<Certificate>?) = Unit
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }
}
