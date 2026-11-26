package io.codemyriad.cassini.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.codemyriad.cassini.CassiniApplication
import io.codemyriad.cassini.nav.RecorderKey
import io.codemyriad.cassini.nav.SettingsKey
import io.codemyriad.cassini.nav.TrashKey
import io.codemyriad.cassini.recorder.RecorderActivity
import io.codemyriad.cassini.recorder.RecorderFeature
import io.codemyriad.cassini.recorder.RecorderTexts
import io.codemyriad.cassini.ui.library.LibraryScreen
import io.codemyriad.cassini.ui.library.RecorderBannerUi

/**
 * The Library destination (architecture 6): collects [viewModel]'s state for [LibraryScreen] and carries out its
 * [LibraryEffect]s: navigation through [navigator], the audio picker (`ACTION_OPEN_DOCUMENT`) and the notification
 * settings.
 */
@Composable
@Suppress("FunctionNaming") // Composables are nouns in PascalCase.
fun LibraryRoute(viewModel: LibraryViewModel, navigator: LibraryNavigator, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(io.codemyriad.cassini.transfer.BulkImportContract()) { uris ->
        viewModel.onPicked(uris.map { it.toString() })
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is LibraryEffect.OpenNote -> navigator.open(effect.key)
                    LibraryEffect.OpenRecording -> navigator.openRecording()
                    LibraryEffect.OpenTrash -> navigator.open(TrashKey)
                    LibraryEffect.OpenSettings -> navigator.open(SettingsKey)
                    LibraryEffect.OpenRecorder -> navigator.open(RecorderKey)
                    is LibraryEffect.OpenModelDownload -> navigator.open(effect.key)
                    LibraryEffect.PickAudio -> launch(context) { picker.launch(LibraryViewModel.IMPORT_TYPES) }
                    LibraryEffect.OpenNotificationSettings -> launch(context) {
                        context.startActivity(notificationSettings(context))
                    }
                }
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    // D-1178: with "Voice recorder" off the recorder feature is never created here and there is no banner.
    val graph = (context.applicationContext as? CassiniApplication)?.graph
    val recorderEnabled = graph?.recorderEnabled?.collectAsStateWithLifecycle(initialValue = false)?.value == true
    val recorderBanner = if (graph != null && recorderEnabled) recorderBanner(graph.recorder) else null
    LibraryScreen(
        state = if (recorderBanner != null && state.query.isBlank()) state.copy(recorder = recorderBanner) else state,
        onEvent = viewModel::onEvent,
        modifier = modifier,
    )
}

/** The Library's recorder banner over [feature], or null when no recorder is bound. */
@Composable
private fun recorderBanner(feature: RecorderFeature): RecorderBannerUi? {
    val context = LocalContext.current
    val settings by feature.store.settings.collectAsStateWithLifecycle(initialValue = null)
    val activity by feature.sync.activity.collectAsStateWithLifecycle(initialValue = RecorderActivity.Idle)
    val notes by feature.notes.collectAsStateWithLifecycle(initialValue = emptyMap())
    return RecorderTexts.libraryBanner(context, settings, activity, notes)
}

/** The app's notification settings (API 26+). */
fun notificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

/** Starts a system screen; a device without it only logs. */
private inline fun launch(context: Context, start: () -> Unit) {
    try {
        start()
    } catch (missing: ActivityNotFoundException) {
        Log.w("CassiniLibrary", "No activity for a system screen in ${context.packageName}", missing)
    }
}
