// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package org.cassini.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.cassini.ui.ScreenStub

/** The Library (board 00, features.md section 1). A stub until P8 step 2. */
@Suppress("UnusedParameter", "UNUSED_PARAMETER") // The stub draws nothing from the state yet.
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenStub("library", modifier)
}
