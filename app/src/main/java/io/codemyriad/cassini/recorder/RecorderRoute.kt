@file:Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions") // Composables in PascalCase; dp layout values.

package io.codemyriad.cassini.recorder

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.codemyriad.cassini.R
import io.codemyriad.cassini.nav.NoteKey
import io.codemyriad.cassini.nav.ScreenNavigator
import io.codemyriad.cassini.nav.startSystemScreen
import io.codemyriad.cassini.ui.theme.CassiniTheme

/**
 * The Recorder destination: a plain screen over [RecorderViewModel] (docs/recorder-sync.md). It asks for the Nearby
 * devices permission, launches the companion device chooser, and opens a note on a tap.
 */
@Composable
fun RecorderRoute(viewModel: RecorderViewModel, navigator: ScreenNavigator, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is RecorderEffect.OpenNote -> navigator.open(NoteKey(effect.noteId))
                }
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(RecorderEvent.Refresh)
        onPauseOrDispose { }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onEvent(RecorderEvent.Refresh)
    }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        chosen(context, result)?.let { viewModel.onEvent(RecorderEvent.DevicePicked(it)) }
            ?: viewModel.onEvent(RecorderEvent.CancelBind)
    }
    val actions = RecorderActions(
        onEvent = viewModel::onEvent,
        back = navigator::close,
        requestPermission = { permissions.launch(RecorderPermissions.required()) },
        openSystem = { intent -> context.startSystemScreen(intent) },
        find = {
            if (state.companionSupported) {
                RecorderCompanion.associate(
                    context,
                    onChooser = { sender -> chooser.launch(IntentSenderRequest.Builder(sender).build()) },
                    onFailure = { reason -> viewModel.onEvent(RecorderEvent.ChooserFailed(reason?.toString())) },
                )
            } else {
                viewModel.onEvent(RecorderEvent.ListBonded)
            }
        },
    )
    RecorderScreen(state, actions, modifier)
}

private fun chosen(context: android.content.Context, result: ActivityResult) =
    if (result.resultCode == Activity.RESULT_OK) RecorderCompanion.deviceOf(context, result.data) else null

/** What the screen can do: the ViewModel's events and the Android actions that need the Activity. */
private class RecorderActions(
    val onEvent: (RecorderEvent) -> Unit,
    val back: () -> Unit,
    val requestPermission: () -> Unit,
    val openSystem: (Intent) -> Unit,
    val find: () -> Unit,
)

@Composable
private fun RecorderScreen(state: RecorderUiState, actions: RecorderActions, modifier: Modifier = Modifier) {
    val colors = CassiniTheme.colors
    Column(
        modifier.fillMaxSize().background(colors.bg).windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = actions.back) { Text(stringResource(R.string.recorder_back)) }
        Text(
            stringResource(R.string.recorder_title),
            Modifier.semantics { heading() },
            style = CassiniTheme.type.screenTitle,
            color = colors.ink,
        )
        Body(stringResource(R.string.recorder_privacy))
        Readiness(state.readiness, actions)
        val bind = state.bind
        if (bind != null) BindSection(bind, state, actions) else DeviceSection(state, actions)
        HorizontalDivider()
        Recordings(state.recordings, actions)
    }
}

@Composable
private fun Readiness(readiness: RadioReadiness, actions: RecorderActions) {
    if (readiness == RadioReadiness.READY) return
    val context = LocalContext.current
    Body(RecorderTexts.readiness(context, readiness))
    when (readiness) {
        RadioReadiness.NO_PERMISSION -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.requestPermission) { Text(stringResource(R.string.recorder_permission_allow)) }
            // After two refusals Android no longer asks: the app settings can still grant it.
            OutlinedButton(onClick = {
                val uri = Uri.fromParts("package", context.packageName, null)
                actions.openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
            }) { Text(stringResource(R.string.recorder_app_settings)) }
        }
        RadioReadiness.OFF -> BluetoothSettings(actions)
        else -> Unit
    }
}

