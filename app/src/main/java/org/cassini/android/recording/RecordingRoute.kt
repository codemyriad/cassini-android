package org.cassini.android.recording

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.cassini.ui.recording.RecordingScreen

/**
 * The Recording destination (architecture 6; features.md section 2): collects [viewModel]'s state for
 * [RecordingScreen], asks for RECORD_AUDIO (and POST_NOTIFICATIONS on API 33+) with `RequestMultiplePermissions`,
 * re-checks the microphone at ON_RESUME, and carries out the [RecordingEffect]s. System back is the navigation's pop,
 * which minimises: the recording continues.
 */
@Composable
@Suppress("FunctionNaming") // Composables are nouns in PascalCase.
fun RecordingRoute(viewModel: RecordingViewModel, navigator: RecordingNavigator, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val contract = ActivityResultContracts.RequestMultiplePermissions()
    val permissions = rememberLauncherForActivityResult(contract) { result ->
        val mic = result[RecordingViewModel.RECORD_AUDIO] ?: granted(context, RecordingViewModel.RECORD_AUDIO)
        viewModel.onPermissionResult(
            micGranted = mic,
            askedNotifications = RecordingViewModel.POST_NOTIFICATIONS in result,
        )
    }
    LaunchedEffect(viewModel) {
        viewModel.onEnter(
            micGranted = granted(context, RecordingViewModel.RECORD_AUDIO),
            notificationsGranted = granted(context, RecordingViewModel.POST_NOTIFICATIONS),
        )
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is RecordingEffect.RequestPermissions -> permissions.launch(effect.permissions.toTypedArray())
                    RecordingEffect.OpenAppSettings -> openAppSettings(context)
                    RecordingEffect.Close -> navigator.close()
                    is RecordingEffect.ReplaceWith -> navigator.replaceWith(effect.keys)
                }
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume(granted(context, RecordingViewModel.RECORD_AUDIO))
        onPauseOrDispose { }
    }
    RecordingScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    val uri = Uri.fromParts("package", context.packageName, null)
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
    try {
        (context.activity() ?: context).startActivity(intent)
    } catch (missing: ActivityNotFoundException) {
        Log.w("CassiniRecordingScreen", "No app settings screen", missing)
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
