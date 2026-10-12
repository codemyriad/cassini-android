package org.cassini.android.licenses

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.cassini.android.nav.ScreenNavigator
import org.cassini.android.nav.startSystemScreen
import org.cassini.ui.licenses.LicensesScreen

/** The Licenses destination (architecture 6): [LicensesScreen] over [viewModel]; the GitHub row opens the browser. */
@Composable
@Suppress("FunctionNaming") // Composables are nouns in PascalCase.
fun LicensesRoute(viewModel: LicensesViewModel, navigator: ScreenNavigator, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    LicensesEffect.Close -> navigator.close()
                    is LicensesEffect.OpenUrl ->
                        context.startSystemScreen(Intent(Intent.ACTION_VIEW, effect.url.toUri()))
                }
            }
        }
    }
    LicensesScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}
