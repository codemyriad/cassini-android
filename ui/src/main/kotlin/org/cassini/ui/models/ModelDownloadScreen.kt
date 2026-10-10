// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package org.cassini.ui.models

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.cassini.ui.ScreenStub

/** The Model download screen (board 05, features.md section 5). A stub until P8 step 5. */
@Suppress("UnusedParameter", "UNUSED_PARAMETER") // The stub draws nothing from the state yet.
@Composable
fun ModelDownloadScreen(
    state: ModelDownloadUiState,
    onEvent: (ModelDownloadEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenStub("model-download", modifier)
}
