// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package org.cassini.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.cassini.ui.ScreenStub

/** Settings (board 08, features.md section 11). A stub until P8 step 5. */
@Suppress("UnusedParameter", "UNUSED_PARAMETER") // The stub draws nothing from the state yet.
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenStub("settings", modifier)
}
