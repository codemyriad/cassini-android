package org.cassini.android.trash

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
import org.cassini.ui.trash.TrashScreen

/** The Trash destination (architecture 6): [TrashScreen] over [viewModel]; Back pops it. */
@Composable
@Suppress("FunctionNaming") // Composables are nouns in PascalCase.
fun TrashRoute(viewModel: TrashViewModel, navigator: ScreenNavigator, modifier: Modifier = Modifier) {
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
        TrashScreen(state = current, onEvent = viewModel::onEvent, modifier = modifier)
    }
}
