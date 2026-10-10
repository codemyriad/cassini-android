// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package org.cassini.ui.trash

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.cassini.ui.ScreenStub

/** Trash (board 09, features.md section 8). A stub until P8 step 5. */
@Suppress("UnusedParameter", "UNUSED_PARAMETER") // The stub draws nothing from the state yet.
@Composable
fun TrashScreen(
    state: TrashUiState,
    onEvent: (TrashEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenStub("trash", modifier)
}