@Composable
private fun DeviceSection(state: RecorderUiState, actions: RecorderActions) {
    val context = LocalContext.current
    val device = state.device
    val ready = state.readiness == RadioReadiness.READY
    Text(
        device?.let { stringResource(R.string.recorder_bound_device, it.name, it.address) }
            ?: stringResource(R.string.recorder_not_bound),
        style = CassiniTheme.type.body16,
        color = CassiniTheme.colors.ink,
    )
    if (state.rebindNeeded) Body(stringResource(R.string.recorder_rebind_needed), error = true)
    if (state.transcriptsUnsupported) Body(stringResource(R.string.recorder_transcripts_unsupported), error = true)
    if (device == null || state.rebindNeeded) {
        Button(onClick = { actions.onEvent(RecorderEvent.StartBind) }, enabled = ready && !state.busy) {
            Text(stringResource(R.string.recorder_bind))
        }
    }
    if (device == null) return
    if (state.busy) Body(RecorderTexts.activity(context, state.activity))
    val canSync = ready && !state.busy && !state.rebindNeeded
    Button(onClick = { actions.onEvent(RecorderEvent.SyncNow) }, enabled = canSync) {
        Text(stringResource(R.string.recorder_sync_now))
    }
    Body(RecorderTexts.lastSync(context, state.lastSync))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Title(stringResource(R.string.recorder_auto_sync))
            Body(stringResource(R.string.recorder_auto_sync_hint))
        }
        Switch(checked = state.autoSync, onCheckedChange = { actions.onEvent(RecorderEvent.AutoSync(it)) })
    }
    OutlinedButton(onClick = { actions.onEvent(RecorderEvent.Forget) }, enabled = !state.busy) {
        Text(stringResource(R.string.recorder_forget))
    }
}

@Composable
private fun BindSection(bind: BindUi, state: RecorderUiState, actions: RecorderActions) {
    val context = LocalContext.current
    when (bind) {
        BindUi.Instructions -> {
            Body(stringResource(R.string.recorder_bind_instructions))
            Button(onClick = actions.find) { Text(stringResource(R.string.recorder_bind_find)) }
        }
        is BindUi.Choose -> {
            Body(stringResource(R.string.recorder_bind_choose))
            if (bind.devices.isEmpty()) Body(stringResource(R.string.recorder_bind_none))
            bind.devices.forEach { device ->
                OutlinedButton(onClick = { actions.onEvent(RecorderEvent.DevicePicked(device)) }) {
                    Text(stringResource(R.string.recorder_bound_device, device.name, device.address))
                }
            }
            BluetoothSettings(actions)
        }
        BindUi.Working -> Body(RecorderTexts.activity(context, state.activity))
        is BindUi.Pin -> PinEntry(bind.wrong, actions)
        is BindUi.Failed -> {
            Body(RecorderTexts.bindFailure(context, bind.failure), error = true)
            Button(onClick = { actions.onEvent(RecorderEvent.StartBind) }) {
                Text(stringResource(R.string.recorder_try_again))
            }
        }
    }
    TextButton(onClick = { actions.onEvent(RecorderEvent.CancelBind) }) {
        Text(stringResource(R.string.recorder_cancel))
    }
}

@Composable
private fun PinEntry(wrong: Boolean, actions: RecorderActions) {
    var pin by rememberSaveable { mutableStateOf("") }
    Body(stringResource(if (wrong) R.string.recorder_bind_pin_wrong else R.string.recorder_bind_pin), error = wrong)
    OutlinedTextField(
        value = pin,
        onValueChange = { typed -> pin = typed.filter(Char::isDigit).take(PIN_DIGITS) },
        label = { Text(stringResource(R.string.recorder_bind_pin_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
    Button(
        onClick = {
            actions.onEvent(RecorderEvent.PinEntered(pin))
            pin = ""
        },
        enabled = pin.length == PIN_DIGITS,
    ) { Text(stringResource(R.string.recorder_bind_confirm)) }
}

@Composable
private fun Recordings(rows: List<RecordingRow>, actions: RecorderActions) {
    val context = LocalContext.current
    Text(
        stringResource(R.string.recorder_recordings),
        Modifier.semantics { heading() },
        style = CassiniTheme.type.body16,
        color = CassiniTheme.colors.ink,
    )
    if (rows.isEmpty()) Body(stringResource(R.string.recorder_recordings_empty))
    rows.forEach { row ->
        val noteId = row.noteId
        val tap = noteId?.let { Modifier.clickable { actions.onEvent(RecorderEvent.OpenNote(it)) } } ?: Modifier
        Column(tap.fillMaxWidth().padding(vertical = 8.dp)) {
            Title(row.title ?: row.name)
            Body("${row.name} · ${RecorderTexts.status(context, row)}", error = row.failure != null)
        }
    }
}

@Composable
private fun BluetoothSettings(actions: RecorderActions) {
    OutlinedButton(onClick = { actions.openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
        Text(stringResource(R.string.recorder_bluetooth_settings))
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = CassiniTheme.type.body16, color = CassiniTheme.colors.ink)
}

@Composable
private fun Body(text: String, error: Boolean = false) {
    val colors = CassiniTheme.colors
    Text(text, style = CassiniTheme.type.body14, color = if (error) colors.error else colors.text2)
}

private const val PIN_DIGITS = 6
