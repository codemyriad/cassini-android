// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package org.cassini.ui.licenses

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.cassini.ui.ScreenStub

/** Licenses (no board; features.md section 11). A stub until P8 step 5. */
@Suppress("UnusedParameter", "UNUSED_PARAMETER") // The stub draws nothing from the state yet.
@Composable
fun LicensesScreen(
    state: LicensesUiState,
    onEvent: (LicensesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenStub("licenses", modifier)
}
