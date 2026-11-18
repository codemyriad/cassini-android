// Composables are nouns in PascalCase; the numbers are the drawn values of P2FirstRun-Phones (D105).
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.PrivacyLine
import io.codemyriad.cassini.ui.theme.CassiniTheme

/** Hero metrics from D105 (`P2FirstRun-Phones`): 16 dp edge, 18 dp group gap, 28 sp hero, 44 dp wave. */
internal object FirstRunHeroMetrics {
    val headlineSize = 28.sp
    const val HEADLINE_LINE_HEIGHT = 1.08f
    val headlineGap = 4.dp
    val groupGap = 18.dp
    val horizontal = 16.dp
    val top = 16.dp
    val bottom = 20.dp
    val waveHeight = 44.dp
}

/**
 * The conversation waveform on phones (D105 §5): 3 dp rounded neutral-grey bars with 3 dp gaps,
 * four phrases and three pauses fading out at the end.
 */
internal object HeroWaveMetrics {
    val bar = 3.dp
    val gap = 3.dp

    val heightsDp = intArrayOf(
        8, 26, 15, 28, 26, 28, 31, 18, 23, 16, 9, 14, 27, 14, 9, 7, 7, 7, 12, 12,
        18, 15, 16, 16, 20, 19, 18, 24, 20, 15, 24, 24, 19, 11, 7, 27, 32, 27, 16, 14,
        20, 16, 30, 38, 24, 34, 16, 7, 7, 7, 11, 12, 12, 12, 11, 7, 7, 7, 7,
    )

    val alphas = floatArrayOf(
        1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,
        1.0f, 1.0f, 1.0f, 1.0f, 0.87f, 0.79f, 0.78f, 0.82f, 0.92f, 1.0f,
        1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,
        1.0f, 1.0f, 1.0f, 0.86f, 0.79f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,
        1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.96f, 0.82f, 0.76f, 0.76f,
        0.82f, 0.84f, 0.79f, 0.74f, 0.69f, 0.64f, 0.6f, 0.55f, 0.5f,
    )
}

/**
 * The first run on phones (L-70, L-71, D105): conversation waveform, "Every word written down." /
 * "Right here, on this phone.", how-to line, and always-true two-line privacy line.
 */
@Composable
fun FirstRunHero(modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    val headline = CassiniTheme.type.hero.copy(
        fontSize = FirstRunHeroMetrics.headlineSize,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp,
        lineHeight = FirstRunHeroMetrics.HEADLINE_LINE_HEIGHT.em,
    )
    Column(
        modifier.fillMaxWidth()
            .padding(
                start = FirstRunHeroMetrics.horizontal,
                end = FirstRunHeroMetrics.horizontal,
                top = FirstRunHeroMetrics.top,
                bottom = FirstRunHeroMetrics.bottom,
            )
            .testTag("first-run-hero"),
        verticalArrangement = Arrangement.spacedBy(FirstRunHeroMetrics.groupGap),
    ) {
        HeroWave(
            Modifier.fillMaxWidth()
                .height(FirstRunHeroMetrics.waveHeight),
        )
        Column(verticalArrangement = Arrangement.spacedBy(FirstRunHeroMetrics.headlineGap)) {
            Text(
                stringResource(R.string.ui_hero_title),
                Modifier.semantics { heading() },
                style = headline,
                color = c.ink,
            )
            Text(
                stringResource(R.string.ui_hero_subtitle_phone),
                style = headline,
                color = c.text3,
            )
        }
        Text(
            stringResource(R.string.ui_first_run_how_to_phone),
            style = CassiniTheme.type.heroBody,
            color = c.text2,
        )
        val privacyText = stringResource(R.string.ui_first_run_privacy_phone) + "\n" +
            stringResource(R.string.ui_first_run_privacy_backups)
        PrivacyLine(
            privacyText,
            // One TalkBack stop: the shield is decorative.
            Modifier.semantics(mergeDescendants = true) {}.testTag("hero-privacy"),
        )
    }
}

/** Decorative: no semantics. Neutral-grey conversation bars across the content width (mirrored in RTL). */
@Composable
internal fun HeroWave(modifier: Modifier = Modifier) {
    val color = CassiniTheme.colors.text3
    Canvas(modifier.clearAndSetSemantics {}) {
        val bar = HeroWaveMetrics.bar.toPx()
        val step = bar + HeroWaveMetrics.gap.toPx()
        val count = ((size.width + HeroWaveMetrics.gap.toPx()) / step).toInt()
        if (count <= 0) return@Canvas
        val heights = HeroWaveMetrics.heightsDp
        val alphas = HeroWaveMetrics.alphas
        val radius = CornerRadius(bar / 2f, bar / 2f)
        val rtl = layoutDirection == LayoutDirection.Rtl
        for (index in 0 until count) {
            val sample = (index * heights.size) / count
            val height = (heights[sample].dp.toPx()).coerceIn(bar, size.height)
            val alpha = alphas[sample]
            val x = if (rtl) size.width - bar - index * step else index * step
            drawRoundRect(
                color = color.copy(alpha = color.alpha * alpha),
                topLeft = Offset(x, (size.height - height) / 2f),
                size = Size(bar, height),
                cornerRadius = radius,
            )
        }
    }
}

@Preview
@Composable
internal fun FirstRunHeroPreview() {
    CassiniTheme {
        Column(Modifier.background(CassiniTheme.colors.bg)) {
            FirstRunHero()
        }
    }
}
