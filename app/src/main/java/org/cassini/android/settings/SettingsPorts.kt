package org.cassini.android.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.cassini.android.AppGraph
import org.cassini.android.BuildConfig
import org.cassini.android.notices.NoticeFacts
import org.cassini.core.models.ModelPart
import org.cassini.data.models.ModelStatus
import org.cassini.ui.settings.LanguageChoice

/**
 * What the Settings ViewModel reads and changes (features.md section 11). [GraphSettingsPorts] binds it to
 * [AppGraph] and AppCompat's per-app language; the ViewModel tests pass a fake.
 */
interface SettingsPorts {
    /** The app language now: [LanguageChoice.PHONE] when the app follows the system. */
    fun language(): LanguageChoice

    /** Sets the app language (`AppCompatDelegate.setApplicationLocales`; Phone is the empty list). Main thread. */
    fun setLanguage(language: LanguageChoice)

    /** The status of each model part. */
    val models: Flow<Map<ModelPart, ModelStatus>>

    /** Reads the installed models and the consent again. */
    suspend fun refreshModels()

    /** The number of saved voices. */
    fun voices(): Flow<Int>

    /** The number of notes in Trash. */
    fun trash(): Flow<Int>

    val notices: Flow<NoticeFacts>

    /** Reads the notification permission again (ON_RESUME). */
    fun refreshNotices()

    /** The app's `versionName`. */
    val version: String
}

/** [SettingsPorts] over the app's graph. */
class GraphSettingsPorts(private val graph: AppGraph) : SettingsPorts {
    override fun language(): LanguageChoice = languageOf(AppCompatDelegate.getApplicationLocales())

    override fun setLanguage(language: LanguageChoice) = AppCompatDelegate.setApplicationLocales(localesOf(language))

    override val models: Flow<Map<ModelPart, ModelStatus>> get() = graph.models.status

    override suspend fun refreshModels() {
        graph.models.refresh()
    }

    override fun voices(): Flow<Int> = flow { emitAll(graph.awaitCatalogue().voices.people()) }.map { it.voices.size }

    override fun trash(): Flow<Int> = flow { emitAll(graph.awaitCatalogue().notes.counts()) }.map { it.trashed }

    override val notices: Flow<NoticeFacts> get() = graph.notices.facts

    override fun refreshNotices() = graph.notices.refreshNotifications()

    override val version: String get() = BuildConfig.VERSION_NAME

    companion object {
        /** The segment of the app locales: Phone for none, English or Italiano by the first locale's language. */
        fun languageOf(locales: LocaleListCompat): LanguageChoice = when (locales[0]?.language) {
            "en" -> LanguageChoice.ENGLISH
            "it" -> LanguageChoice.ITALIAN
            else -> LanguageChoice.PHONE
        }

        /** The app locales of a segment; Phone is the empty list (follow the system). */
        fun localesOf(language: LanguageChoice): LocaleListCompat = when (language) {
            LanguageChoice.PHONE -> LocaleListCompat.getEmptyLocaleList()
            LanguageChoice.ENGLISH -> LocaleListCompat.forLanguageTags("en")
            LanguageChoice.ITALIAN -> LocaleListCompat.forLanguageTags("it")
        }
    }
}
