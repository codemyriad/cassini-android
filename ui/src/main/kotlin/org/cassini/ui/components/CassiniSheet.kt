// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniTheme

/**
 * The app's bottom sheet: an M3 `ModalBottomSheet`, always fully expanded, with the 28 dp top corners, the surface
 * colour, the scrim token, no tonal elevation, the [DragHandle] and the navigation-bar insets. The content gets IME
 * padding, so a focused field stays above the keyboard. Screens give the content its own padding (board 06: 10/12/20;
 * board 04: 10/20/24 with a 16 dp gap); the handle already holds the top 10 dp.
 *
 * The sheet state stays inside (its API is experimental); to close, the screen drops the sheet from its state.
 * Screenshot tests render the same content inline in `SheetBoardFrame` (test sources).
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CassiniSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = CassiniTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = CassiniTheme.shapes.sheet,
        containerColor = c.surface,
        contentColor = c.ink,
        tonalElevation = 0.dp,
        scrimColor = c.scrim,
        dragHandle = { DragHandle() },
        contentWindowInsets = { WindowInsets.navigationBars },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            content = content,
        )
    }
}

/**
 * The sheet's drag handle: a 36 x 4 dp line2 bar (radius 2) 10 dp below the sheet's top edge. It is 14 dp tall in
 * layout, so the content below starts where the boards start it. The sheet adds the dismiss and expand actions.
 */
@Composable
fun DragHandle(modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CassiniTheme.shapes.track4)
                .background(CassiniTheme.colors.line2),
        )
    }
}

@Preview
@Composable
internal fun DragHandlePreview() {
    CassiniTheme {
        Column(
            Modifier
                .width(390.dp)
                .background(CassiniTheme.colors.surface),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DragHandle()
            SheetSection("Save a copy")
        }
    }
}
