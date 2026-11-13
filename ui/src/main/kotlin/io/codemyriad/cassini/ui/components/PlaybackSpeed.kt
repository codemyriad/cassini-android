@file:Suppress("MagicNumber") // Existing 52dp player touch target and the board speed pill metrics.

package io.codemyriad.cassini.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.theme.CassiniTheme

@Composable
internal fun speedControlWidth(speed: SpeedControl): Dp {
    val label = stringResource(R.string.ui_speed_rate, speedNumber(speed.speed))
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = CassiniTheme.type.label14.copy(
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )
    val pixels = remember(label, style, density) { measurer.measure(label, style, maxLines = 1).size.width }
    return maxOf(52.dp, with(density) { pixels.toDp() } + 20.dp)
}
