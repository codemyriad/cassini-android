package io.codemyriad.cassini.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.data.AppPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Why the queue does not start its next job (architecture 5.2 rule 10). Stored by name. */
enum class QueueHold {
    /** "Stop" in the notification; cleared by Continue or any explicit Transcribe or Resume. */
    USER,

    /** `onTimeout` of the foreground service; cleared only by Continue or Resume from a visible screen. */
    SYSTEM,
}

/** Every setting at once, for one-shot reads. */
data class Settings(
    val consentAt: Long?,
    val declinedAt: Long?,
    val finalizationMs: Long?,
    val queueHold: QueueHold?,
    val lastRecordingFormat: String?,
    val recoveredAck: Long,
    val opusNativeAck: Boolean,
    val notificationsAsked: Boolean,
    val trashLastPurgeAt: Long?,
)

/**
 * The small settings in `settings.preferences_pb` (docs/architecture.md, section 3.4), with one typed accessor per
 * key. The app creates one instance per process with [create]: DataStore allows one instance per file.
 */
// One flow and one setter per key.
@Suppress("TooManyFunctions")
class SettingsRepository(private val store: DataStore<Preferences>) {
    private val preferences: Flow<Preferences> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    /** Every setting at once. */
    val settings: Flow<Settings> = preferences.map(::settings).distinctUntilChanged()

    suspend fun snapshot(): Settings = settings.first()

    /** Explicit optional-model download consent; a Parakeet-only plan does not set this. */
    val consentAt: Flow<Long?> = key(Keys.CONSENT_AT)

    /** Consent for the single Tell speakers apart download, without speech or saved voices. */
    val speakerConsentAt: Flow<Long?> = key(Keys.SPEAKER_CONSENT_AT)
    val voiceConsentAt: Flow<Long?> = key(Keys.VOICE_CONSENT_AT)

    suspend fun recordConsent(
        at: Long,
        includeSpeech: Boolean = true,
        includeOptional: Boolean = true,
        includeSpeakers: Boolean = false,
        includeVoices: Boolean = false,
    ) {
        store.edit {
            if (includeOptional) it[Keys.CONSENT_AT] = at
            if (includeSpeakers) it[Keys.SPEAKER_CONSENT_AT] = at
            if (includeVoices) it[Keys.VOICE_CONSENT_AT] = at
            if (includeSpeech) it[Keys.SPEECH_CONSENT_AT] = at
        }
    }

    val speechConsentAt: Flow<Long?> = key(Keys.SPEECH_CONSENT_AT)

    /** When the user pressed "Not now — keep the audio"; it suppresses the automatic consent screen. */
    val declinedAt: Flow<Long?> = key(Keys.DECLINED_AT)

    suspend fun recordDeclined(at: Long) = set(Keys.DECLINED_AT, at)

    /** The running estimate of matching plus saving time. */
    val finalizationMs: Flow<Long?> = key(Keys.FINALIZATION_MS)

    /** Folds [measuredMs] into the estimate as `(old * 3 + new) / 4` (the first measure is taken as it is). */
    suspend fun recordFinalization(measuredMs: Long): Long {
        var estimate = measuredMs
        store.edit { prefs ->
            estimate = prefs[Keys.FINALIZATION_MS]?.let { (it * OLD_WEIGHT + measuredMs) / WEIGHTS } ?: measuredMs
            prefs[Keys.FINALIZATION_MS] = estimate
        }
        return estimate
    }

    /** The queue hold, or null when the queue may start its next job. An unknown stored value reads as no hold. */
    val queueHold: Flow<QueueHold?> = preferences.map { hold(it) }.distinctUntilChanged()

    suspend fun setQueueHold(hold: QueueHold?) {
        store.edit { prefs -> if (hold == null) prefs.remove(Keys.QUEUE_HOLD) else prefs[Keys.QUEUE_HOLD] = hold.name }
    }

