// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package org.cassini.ui.recording

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.cassini.ui.ScreenStub

/** The Recording screen (board 01, features.md section 2). A stub until P8 step 3. */
@Suppress("UnusedParameter", "UNUSED_PARAMETER") // The stub draws nothing from the state yet.
@Composable
fun RecordingScreen(
    state: RecordingUiState,
    onEvent: (RecordingEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenStub("recording", modifier)
}
