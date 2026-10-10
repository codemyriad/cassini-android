package org.cassini.android.models

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.cassini.android.nav.LicensesKey
import org.cassini.android.nav.ScreenBackground
import org.cassini.android.nav.ScreenNavigator
import org.cassini.ui.models.ModelDownloadScreen

/**
 * The Model download destination (architecture 6): collects [viewModel]'s state for [ModelDownloadScreen] and
 * carries out its [ModelDownloadEffect]s. Until the plan is read (or when the entry is stale and pops itself) it
 * shows only the background.
 */
@Composable
@Suppress("FunctionNaming") // Composables are nouns in PascalCase.
fun ModelDownloadRoute(viewModel: ModelDownloadViewModel, navigator: ScreenNavigator, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onNotificationsAnswered()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    ModelDownloadEffect.Close -> navigator.close()
                    ModelDownloadEffect.OpenLicenses -> navigator.open(LicensesKey)
                    ModelDownloadEffect.RequestNotifications ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.onNotificationsAnswered()
                        }
                }
            }
        }
    }
    val current = state
    if (current == null) {
        ScreenBackground(modifier)
    } else {
        ModelDownloadScreen(state = current, onEvent = viewModel::onEvent, modifier = modifier)
    }
}
