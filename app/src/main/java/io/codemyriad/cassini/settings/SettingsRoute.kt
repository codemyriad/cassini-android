package io.codemyriad.cassini.settings

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.codemyriad.cassini.CassiniApplication
import io.codemyriad.cassini.R
import io.codemyriad.cassini.library.notificationSettings
import io.codemyriad.cassini.nav.RecorderKey
import io.codemyriad.cassini.nav.ScreenNavigator
import io.codemyriad.cassini.nav.startSystemScreen
import io.codemyriad.cassini.recorder.RecorderActivity
import io.codemyriad.cassini.recorder.RecorderFeature
import io.codemyriad.cassini.recorder.RecorderTexts
import io.codemyriad.cassini.transfer.BulkTransferControls
import io.codemyriad.cassini.transfer.BulkTransfers
import io.codemyriad.cassini.ui.settings.SettingsExtraRow
import io.codemyriad.cassini.ui.settings.SettingsScreen

/** The Settings destination (architecture 6): [SettingsScreen] over [viewModel], and its [SettingsEffect]s. */
@Composable
@Suppress("FunctionNaming") // Composables are nouns in PascalCase.
fun SettingsRoute(viewModel: SettingsViewModel, navigator: ScreenNavigator, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is SettingsEffect.OpenUrl ->
                        context.startSystemScreen(Intent(Intent.ACTION_VIEW, effect.url.toUri()))
                    SettingsEffect.Close -> navigator.close()
                    is SettingsEffect.Open -> navigator.open(effect.key)
                    SettingsEffect.OpenNotificationSettings ->
                        context.startSystemScreen(notificationSettings(context))
                }
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    val graph = (context.applicationContext as CassiniApplication).graph
    val transfers = graph.transfers
    // D-1178: the recorder feature is read (and so created) only while "Voice recorder" is on.
    val recorderEnabled by graph.recorderEnabled.collectAsStateWithLifecycle(initialValue = false)
    val recorderRow = if (recorderEnabled) recorderRow(graph.recorder) else null
    val transferState by transfers.state.collectAsStateWithLifecycle()
    val choices: List<BulkTransfers.Choice>? by transfers.available.collectAsStateWithLifecycle(initialValue = null)
    BulkTransferControls(transfers, transferState, choices) { enabled, onExport ->
        SettingsScreen(
            state,
            viewModel::onEvent,
            modifier,
            exportEnabled = enabled,
            onExport = onExport,
            extraRow = recorderRow,
            onExtraRow = { navigator.open(RecorderKey) },
        )
    }
}

/** The Recorder row of Settings › Experimental features, with what the recorder does now. */
@Composable
private fun recorderRow(feature: RecorderFeature): SettingsExtraRow {
    val context = LocalContext.current
    val recorder by feature.store.settings.collectAsStateWithLifecycle(initialValue = null)
    val activity by feature.sync.activity.collectAsStateWithLifecycle(initialValue = RecorderActivity.Idle)
    val notes by feature.notes.collectAsStateWithLifecycle(initialValue = emptyMap())
    return SettingsExtraRow(
        stringResource(R.string.recorder_title),
        RecorderTexts.settingsSubtitle(context, recorder, activity, notes),
    )
}
