package org.cassini.android.licenses

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.cassini.core.models.ModelCatalog
import org.cassini.ui.licenses.LicenseEntry
import org.cassini.ui.licenses.LicenseText
import org.cassini.ui.licenses.LicensesBackTo
import org.cassini.ui.licenses.LicensesEvent
import org.cassini.ui.licenses.SOURCE_CODE_URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The Licenses ViewModel over fake ports, and [LicenseTexts] over the repository's asset files. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LicensesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val effects = mutableListOf<LicensesEffect>()
    private val reads = mutableListOf<LicenseEntry>()
    private val ports = LicensesPorts { entry ->
        reads += entry
        "text of $entry"
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(saved: SavedStateHandle = SavedStateHandle()): LicensesViewModel {
        val viewModel = LicensesViewModel(saved, ports, LicensesBackTo.MODEL_DOWNLOAD)
        backgroundScope.launch { viewModel.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect { effects += it } }
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun listsEveryRowAndOpensATextPane() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertEquals(LicenseEntry.entries, viewModel.state.value.entries)
        assertEquals(LicensesBackTo.MODEL_DOWNLOAD, viewModel.state.value.backTo)
        viewModel.onEvent(LicensesEvent.Open(LicenseEntry.PRIVACY))
        advanceUntilIdle()
        assertEquals(LicenseText(LicenseEntry.PRIVACY, "text of PRIVACY"), viewModel.state.value.open)
        viewModel.onEvent(LicensesEvent.CloseText)
        advanceUntilIdle()
        assertNull(viewModel.state.value.open)
        viewModel.onEvent(LicensesEvent.Back)
        advanceUntilIdle()
        assertEquals(listOf<LicensesEffect>(LicensesEffect.Close), effects)
    }

    @Test
    fun sourceCodeOpensTheBrowserAndNoPane() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(LicensesEvent.Open(LicenseEntry.SOURCE_CODE))
        advanceUntilIdle()
        assertNull(viewModel.state.value.open)
        assertTrue(reads.isEmpty())
        assertEquals(listOf<LicensesEffect>(LicensesEffect.OpenUrl(SOURCE_CODE_URL)), effects)
    }

    @Test
    fun theOpenPaneComesBackAfterProcessDeath() = runTest(dispatcher) {
        val restored = viewModel(SavedStateHandle(mapOf("open" to LicenseEntry.NATIVE.name)))
        assertEquals(LicenseText(LicenseEntry.NATIVE, "text of NATIVE"), restored.state.value.open)
    }

    @Test
    fun everyRowHasItsTextFromTheAssets() {
        val texts = LicenseTexts(read = { File(asset(it)).readText() }, list = ::list)
        val gpl = texts.text(LicenseEntry.CASSINI)
        assertTrue("GNU GENERAL PUBLIC LICENSE" in gpl)
        assertTrue("THIRD_PARTY notices", texts.text(LicenseEntry.THIRD_PARTY).startsWith("# Licenses and sources"))
        assertTrue("protobuf licence", "protobuf/LICENSE" in texts.text(LicenseEntry.THIRD_PARTY))
        val native = texts.text(LicenseEntry.NATIVE)
        for (name in listOf("sherpa-onnx", "onnxruntime/ThirdPartyNotices.txt", "eigen/COPYING.MPL2", "opus-COPYING")) {
            assertTrue("native: $name", name in native)
        }
        assertFalse("the diarizer licence is a model notice", "OpenMDW" in native)
        assertTrue("Instrument Sans", "SIL OPEN FONT LICENSE" in texts.text(LicenseEntry.INSTRUMENT_SANS).uppercase())
        assertTrue("Plex Mono", "SIL OPEN FONT LICENSE" in texts.text(LicenseEntry.PLEX_MONO).uppercase())
        val models = texts.text(LicenseEntry.MODELS)
        assertTrue(models.startsWith("Downloaded models and local test fixtures\n"))
        assertTrue("nemotron-3-diarization/OpenMDW-1.1.txt" in models)
        val sources = texts.text(LicenseEntry.MODEL_SOURCES)
        assertTrue(sources.startsWith("Network\n"))
        for (host in ModelCatalog.DOWNLOAD_HOSTS) assertTrue(host, host in sources)
        assertTrue(ModelCatalog.PARAKEET.artifacts.first().url in sources)
        assertEquals(File(root, "PRIVACY.md").readText(), texts.text(LicenseEntry.PRIVACY))
    }

    private val root = generateSequence(File("").absoluteFile, File::getParentFile)
        .first { dir -> File(dir, "settings.gradle.kts").exists() }

    /** An asset path of the APK: the app's own, or the font licences that `:ui` adds. */
    private fun asset(path: String): String {
        val app = File(root, "app/src/main/assets/$path")
        return if (app.exists()) app.path else File(root, "ui/src/main/assets/$path").path
    }

    private fun list(path: String): List<String> {
        val names = listOf("app", "ui").flatMap { module ->
            File(root, "$module/src/main/assets/$path").takeIf { it.isDirectory }?.list()?.toList().orEmpty()
        }
        return names.distinct()
    }
}
