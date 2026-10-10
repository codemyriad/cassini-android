package org.cassini.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The widest that a screen's content gets on a wide window (architecture.md section 6). */
internal val MaxContentWidth: Dp = 560.dp

/**
 * Limits what comes after this modifier to [MaxContentWidth] and centres it in the width it gets. The modifiers
 * before it, such as the screen's background, still fill the window. On a phone (narrower than 560 dp) it changes
 * nothing. Each screen puts it after its background and its window insets.
 */
internal fun Modifier.screenContentWidth(): Modifier = this
    .fillMaxWidth()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = MaxContentWidth)
    .fillMaxWidth()
