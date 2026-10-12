package org.cassini.ui.components

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import org.cassini.ui.theme.CassiniSpacing

/**
 * Lays a control out at least 48 x 48 dp (docs/design/ui.md section 5). Put it on the clickable node and draw the
 * smaller board visual centred inside; the neighbouring padding is then reduced by the difference, so no pixel
 * moves. This is explicit on purpose: the compensation is visible in code, unlike `minimumInteractiveComponentSize()`.
 */
fun Modifier.touchTarget(): Modifier =
    this.sizeIn(minWidth = CassiniSpacing.touchTarget, minHeight = CassiniSpacing.touchTarget)

/**
 * Lays the content out [amount] smaller on every side than it measures, as a negative margin; nothing clips it. A
 * 48 dp [touchTarget] around a 44 dp visual then takes the visual's 44 dp in its row (the Name sheet's Play sample).
 */
internal fun Modifier.overhang(amount: Dp): Modifier = layout { measurable, constraints ->
    val px = amount.roundToPx()
    val placeable = measurable.measure(constraints.offset(2 * px, 2 * px))
    layout((placeable.width - 2 * px).coerceAtLeast(0), (placeable.height - 2 * px).coerceAtLeast(0)) {
        placeable.place(-px, -px)
    }
}
