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
    val recorder by graph.recorder.store.settings.collectAsStateWithLifecycle(initialValue = null)
    val recorderActivity by graph.recorder.sync.activity.collectAsStateWithLifecycle(
        initialValue = RecorderActivity.Idle,
    )
    val recorderNotes by graph.recorder.notes.collectAsStateWithLifecycle(initialValue = emptyMap())
    val transferState by transfers.state.collectAsStateWithLifecycle()
    val choices: List<BulkTransfers.Choice>? by transfers.available.collectAsStateWithLifecycle(initialValue = null)
    BulkTransferControls(transfers, transferState, choices) { enabled, onExport ->
        SettingsScreen(
            state,
            viewModel::onEvent,
            modifier,
            exportEnabled = enabled,
            onExport = onExport,
            extraRow = SettingsExtraRow(
                stringResource(R.string.recorder_title),
                RecorderTexts.settingsSubtitle(context, recorder, recorderActivity, recorderNotes),
            ),
            onExtraRow = { navigator.open(RecorderKey) },
        )
    }
}
