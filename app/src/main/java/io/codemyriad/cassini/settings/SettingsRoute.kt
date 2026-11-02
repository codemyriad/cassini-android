package io.codemyriad.cassini.settings

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
import io.codemyriad.cassini.library.notificationSettings
import io.codemyriad.cassini.nav.ScreenNavigator
import io.codemyriad.cassini.nav.startSystemScreen
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
    SettingsScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}
