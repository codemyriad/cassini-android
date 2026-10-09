package org.cassini.ui.components

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import org.cassini.ui.theme.CassiniSpacing

/**
 * Lays a control out at least 48 x 48 dp (docs/design/ui.md section 5). Put it on the clickable node and draw the
 * smaller board visual centred inside; the neighbouring padding is then reduced by the difference, so no pixel
 * moves. This is explicit on purpose: the compensation is visible in code, unlike `minimumInteractiveComponentSize()`.
 */
fun Modifier.touchTarget(): Modifier =
    this.sizeIn(minWidth = CassiniSpacing.touchTarget, minHeight = CassiniSpacing.touchTarget)