    /** The last capture format (`opus` or `m4a`), for the format chip before the encoder probe returns. */
    val lastRecordingFormat: Flow<String?> = key(Keys.LAST_FORMAT)

    suspend fun setLastRecordingFormat(format: String) = set(Keys.LAST_FORMAT, format)

    /** The latest recovered-recording time the user has seen or dismissed; 0 when none. */
    val recoveredAck: Flow<Long> = preferences.map { it[Keys.RECOVERED_ACK] ?: 0L }.distinctUntilChanged()

    /** Records that the user saw recovered recordings up to [at]; the stored time never goes back. */
    suspend fun acknowledgeRecovered(at: Long) {
        store.edit { prefs -> prefs[Keys.RECOVERED_ACK] = maxOf(prefs[Keys.RECOVERED_ACK] ?: 0L, at) }
    }

    /** "This phone can't create Cassini files" was dismissed. */
    val opusNativeAck: Flow<Boolean> = flag(Keys.OPUS_NATIVE_ACK)

    suspend fun acknowledgeOpusNative() = set(Keys.OPUS_NATIVE_ACK, true)

    /** POST_NOTIFICATIONS was requested once. */
    val notificationsAsked: Flow<Boolean> = flag(Keys.NOTIFICATIONS_ASKED)

    suspend fun markNotificationsAsked() = set(Keys.NOTIFICATIONS_ASKED, true)

    /** The last trash purge (diagnostics only). */
    val trashLastPurgeAt: Flow<Long?> = key(Keys.LAST_PURGE_AT)

    suspend fun recordPurge(at: Long) = set(Keys.LAST_PURGE_AT, at)

    /** The Note screen's playback speed, for every note (coordination D53); null until one is chosen. */
    val playbackSpeed: Flow<Float?> = key(Keys.PLAYBACK_SPEED)

    suspend fun setPlaybackSpeed(speed: Float) = set(Keys.PLAYBACK_SPEED, speed)

    /** New installs resolve the absent choice from verified Included availability (D5/D75). */
    val speechModel: Flow<SpeechModel?> = preferences.map {
        SpeechModel.setting(it[Keys.SPEECH_MODEL])
    }.distinctUntilChanged()

    suspend fun setSpeechModel(model: SpeechModel) {
        store.edit {
            it[Keys.SPEECH_MODEL] = model.setting
            if (model == SpeechModel.INCLUDED) it[Keys.PARAKEET_DEFAULT_NOTICE] = false
        }
    }

    /** Promote once per verified installation; a later explicit Whistle choice is kept across restarts. */
    suspend fun resolveSpeechModel(parakeetReady: Boolean, includedAvailable: Boolean = true): SpeechModel? {
        var selected: SpeechModel? = null
        store.edit { prefs ->
            val activated = prefs[Keys.PARAKEET_ACTIVATED] ?: false
            if (parakeetReady && !activated) {
                prefs[Keys.SPEECH_MODEL] = SpeechModel.PARAKEET.setting
                prefs[Keys.PARAKEET_DEFAULT_NOTICE] = includedAvailable
            } else if (!parakeetReady && activated && SpeechModel.setting(prefs[Keys.SPEECH_MODEL]) == SpeechModel.PARAKEET) {
                // Removal or invalidation returns new work to the starter, without changing a queued job's pin.
                prefs.remove(Keys.SPEECH_MODEL)
                prefs[Keys.PARAKEET_DEFAULT_NOTICE] = false
            }
            prefs[Keys.PARAKEET_ACTIVATED] = parakeetReady
            if (!includedAvailable) prefs[Keys.PARAKEET_DEFAULT_NOTICE] = false
            selected = SpeechModel.setting(prefs[Keys.SPEECH_MODEL])
        }
        return selected
    }

    val firstWhistleNote: Flow<String?> = key(Keys.FIRST_WHISTLE_NOTE)

    /** Only a completed transcription counts; later completions cannot repeat the first-use suggestion. */
    suspend fun recordWhistleCompleted(noteId: String) {
        store.edit { if (it[Keys.FIRST_WHISTLE_NOTE] == null) it[Keys.FIRST_WHISTLE_NOTE] = noteId }
    }

