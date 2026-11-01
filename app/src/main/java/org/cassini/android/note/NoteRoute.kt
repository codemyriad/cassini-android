package org.cassini.android.note

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.cassini.android.library.notificationSettings
import org.cassini.ui.note.NoteScreen

/**
 * The Note destination (architecture 6): collects [viewModel]'s state for [NoteScreen] and carries out its
 * [NoteEffect]s: navigation through [navigator], the "Save a copy" picker (`ACTION_CREATE_DOCUMENT` with the
 * export's MIME type and name), POST_NOTIFICATIONS and the notification settings. The player opens while the
 * screen is in the composition and is left (position and Library preview saved) when it goes.
 */
@Composable
@Suppress("FunctionNaming") // Composables are nouns in PascalCase.
fun NoteRoute(
    viewModel: NoteViewModel,
    navigator: NoteNavigator,
    modifier: Modifier = Modifier,
    transitionRunning: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val createFile = rememberLauncherForActivityResult(CreateFile()) { uri ->
        viewModel.onExportTarget(uri?.toString())
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    NoteEffect.Close -> navigator.close()
                    NoteEffect.CloseToLibrary -> navigator.closeToLibrary()
                    is NoteEffect.OpenModelDownload -> navigator.open(effect.key)
                    is NoteEffect.CreateFile -> start(context) { createFile.launch(effect.mime to effect.name) }
                    is NoteEffect.Share -> start(context) { context.startActivity(shareChooser(context, effect)) }
                    NoteEffect.RequestNotifications -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    NoteEffect.OpenNotificationSettings -> start(context) {
                        context.startActivity(notificationSettings(context))
                    }
                }
            }
        }
    }
    val activity = LocalActivity.current
    DisposableEffect(viewModel) {
        viewModel.onVisible(true)
        // A configuration change (rotation, dark theme, font scale) recreates the activity: the screen stays, and so
        // does the player, which keeps playing.
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.onVisible(false) }
    }
    val turnsShown = state.turns.isNotEmpty()
    LaunchedEffect(viewModel, turnsShown, transitionRunning) {
        if (turnsShown && !transitionRunning) {
            // The next frame starts when the frame that draws them has ended.
            withFrameNanos { }
            viewModel.onTurnsShown()
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    NoteScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

/** `ACTION_CREATE_DOCUMENT` with a MIME type and a suggested name chosen per export. */
private class CreateFile : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.first)
            .putExtra(Intent.EXTRA_TITLE, input.second)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.takeIf { resultCode == Activity.RESULT_OK }?.data
}

/**
 * `ACTION_SEND` of a share file through the system chooser: the FileProvider Uri as the stream and as ClipData (so the
 * read grant reaches the chosen app and the chooser shows a preview), the MIME type, and the note title as subject.
 */
private fun shareChooser(context: Context, share: NoteEffect.Share): Intent {
    val uri = share.uri.toUri()
    val send = Intent(Intent.ACTION_SEND)
        .setType(share.mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, share.title)
        .putExtra(Intent.EXTRA_TITLE, share.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newUri(context.contentResolver, share.name, uri)
    return Intent.createChooser(send, null)
}

/** Starts a system screen; a device without it only logs. */
private inline fun start(context: Context, block: () -> Unit) {
    try {
        block()
    } catch (missing: ActivityNotFoundException) {
        Log.w("CassiniNote", "No activity for a system screen in ${context.packageName}", missing)
    }
}
