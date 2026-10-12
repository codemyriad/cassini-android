package org.cassini.ui.components

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue

private val MinTouch = 48.dp
private val DpTolerance = 0.25.dp
private const val PX_TOLERANCE = 0.5f

/**
 * A standalone control: its layout is at least 48 x 48 dp (ui.md section 5, `Modifier.touchTarget()`), and so is its
 * touch area. The layout check matters: Compose widens the touch bounds of any small clickable node, so the touch
 * check alone passes without `touchTarget()`.
 */
internal fun SemanticsNodeInteraction.assertTouchAtLeast48(density: Density): SemanticsNodeInteraction {
    assertExtendedTouchAtLeast48(density)
    val bounds = getUnclippedBoundsInRoot()
    val width = bounds.right - bounds.left
    val height = bounds.bottom - bounds.top
    val node = fetchSemanticsNode()
    assertTrue("${node.config} layout width $width", width >= MinTouch - DpTolerance)
    assertTrue("${node.config} layout height $height", height >= MinTouch - DpTolerance)
    return this
}

/**
 * An inline control (the Library row suffix): only its touch bounds, which Compose's extended hit testing widens to
 * the minimum touch target, reach 48 x 48 dp.
 */
internal fun SemanticsNodeInteraction.assertExtendedTouchAtLeast48(density: Density): SemanticsNodeInteraction {
    val node = fetchSemanticsNode()
    val touch = node.touchBoundsInRoot
    val min = with(density) { MinTouch.toPx() } - PX_TOLERANCE
    assertTrue("${node.config} touch width ${touch.width} px", touch.width >= min)
    assertTrue("${node.config} touch height ${touch.height} px", touch.height >= min)
    return this
}