    val parakeetSuggestionDismissed: Flow<Boolean> = flag(Keys.PARAKEET_SUGGESTION_DISMISSED)

    suspend fun dismissParakeetSuggestion() = set(Keys.PARAKEET_SUGGESTION_DISMISSED, true)

    val parakeetDefaultNotice: Flow<Boolean> = flag(Keys.PARAKEET_DEFAULT_NOTICE)

    suspend fun dismissParakeetDefaultNotice() = set(Keys.PARAKEET_DEFAULT_NOTICE, false)

    private fun <T> key(key: Preferences.Key<T>): Flow<T?> = preferences.map { it[key] }.distinctUntilChanged()

    private fun flag(key: Preferences.Key<Boolean>): Flow<Boolean> =
        preferences.map { it[key] ?: false }.distinctUntilChanged()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    private fun hold(prefs: Preferences): QueueHold? =
        prefs[Keys.QUEUE_HOLD]?.let { name -> QueueHold.entries.firstOrNull { it.name == name } }

    private fun settings(prefs: Preferences) = Settings(
        consentAt = prefs[Keys.CONSENT_AT],
        declinedAt = prefs[Keys.DECLINED_AT],
        finalizationMs = prefs[Keys.FINALIZATION_MS],
        queueHold = hold(prefs),
        lastRecordingFormat = prefs[Keys.LAST_FORMAT],
        recoveredAck = prefs[Keys.RECOVERED_ACK] ?: 0L,
        opusNativeAck = prefs[Keys.OPUS_NATIVE_ACK] ?: false,
        notificationsAsked = prefs[Keys.NOTIFICATIONS_ASKED] ?: false,
        trashLastPurgeAt = prefs[Keys.LAST_PURGE_AT],
    )

    /** The stored key names of architecture 3.4. They are persisted: never rename one. */
    object Keys {
        val PARAKEET_DEFAULT_NOTICE = booleanPreferencesKey("notices.parakeetDefault")
        val FIRST_WHISTLE_NOTE = stringPreferencesKey("recognition.firstWhistleNote")
        val PARAKEET_SUGGESTION_DISMISSED = booleanPreferencesKey("notices.parakeetSuggestionDismissed")
        val PARAKEET_ACTIVATED = booleanPreferencesKey("recognition.parakeetActivated")
        val SPEECH_MODEL = stringPreferencesKey("recognition.model")
        val SPEECH_CONSENT_AT = longPreferencesKey("models.speechConsentAt")
        val CONSENT_AT = longPreferencesKey("models.consentAt")
        val SPEAKER_CONSENT_AT = longPreferencesKey("models.speakerConsentAt")
        val VOICE_CONSENT_AT = longPreferencesKey("models.voiceConsentAt")
        val DECLINED_AT = longPreferencesKey("models.declinedAt")
        val FINALIZATION_MS = longPreferencesKey("processing.finalizationMs")
        val QUEUE_HOLD = stringPreferencesKey("queue.hold")
        val LAST_FORMAT = stringPreferencesKey("recording.lastFormat")
        val RECOVERED_ACK = longPreferencesKey("notices.recoveredAck")
        val OPUS_NATIVE_ACK = booleanPreferencesKey("notices.opusNativeAck")
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("notifications.asked")
        val LAST_PURGE_AT = longPreferencesKey("trash.lastPurgeAt")
        val PLAYBACK_SPEED = floatPreferencesKey("playback.speed")
    }

    companion object {
        private const val OLD_WEIGHT = 3
        private const val WEIGHTS = 4

        /**
         * The settings store at [AppPaths.settings], run in [scope] (the application scope on IO). A file that does
         * not parse is replaced with empty settings.
         */
        fun create(paths: AppPaths, scope: CoroutineScope): SettingsRepository = SettingsRepository(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { paths.settings },
            ),
        )
    }
}
