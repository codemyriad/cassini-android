package org.cassini.android.settings

import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.cassini.android.nav.LicensesKey
import org.cassini.android.nav.ModelDownloadKey
import org.cassini.android.nav.SavedVoicesKey
import org.cassini.android.nav.TrashKey
import org.cassini.android.notices.NoticeFacts
import org.cassini.core.Failure
import org.cassini.core.models.ModelPart
import org.cassini.core.models.ModelPlan
import org.cassini.data.models.ModelStatus
import org.cassini.data.models.ModelStatus.Missing
import org.cassini.ui.format.ModelPartUi
import org.cassini.ui.settings.LanguageChoice
import org.cassini.ui.settings.ModelStatusUi
import org.cassini.ui.settings.SettingsEvent
import org.cassini.ui.settings.SettingsUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Settings ViewModel over fake ports (features.md section 11). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val ports = FakeSettingsPorts()
    private val effects = mutableListOf<SettingsEffect>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): SettingsViewModel {
        val viewModel = SettingsViewModel(ports)
        backgroundScope.launch { viewModel.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect { effects += it } }
        advanceUntilIdle()
        return viewModel
    }

    private fun SettingsViewModel.now(): SettingsUiState = state.value

    @Test
    fun showsTheModelRowsTheCountsTheVersionAndTheNotificationsBanner() = runTest(dispatcher) {
        ports.models.value = mapOf(
            ModelPart.SPEECH to ModelStatus.Ready,
            ModelPart.SPEAKERS to ModelStatus.Downloading(percent = 46, bytes = 1, total = 2),
            ModelPart.VOICES to Missing(consented = true),
        )
        ports.voices.value = 4
        ports.trash.value = 2
        ports.notices.value = NoticeFacts(notificationsOff = true)
        val state = viewModel().now()
        assertEquals(1, ports.refreshed)
        assertEquals(listOf(ModelPartUi.SPEECH, ModelPartUi.SPEAKERS, ModelPartUi.VOICES), state.models.map { it.part })
        assertEquals(
            listOf(ModelStatusUi.Ready, ModelStatusUi.Downloading(46), ModelStatusUi.Pending),
            state.models.map { it.status },
        )
        // Each row shows its part's full download (640 MB, 62 MB, 28 MB for a fresh install).
        assertEquals(ModelPlan.of { false }.missing.map { it.downloadBytes }, state.models.map { it.bytes })
        assertEquals(4, state.voices)
        assertEquals(2, state.trash)
        assertEquals("9.9.9", state.version)
        assertTrue(state.notificationsOff)
    }

    @Test
    fun mapsEveryModelStatus() {
        assertEquals(ModelStatusUi.NotDownloaded, SettingsViewModel.status(Missing(consented = false)))
        assertEquals(ModelStatusUi.Pending, SettingsViewModel.status(Missing(consented = true)))
        assertEquals(ModelStatusUi.Checking, SettingsViewModel.status(ModelStatus.Verifying(80)))
        assertEquals(ModelStatusUi.Failed, SettingsViewModel.status(ModelStatus.Failed(Failure.VERIFY, null)))
        assertEquals(ModelStatusUi.Ready, SettingsViewModel.status(ModelStatus.Ready))
    }

    @Test
    fun onlyANotDownloadedOrAFailedRowOpensModelDownload() = runTest(dispatcher) {
        ports.models.value = mapOf(
            ModelPart.SPEECH to Missing(consented = false),
            ModelPart.SPEAKERS to ModelStatus.Failed(Failure.DOWNLOAD, "INTERRUPTED"),
            ModelPart.VOICES to ModelStatus.Ready,
        )
        val viewModel = viewModel()
        viewModel.onEvent(SettingsEvent.OpenModel(ModelPartUi.VOICES))
        viewModel.onEvent(SettingsEvent.OpenModel(ModelPartUi.SPEECH))
        // A download that Android stopped (or that failed) starts again from Model download.
        viewModel.onEvent(SettingsEvent.OpenModel(ModelPartUi.SPEAKERS))
        advanceUntilIdle()
        assertEquals(List<SettingsEffect>(2) { SettingsEffect.Open(ModelDownloadKey()) }, effects)
    }

    @Test
    fun theLanguageGoesToAppCompatAndPhoneIsTheEmptyList() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertEquals(LanguageChoice.PHONE, viewModel.now().language)
        viewModel.onEvent(SettingsEvent.Language(LanguageChoice.ITALIAN))
        advanceUntilIdle()
        assertEquals(LanguageChoice.ITALIAN, viewModel.now().language)
        viewModel.onEvent(SettingsEvent.Language(LanguageChoice.ITALIAN))
        viewModel.onEvent(SettingsEvent.Language(LanguageChoice.PHONE))
        advanceUntilIdle()
        assertEquals(listOf(LanguageChoice.ITALIAN, LanguageChoice.PHONE), ports.languages)

        assertTrue(GraphSettingsPorts.localesOf(LanguageChoice.PHONE).isEmpty)
        assertEquals("it", GraphSettingsPorts.localesOf(LanguageChoice.ITALIAN).toLanguageTags())
        assertEquals("en", GraphSettingsPorts.localesOf(LanguageChoice.ENGLISH).toLanguageTags())
        assertEquals(LanguageChoice.PHONE, GraphSettingsPorts.languageOf(LocaleListCompat.getEmptyLocaleList()))
        assertEquals(LanguageChoice.ITALIAN, GraphSettingsPorts.languageOf(LocaleListCompat.forLanguageTags("it-IT")))
        assertEquals(LanguageChoice.ENGLISH, GraphSettingsPorts.languageOf(LocaleListCompat.forLanguageTags("en-GB")))
    }

    // A language set in Android's per-app setting (locales_config) is Other, not Phone: the row selects no segment and
    // Phone clears it. One test per language that has no segment.
    @Test fun frenchFromTheSystemSettingsIsClearedByPhone() = runTest(dispatcher) { phoneClears("fr") }

    @Test fun spanishFromTheSystemSettingsIsClearedByPhone() = runTest(dispatcher) { phoneClears("es") }

    @Test fun brazilianPortugueseFromTheSystemSettingsIsClearedByPhone() = runTest(dispatcher) { phoneClears("pt-BR") }

    @Test fun europeanPortugueseFromTheSystemSettingsIsClearedByPhone() = runTest(dispatcher) { phoneClears("pt-PT") }

    private fun TestScope.phoneClears(tag: String) {
        assertEquals(tag, LanguageChoice.OTHER, GraphSettingsPorts.languageOf(LocaleListCompat.forLanguageTags(tag)))
        ports.current = LanguageChoice.OTHER
        val viewModel = viewModel()
        assertEquals(tag, LanguageChoice.OTHER, viewModel.now().language)
        viewModel.onEvent(SettingsEvent.Language(LanguageChoice.PHONE))
        advanceUntilIdle()
        assertEquals(tag, listOf(LanguageChoice.PHONE), ports.languages)
        assertEquals(tag, LanguageChoice.PHONE, viewModel.now().language)
        assertTrue(GraphSettingsPorts.localesOf(ports.languages.single()).isEmpty)
    }

    /** A language changed in the system settings while Settings was open shows when the screen resumes. */
    @Test
    fun resumingReadsTheLanguageAgain() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertEquals(LanguageChoice.PHONE, viewModel.now().language)
        ports.current = LanguageChoice.OTHER
        viewModel.onResume()
        advanceUntilIdle()
        assertEquals(LanguageChoice.OTHER, viewModel.now().language)
        assertEquals(emptyList<LanguageChoice>(), ports.languages)
    }

    @Test
    fun theRowsOpenTheirScreensAndTurnOnOpensTheNotificationSettings() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(SettingsEvent.OpenVoices)
        viewModel.onEvent(SettingsEvent.OpenTrash)
        viewModel.onEvent(SettingsEvent.OpenLicenses)
        viewModel.onEvent(SettingsEvent.TurnOnNotifications)
        viewModel.onEvent(SettingsEvent.Back)
        viewModel.onResume()
        advanceUntilIdle()
        assertEquals(
            listOf(
                SettingsEffect.Open(SavedVoicesKey),
                SettingsEffect.Open(TrashKey),
                SettingsEffect.Open(LicensesKey),
                SettingsEffect.OpenNotificationSettings,
                SettingsEffect.Close,
            ),
            effects,
        )
        assertEquals(1, ports.noticeRefreshes)
    }

    private class FakeSettingsPorts : SettingsPorts {
        val languages = mutableListOf<LanguageChoice>()
        var current = LanguageChoice.PHONE
        var refreshed = 0
        var noticeRefreshes = 0
        override val models = MutableStateFlow<Map<ModelPart, ModelStatus>>(emptyMap())
        val voices = MutableStateFlow(0)
        val trash = MutableStateFlow(0)
        override val notices = MutableStateFlow(NoticeFacts())
        override val version: String = "9.9.9"

        override fun language(): LanguageChoice = current

        override fun setLanguage(language: LanguageChoice) {
            languages += language
            current = language
        }

        override suspend fun refreshModels() {
            refreshed++
        }

        override fun voices(): Flow<Int> = voices

        override fun trash(): Flow<Int> = trash

        override fun refreshNotices() {
            noticeRefreshes++
        }
    }
}
