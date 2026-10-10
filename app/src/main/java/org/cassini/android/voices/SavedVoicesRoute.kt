package org.cassini.android.voices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.cassini.android.nav.ScreenBackground
import org.cassini.android.nav.ScreenNavigator
import org.cassini.ui.voices.SavedVoicesScreen

/** The Saved voices destination (architecture 6): [SavedVoicesScreen] over [viewModel]; Back pops it. */
@Composable
@Suppress("FunctionNaming") // Composables are nouns in PascalCase.
fun SavedVoicesRoute(viewModel: SavedVoicesViewModel, navigator: ScreenNavigator, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.close.collect { navigator.close() }
        }
    }
    val current = state
    if (current == null) {
        ScreenBackground(modifier)
    } else {
        SavedVoicesScreen(state = current, onEvent = viewModel::onEvent, modifier = modifier)
    }
}
