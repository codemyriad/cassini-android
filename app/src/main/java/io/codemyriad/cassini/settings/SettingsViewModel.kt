package io.codemyriad.cassini.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.data.models.ModelStatus
import io.codemyriad.cassini.nav.LicensesKey
import io.codemyriad.cassini.nav.ModelDownloadKey
import io.codemyriad.cassini.nav.SavedVoicesKey
import io.codemyriad.cassini.nav.TrashKey
import io.codemyriad.cassini.notices.Notices
import io.codemyriad.cassini.ui.settings.LanguageChoice
import io.codemyriad.cassini.ui.settings.ModelRowUi
import io.codemyriad.cassini.ui.settings.ModelStatusUi
import io.codemyriad.cassini.ui.settings.SettingsEvent
import io.codemyriad.cassini.ui.settings.SettingsUiState
import io.codemyriad.cassini.ui.settings.SpeechModelUi
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Settings route does for the ViewModel. */
sealed interface SettingsEffect {
    data object Close : SettingsEffect

    data class Open(val key: NavKey) : SettingsEffect

    /** The app's notification settings ("Turn on"). */
    data object OpenNotificationSettings : SettingsEffect
}

/**
 * Settings (board 08, features.md section 11; ui.md section 7): the app language, the model rows (status from
 * `ModelRepository.status`, which holds the consent and the install progress), the counts of the Privacy rows, the
 * version and the notifications-off banner.
 *
 * A language change goes to AppCompat at once; the activity is created again in the new language, and this
 * ViewModel (kept by the back stack entry) keeps the chosen segment.
 */
class SettingsViewModel(private val ports: SettingsPorts) : ViewModel() {
    private val effectChannel = Channel<SettingsEffect>(Channel.BUFFERED)

    /** One-shot navigation requests; the route collects them. */
    val effects: Flow<SettingsEffect> = effectChannel.receiveAsFlow()

    private val language = MutableStateFlow(ports.language())

    val state: StateFlow<SettingsUiState> = combine(
        language,
        ports.models.onStart { emit(emptyMap()) }.combine(ports.speech) { models, speech -> models to speech },
        ports.voices().guarded("voices"),
        ports.trash().guarded("trash"),
        ports.notices,
    ) { language, modelState, voices, trash, facts ->
        val (models, speech) = modelState
        SettingsUiState(
            language = language,
            models = rows(models).map {
                if (speech.selected == SpeechModel.INCLUDED && it.status == ModelStatusUi.Pending) {
                    it.copy(status = ModelStatusUi.NotDownloaded)
                } else {
                    it
                }
            }.toImmutableList(),
            speechModel = if (speech.selected == SpeechModel.INCLUDED) SpeechModelUi.INCLUDED else SpeechModelUi.PARAKEET,
            includedAvailable = speech.includedAvailable,
            speechChecking = false,
            voices = voices,
            trash = trash,
            version = ports.version,
            notificationsOff = facts.notificationsOff,
            parakeetSuggestion = Notices.parakeetSuggestion(facts),
            parakeetNowDefault = facts.parakeetNowDefault,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initial())

    init {
        viewModelScope.launch {
            try {
                ports.refreshModels()
            } catch (error: CancellationException) {
                throw error
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Log.w(TAG, "Cannot read the models", error)
            }
        }
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            SettingsEvent.DownloadParakeet -> emit(SettingsEffect.Open(ModelDownloadKey(parakeetOnly = true)))
            SettingsEvent.DismissParakeet -> ports.dismissParakeetSuggestion()
            SettingsEvent.DismissParakeetDefault -> ports.dismissParakeetDefault()
            SettingsEvent.Back -> emit(SettingsEffect.Close)
            is SettingsEvent.Language -> choose(event.language)
            is SettingsEvent.ChooseSpeech -> chooseSpeech(event.model)
            is SettingsEvent.OpenModel -> openModel(event)
            SettingsEvent.OpenVoices -> emit(SettingsEffect.Open(SavedVoicesKey))
            SettingsEvent.OpenTrash -> emit(SettingsEffect.Open(TrashKey))
            SettingsEvent.OpenLicenses -> emit(SettingsEffect.Open(LicensesKey))
            SettingsEvent.TurnOnNotifications -> emit(SettingsEffect.OpenNotificationSettings)
        }
    }

    /**
     * ON_RESUME: the notification permission or the app language may have changed in the system settings. A language
     * change there creates the activity again but keeps this ViewModel.
     */
    fun onResume() {
        language.value = ports.language()
        ports.refreshNotices()
    }

    private fun choose(next: LanguageChoice) {
        // With Other (a language that is not an app language) every choice differs, so Phone clears it.
        if (next == language.value || next == LanguageChoice.OTHER) return
        language.value = next
        ports.setLanguage(next)
    }

    private fun chooseSpeech(model: SpeechModelUi) {
        if (model == SpeechModelUi.INCLUDED && !state.value.includedAvailable) return
        viewModelScope.launch {
            try {
                val status = state.value.models.firstOrNull { it.part == io.codemyriad.cassini.ui.format.ModelPartUi.SPEECH }?.status
                if (model == SpeechModelUi.PARAKEET && status != ModelStatusUi.Ready) {
                    emit(SettingsEffect.Open(ModelDownloadKey(parakeetOnly = true)))
                } else {
                    val selected = if (model == SpeechModelUi.INCLUDED) SpeechModel.INCLUDED else SpeechModel.PARAKEET
                    ports.chooseSpeech(selected)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Log.w(TAG, "Cannot save the model choice", error)
            }
        }
    }

    /**
     * A "Not downloaded" row opens Model download (the consent), with no note. A failed row opens it too: there,
     * Download starts the download again, for example after Android stopped it.
     */
    private fun openModel(event: SettingsEvent.OpenModel) {
        val status = state.value.models.firstOrNull { it.part == event.part }?.status
        if (status == ModelStatusUi.NotDownloaded || status == ModelStatusUi.Failed) {
            emit(SettingsEffect.Open(ModelDownloadKey()))
        }
    }

    private fun initial() = SettingsUiState(
        language = language.value,
        models = rows(emptyMap()),
        voices = 0,
        trash = 0,
        version = ports.version,
        speechChecking = true,
    )

    private fun emit(effect: SettingsEffect) {
        effectChannel.trySend(effect)
    }

    private fun Flow<Int>.guarded(what: String): Flow<Int> = onStart { emit(0) }.catch { error ->
        Log.w(TAG, "Cannot count the $what", error)
        emit(0)
    }

    companion object {
        private const val TAG = "CassiniSettings"
        private const val STOP_TIMEOUT_MS = 5_000L

        /** The On-device models rows in [ModelPart] order; a part whose status is not known yet is left out. */
        fun rows(models: Map<ModelPart, ModelStatus>) = ModelPart.entries.mapNotNull { part ->
            models[part]?.let { ModelRowUi(Notices.partUi(part), part.models.sumOf { it.downloadBytes }, status(it)) }
        }.toImmutableList()

        fun status(status: ModelStatus): ModelStatusUi = when (status) {
            ModelStatus.Ready -> ModelStatusUi.Ready
            is ModelStatus.Missing -> if (status.consented) ModelStatusUi.Pending else ModelStatusUi.NotDownloaded
            is ModelStatus.Downloading -> ModelStatusUi.Downloading(status.percent)
            is ModelStatus.Verifying -> ModelStatusUi.Checking
            is ModelStatus.Failed -> ModelStatusUi.Failed
        }
    }
}
