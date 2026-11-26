package io.codemyriad.cassini.recorder

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.recorder.FakeRecorder
import io.codemyriad.cassini.core.recorder.XrecAuth
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.models.ModelFetcher
import io.codemyriad.cassini.data.recorder.RecorderStore
import io.codemyriad.cassini.data.settings.ExperimentalChoices
import io.codemyriad.cassini.processing.AppVisibility
import io.codemyriad.cassini.testing.NoUncaughtCoroutines
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * D-1178: the recorder integration runs only while "Voice recorder" is on in Settings › Experimental features. A fresh
 * install never creates the recorder feature; an install with a bound recorder keeps it across the upgrade; turning
 * the switch off stops the feature and keeps `recorder/recorder.json`.
 */
@RunWith(RobolectricTestRunner::class)
class RecorderGateTest {
    @get:Rule(order = Int.MIN_VALUE)
    val uncaught = NoUncaughtCoroutines()

    @get:Rule val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val key = ByteArray(XrecAuth.KEY_BYTES) { 7 }
    private val radio = FakeRadio(FakeRecorder(key = key.copyOf()))
    private val created = AtomicInteger()
    private val featureCreated = CompletableDeferred<RecorderFeature>()
    private val visible = MutableStateFlow(false)
    private val paths by lazy { AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder()) }
    private val recorderFile by lazy { File(File(paths.root, "recorder"), "recorder.json") }
    private var graph: AppGraph? = null

    private fun graph(radioOverride: RecorderRadio = radio): AppGraph {
        val config = AppGraph.Config(
            paths = paths,
            dispatchers = CassiniDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Unconfined),
            fetcher = ModelFetcher { _, _, _ -> fail("No model is fetched in this test") },
            network = { false },
            processing = AppGraph.ProcessingHooks(
                visibility = object : AppVisibility {
                    override val visible = this@RecorderGateTest.visible
                },
            ),
            recorder = { ctx, graph ->
                created.incrementAndGet()
                RecorderFeature(ctx, graph, radio = radioOverride, vault = FakeVault()).also {
                    featureCreated.complete(it)
                }
            },
        )
        return AppGraph(context, config).also { graph = it }
    }

    @After fun tearDown() {
        graph?.close { shadowOf(Looper.getMainLooper()).idle() }
        radio.device.close()
    }

    @Test fun aFreshInstallNeverCreatesTheRecorder() = runBlocking {
        val graph = graph()
        visible.value = true
        assertFalse(graph.recorderEnabled.first())
        assertNull(graph.enabledRecorder())
        assertEquals(ExperimentalChoices(), graph.experimentalChoices.first())
        assertEquals(0, created.get())
        assertFalse(recorderFile.exists())
    }

    @Test fun aBoundRecorderStaysUnlockedAndEnabledAcrossTheUpgrade() = runBlocking {
        RecorderStore(recorderFile, Dispatchers.IO).bound(key)
        val graph = graph()
        assertEquals(
            ExperimentalChoices(unlocked = true, voiceRecorder = true),
            graph.experimentalChoices.first(),
        )
        val feature = assertNotNull(graph.enabledRecorder())
        assertTrue(feature.active)
        assertEquals(1, created.get())
    }

    @Test fun anUnboundRecorderFileDoesNotUnlock() = runBlocking {
        RecorderStore(recorderFile, Dispatchers.IO).update { it.copy(autoSync = true) }
        val graph = graph()
        assertEquals(ExperimentalChoices(), graph.experimentalChoices.first())
        assertNull(graph.enabledRecorder())
    }

    @Test fun turningVoiceRecorderOffStopsTheFeatureAndKeepsItsState() = runBlocking {
        val graph = graph()
        visible.value = true
        graph.settings.unlockExperimental()
        graph.settings.setVoiceRecorderEnabled(true)
        val feature = awaitActive(true)
        feature.store.bound(key)

        graph.settings.setExperimentalFeatures(false)
        awaitActive(false)
        assertNull(graph.enabledRecorder())
        assertTrue("recorder.json stays", recorderFile.isFile)
        assertNotNull(RecorderStore(recorderFile, Dispatchers.IO).read().binding)

        graph.settings.setExperimentalFeatures(true)
        graph.settings.setVoiceRecorderEnabled(true)
        assertSame(feature, awaitActive(true))
        assertEquals(1, created.get())
    }

    @Test fun pendingCompanionWakeupAfterShutdownStaysStopped() = runBlocking {
        val graph = graph()
        visible.value = true
        graph.settings.unlockExperimental()
        graph.settings.setVoiceRecorderEnabled(true)
        val feature = awaitActive(true)
        feature.store.bound(key)
        feature.sync.setAutoSync(true)

        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val wakeup = async(Dispatchers.IO) {
            wakeCompanionDevice(graph, DEVICE.address) {
                paused.complete(Unit)
                resume.await()
            }
        }

        withTimeout(TIMEOUT_MS) { paused.await() }
        graph.settings.setVoiceRecorderEnabled(false)
        awaitActive(false)
        feature.runner.awaitIdle()
        val opensAfterShutdown = radio.opens

        resume.complete(Unit)
        assertFalse(withTimeout(TIMEOUT_MS) { wakeup.await() })
        assertFalse(feature.active)
        assertEquals(opensAfterShutdown, radio.opens)
    }

    @Test fun pendingAclReceiverWakeupAfterShutdownStaysStopped() = runBlocking {
        val graph = graph()
        visible.value = true
        graph.settings.unlockExperimental()
        graph.settings.setVoiceRecorderEnabled(true)
        val feature = awaitActive(true)
        feature.store.bound(key)
        feature.sync.setAutoSync(true)

        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val wakeup = async(Dispatchers.IO) {
            RecorderBluetoothReceiver.handleAction(
                graph,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                DEVICE.address,
            ) {
                paused.complete(Unit)
                resume.await()
            }
        }

        withTimeout(TIMEOUT_MS) { paused.await() }
        graph.settings.setVoiceRecorderEnabled(false)
        awaitActive(false)
        feature.runner.awaitIdle()
        val opensAfterShutdown = radio.opens

        resume.complete(Unit)
        assertFalse(withTimeout(TIMEOUT_MS) { wakeup.await() })
        assertFalse(feature.active)
        assertEquals(opensAfterShutdown, radio.opens)
    }

    @Test fun staleOnDevicePresentDoesNotRestartStoppedFeature() = runBlocking {
        val graph = graph()
        visible.value = true
        graph.settings.unlockExperimental()
        graph.settings.setVoiceRecorderEnabled(true)
        val feature = assertNotNull(graph.enabledRecorder())
        awaitActive(true)
        feature.store.bound(key)
        feature.sync.setAutoSync(true)

        graph.settings.setVoiceRecorderEnabled(false)
        awaitActive(false)
        feature.runner.awaitIdle()
        val opensAfterShutdown = radio.opens

        assertFalse(feature.onDevicePresent(DEVICE.address, "companion_appeared"))
        assertFalse(feature.active)
        assertEquals(opensAfterShutdown, radio.opens)
    }

    @Test fun disablingVoiceRecorderPreservesAlreadyRunningTransfer() = runBlocking {
        val transferStarted = CompletableDeferred<Unit>()
        val allowFinish = CompletableDeferred<Unit>()
        val gatedRadio = object : RecorderRadio by radio {
            override suspend fun open(address: String): RecorderLink {
                transferStarted.complete(Unit)
                allowFinish.await()
                return radio.open(address)
            }
        }
        val graph = graph(radioOverride = gatedRadio)
        visible.value = true
        graph.settings.unlockExperimental()
        graph.settings.setVoiceRecorderEnabled(true)
        val feature = awaitActive(true)
        feature.store.bound(key)

        val transfer = feature.runner.syncNow()
        withTimeout(TIMEOUT_MS) { transferStarted.await() }

        graph.settings.setVoiceRecorderEnabled(false)
        awaitActive(false)
        assertFalse(feature.active)
        assertFalse("In-flight transfer must not be cancelled on disable", transfer.isCompleted)

        allowFinish.complete(Unit)
        val outcome = withTimeout(TIMEOUT_MS) { transfer.await() }
        assertTrue("Expected SyncOutcome.Done, got $outcome", outcome is SyncOutcome.Done)
        assertFalse(feature.active)
    }

    /** Waits until the graph started ([active]) or stopped the recorder, event-gated without polling. */
    private suspend fun awaitActive(active: Boolean): RecorderFeature = withTimeout(TIMEOUT_MS) {
        val feature = featureCreated.await()
        feature.activeFlow.first { it == active }
        feature
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
