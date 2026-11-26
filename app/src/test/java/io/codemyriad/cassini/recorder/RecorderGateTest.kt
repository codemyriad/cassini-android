package io.codemyriad.cassini.recorder

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private val visible = MutableStateFlow(false)
    private val paths by lazy { AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder()) }
    private val recorderFile by lazy { File(File(paths.root, "recorder"), "recorder.json") }
    private var graph: AppGraph? = null

    private fun graph(): AppGraph {
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
                RecorderFeature(ctx, graph, radio = radio, vault = FakeVault())
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
        val feature = awaitActive(graph, true)
        feature.store.bound(key)

        graph.settings.setExperimentalFeatures(false)
        awaitActive(graph, false)
        assertNull(graph.enabledRecorder())
        assertTrue("recorder.json stays", recorderFile.isFile)
        assertNotNull(RecorderStore(recorderFile, Dispatchers.IO).read().binding)

        graph.settings.setExperimentalFeatures(true)
        graph.settings.setVoiceRecorderEnabled(true)
        assertSame(feature, awaitActive(graph, true))
        assertEquals(1, created.get())
    }

    /** Waits until the graph started ([active]) or stopped the recorder; the switch is followed asynchronously. */
    private suspend fun awaitActive(graph: AppGraph, active: Boolean): RecorderFeature = withTimeout(TIMEOUT_MS) {
        while (created.get() == 0 || graph.recorder.active != active) {
            delay(POLL_MS)
        }
        graph.recorder
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
