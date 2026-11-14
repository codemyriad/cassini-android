// Composables are nouns in PascalCase; the numbers are the drawn values of cassini-ux COMPONENTS.md section 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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

/**
 * Hero metrics. The phone size (32) is DECIDED (L-27); the gaps and the wave counts are PROPOSED, kept here so a
 * designer edit is a one-line change (COMPONENTS.md, "use drawn values in named constants").
 */
internal object FirstRunHeroMetrics {
    val headlineSize = 32.sp
    const val HEADLINE_LINE_HEIGHT = 1.12f
    val horizontal = 20.dp
    val top = 28.dp
    val waveTop = 24.dp
    val waveHeight = 40.dp
    val howToTop = 20.dp
    val qualifierTop = 6.dp
}

/** The wave component on phones (L-27, W5.2): square 4 dp teal bars, 4 dp apart, fading to the right. */
internal object HeroWaveMetrics {
    val bar = 4.dp
    val gap = 4.dp

    /** Bar heights as fractions of the wave height, repeated across the width. A drawn shape, not audio. */
    val levels = floatArrayOf(
        0.30f, 0.55f, 0.80f, 0.45f, 1.00f, 0.65f, 0.35f, 0.70f, 0.90f, 0.50f, 0.25f, 0.60f,
        0.85f, 0.40f, 0.75f, 0.30f, 0.55f, 0.95f, 0.45f, 0.20f,
    )

    /** The last bar keeps this opacity; the first is opaque. */
    const val END_ALPHA = 0f
}

/**
 * The first run on phones (L-27, D-975): the headline pair, the wave, the how-to line and the privacy line. Record
 * and Import stay in the bottom bar. The second line pairs "None of it uploaded." with its backup qualifier while
 * cloud backup is on, read as the catalog's combined sentence (firstRun.hero.subtitlePhoneNote), never joined in code.
 */
@Composable
fun FirstRunHero(hero: FirstRunHeroUi, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    val headline = CassiniTheme.type.hero.copy(
        fontSize = FirstRunHeroMetrics.headlineSize,
        fontWeight = FontWeight.Bold,
        lineHeight = FirstRunHeroMetrics.HEADLINE_LINE_HEIGHT.em,
    )
    Column(
        modifier.fillMaxWidth()
            .padding(horizontal = FirstRunHeroMetrics.horizontal)
            .padding(top = FirstRunHeroMetrics.top)
            .testTag("first-run-hero"),
    ) {
        Text(
            stringResource(R.string.ui_hero_title),
            Modifier.semantics { heading() },
            style = headline,
            color = c.ink,
        )
        val subtitle = stringResource(R.string.ui_hero_subtitle)
        if (hero.cloudBackup) {
            val spoken = stringResource(R.string.ui_hero_subtitle_backup)
            Column(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
                Text(subtitle, style = headline, color = c.text3)
                Text(
                    stringResource(R.string.ui_hero_backup_qualifier),
                    Modifier.padding(top = FirstRunHeroMetrics.qualifierTop),
                    style = CassiniTheme.type.about,
                    color = c.text3,
                )
            }
        } else {
            Text(subtitle, style = headline, color = c.text3)
        }
        HeroWave(
            Modifier.padding(top = FirstRunHeroMetrics.waveTop)
                .fillMaxWidth()
                .height(FirstRunHeroMetrics.waveHeight),
        )
        Text(
            stringResource(R.string.ui_banner_lib_empty_body),
            Modifier.padding(top = FirstRunHeroMetrics.howToTop),
            style = CassiniTheme.type.about,
            color = c.text2,
        )
        PrivacyLine(
            stringResource(
                when (hero.privacy) {
                    FirstRunPrivacy.WITH_BACKUP -> R.string.ui_privacy_with_backup
                    FirstRunPrivacy.PROCESSING -> R.string.ui_privacy_processing
                    FirstRunPrivacy.PHONE_ONLY -> R.string.ui_privacy_phone_only
                },
            ),
            // One TalkBack stop: the shield is decorative.
            Modifier.semantics(mergeDescendants = true) {}.testTag("hero-privacy"),
        )
        Spacer(Modifier.height(FirstRunHeroMetrics.top))
    }
}

/** Decorative: no semantics. Bars start at the leading edge and fade toward the trailing edge (mirrored in RTL). */
@Composable
internal fun HeroWave(modifier: Modifier = Modifier) {
    val teal = CassiniTheme.colors.teal
    Canvas(modifier.clearAndSetSemantics {}) {
        val bar = HeroWaveMetrics.bar.toPx()
        val step = bar + HeroWaveMetrics.gap.toPx()
        val count = ((size.width + HeroWaveMetrics.gap.toPx()) / step).toInt()
        if (count <= 0) return@Canvas
        val levels = HeroWaveMetrics.levels
        val rtl = layoutDirection == LayoutDirection.Rtl
        for (index in 0 until count) {
            val fraction = if (count == 1) 0f else index / (count - 1f)
            val alpha = 1f - (1f - HeroWaveMetrics.END_ALPHA) * fraction
            val height = (size.height * levels[index % levels.size]).coerceAtLeast(bar)
            val x = if (rtl) size.width - bar - index * step else index * step
            drawRect(
                color = teal.copy(alpha = teal.alpha * alpha),
                topLeft = Offset(x, (size.height - height) / 2f),
                size = Size(bar, height),
            )
        }
    }
}

@Preview
@Composable
internal fun FirstRunHeroPreview() {
    CassiniTheme {
        Column(Modifier.background(CassiniTheme.colors.bg)) {
            FirstRunHero(FirstRunHeroUi(cloudBackup = true, privacy = FirstRunPrivacy.WITH_BACKUP))
            FirstRunHero(FirstRunHeroUi(cloudBackup = false, privacy = FirstRunPrivacy.PROCESSING))
        }
    }
}
