package org.cassini.android.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import org.cassini.android.nav.SettingsKey
import org.cassini.android.nav.TrashKey
import org.cassini.ui.library.LibraryScreen

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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.onPicked(uri?.toString())
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
    LibraryScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
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
