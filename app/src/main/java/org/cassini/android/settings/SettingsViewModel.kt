package org.cassini.android.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
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
import org.cassini.android.nav.LicensesKey
import org.cassini.android.nav.ModelDownloadKey
import org.cassini.android.nav.SavedVoicesKey
import org.cassini.android.nav.TrashKey
import org.cassini.android.notices.Notices
import org.cassini.core.models.ModelPart
import org.cassini.data.models.ModelStatus
import org.cassini.ui.settings.LanguageChoice
import org.cassini.ui.settings.ModelRowUi
import org.cassini.ui.settings.ModelStatusUi
import org.cassini.ui.settings.SettingsEvent
import org.cassini.ui.settings.SettingsUiState

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
        ports.models.onStart { emit(emptyMap()) },
        ports.voices().guarded("voices"),
        ports.trash().guarded("trash"),
        ports.notices,
    ) { language, models, voices, trash, facts ->
        SettingsUiState(
            language = language,
            models = rows(models),
            voices = voices,
            trash = trash,
            version = ports.version,
            notificationsOff = facts.notificationsOff,
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
            SettingsEvent.Back -> emit(SettingsEffect.Close)
            is SettingsEvent.Language -> choose(event.language)
            is SettingsEvent.OpenModel -> openModel(event)
            SettingsEvent.OpenVoices -> emit(SettingsEffect.Open(SavedVoicesKey))
            SettingsEvent.OpenTrash -> emit(SettingsEffect.Open(TrashKey))
            SettingsEvent.OpenLicenses -> emit(SettingsEffect.Open(LicensesKey))
            SettingsEvent.TurnOnNotifications -> emit(SettingsEffect.OpenNotificationSettings)
        }
    }

    /** ON_RESUME: the notification permission may have changed in the system settings. */
    fun onResume() = ports.refreshNotices()

    private fun choose(next: LanguageChoice) {
        if (next == language.value) return
        language.value = next
        ports.setLanguage(next)
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
