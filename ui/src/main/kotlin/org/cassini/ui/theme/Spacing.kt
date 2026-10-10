@file:Suppress("MagicNumber") // The spacing and elevation values of docs/design/ui.md sections 4.4 and 5.

package org.cassini.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The shared spacing values, read through `CassiniTheme.spacing`. Other values belong to their component. */
@Immutable
object CassiniSpacing {
    /** Cards and fields from the screen edge. */
    val gutter: Dp = 16.dp

    /** Section headers, the transcript and intros from the screen edge. */
    val textGutter: Dp = 20.dp

    /** Library rows: inset of the row card from the edge. */
    val listInset: Dp = 12.dp

    /** Library rows: padding inside the row card. */
    val rowPad: Dp = 10.dp

    /** Horizontal padding of the top bars. */
    val topBarPad: Dp = 8.dp

    /** The minimum touch target of every control (docs/design/ui.md section 5). */
    val touchTarget: Dp = 48.dp

    /** The width of hairlines and borders: the design is flat. */
    val hairline: Dp = 1.dp
}

/** The only shadow of the design: the Record button's, in [CassiniColors.red]. */
@Immutable
object RecordShadow {
    val radius: Dp = 18.dp
    val offsetY: Dp = 6.dp
    const val ALPHA: Float = 0.28f
}
