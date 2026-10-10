// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package org.cassini.ui.voices

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.cassini.ui.ScreenStub

/** Saved voices (board 07, features.md section 10). A stub until P8 step 5. */
@Suppress("UnusedParameter", "UNUSED_PARAMETER") // The stub draws nothing from the state yet.
@Composable
fun SavedVoicesScreen(
    state: SavedVoicesUiState,
    onEvent: (SavedVoicesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenStub("saved-voices", modifier)
}
