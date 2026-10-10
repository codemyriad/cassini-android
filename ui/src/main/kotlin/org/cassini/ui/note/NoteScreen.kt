// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package org.cassini.ui.note

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.cassini.ui.ScreenStub

/** The Note screen (boards 02, 03, 04, 06; features.md sections 3, 4 and 6). A stub until P8 steps 2–4. */
@Suppress("UnusedParameter", "UNUSED_PARAMETER") // The stub draws nothing from the state yet.
@Composable
fun NoteScreen(
    state: NoteUiState,
    onEvent: (NoteEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenStub("note", modifier)
}
