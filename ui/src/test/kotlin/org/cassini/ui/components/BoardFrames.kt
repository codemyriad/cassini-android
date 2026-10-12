package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniTheme

/*
 * Inline frames for screenshots (docs/design/ui.md section 10.1). A sheet, dialog or menu normally lives in its own
 * window, which `onRoot()` does not capture; these frames draw the same content in the root, placed as the real
 * window places it, over the scrim of the boards (#8A9098 in light: the scrim token over bg). OverlayScreenshotTest
 * renders each real window type once with `captureScreenRoboImage` to check the placement.
 */

/** A [CassiniSheet] as it shows: the content in the sheet surface with its [DragHandle], at the bottom of the scrim. */
@Composable
internal fun SheetBoardFrame(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = CassiniTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            .background(c.scrim),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(CassiniTheme.shapes.sheet)
                .background(c.surface),
        ) {
            DragHandle(Modifier.align(Alignment.CenterHorizontally))
            content()
        }
    }
}

/** A [CassiniDialog] as it shows: its [CassiniDialogContent] centred over the scrim. */
@Composable
internal fun DialogBoardFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = CassiniTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            .background(c.scrim),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * A [CassiniMenu] as it shows over [screen]: the [CassiniMenuContent] placed for an anchor at [anchor] (in the
 * frame's coordinates) by the rules of M3's `DropdownMenuPositionProvider`: below the anchor, start-aligned, else
 * end-aligned; above it when it does not fit; never closer than 48 dp to the top or bottom edge. No scrim.
 */
@Composable
internal fun MenuBoardFrame(
    anchor: DpRect,
    screen: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        screen()
        Layout(content = { CassiniMenuContent(content = menu) }, modifier = Modifier.fillMaxSize()) { measurables, c ->
            val placeable = measurables.single().measure(Constraints())
            val bounds = IntRect(
                left = anchor.left.roundToPx(),
                top = anchor.top.roundToPx(),
                right = anchor.right.roundToPx(),
                bottom = anchor.bottom.roundToPx(),
            )
            val position = menuPosition(
                anchor = bounds,
                window = IntSize(c.maxWidth, c.maxHeight),
                menu = IntSize(placeable.width, placeable.height),
                margin = MENU_MARGIN.dp.roundToPx(),
            )
            layout(c.maxWidth, c.maxHeight) { placeable.place(position) }
        }
    }
}

private const val MENU_MARGIN = 48

/** M3's dropdown placement, left to right. */
internal fun menuPosition(anchor: IntRect, window: IntSize, menu: IntSize, margin: Int): IntOffset {
    val x = sequenceOf(anchor.left, anchor.right - menu.width, window.width - menu.width)
        .firstOrNull { it >= 0 && it + menu.width <= window.width }
        ?: (anchor.right - menu.width)
    val y = sequenceOf(
        maxOf(anchor.bottom, margin),
        anchor.top - menu.height,
        anchor.top - menu.height / 2,
        window.height - menu.height - margin,
    ).firstOrNull { it >= margin && it + menu.height <= window.height - margin }
        ?: (anchor.top - menu.height)
    return IntOffset(x, y)
}
