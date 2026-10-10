package org.cassini.android.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

// The destinations of the app (docs/architecture.md section 6). Navigation 3 saves the back stack with
// kotlinx-serialization, so every key is @Serializable; proguard-rules.pro keeps them in the minified build.
// Sheets and dialogs are ViewModel state, not destinations; "Transcribing" is a mode of the Note screen.

/** The root. Back from it leaves the app. */
@Serializable
data object LibraryKey : NavKey

/** One note. [query] is the Library search that opened it; the in-note search starts with it. */
@Serializable
data class NoteKey(val noteId: String, val query: String? = null) : NavKey

/** The active recording. Back minimises it; the recording continues. */
@Serializable
data object RecordingKey : NavKey

/** The model download consent. With [noteId] it transcribes that note once the models are there. */
@Serializable
data class ModelDownloadKey(val noteId: String? = null) : NavKey

@Serializable
data object SettingsKey : NavKey

@Serializable
data object SavedVoicesKey : NavKey

@Serializable
data object TrashKey : NavKey

@Serializable
data object LicensesKey : NavKey
