// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.banner.BannerAction
import org.cassini.ui.banner.BannerArgs
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.banner.BannerSpec
import org.cassini.ui.banner.BannerText
import org.cassini.ui.theme.CassiniColors
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph
import org.cassini.ui.theme.LocalCassiniColors
import org.cassini.ui.theme.Tone
import org.cassini.ui.theme.tone

/**
 * A catalogue banner (features.md section 13) with its copy, tone and actions from [spec]. The first action is an
 * [InkButton], the others [SecondaryButton]s (small). When the spec is dismissible and [onDismiss] is set, a 48 dp
 * close button sits top-right; the States board renders dismissible kinds without it (ui.md section 11, item 10).
 * [progress] adds an optional 4 dp track below the body (no board shows one; the Library has its own card).
 * [colors] lets the dark Recording screen keep the light tones.
 */
@Composable
@Suppress("LongParameterList") // The spec, its two callbacks and three presentation options.
fun Banner(
    spec: BannerSpec,
    onAction: (BannerAction) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    progress: Float? = null,
    colors: CassiniColors = CassiniTheme.colors,
) {
    CompositionLocalProvider(LocalCassiniColors provides colors) {
        val actions = spec.actions
        BannerCard(
            tone = spec.kind.tone,
            title = BannerText.title(spec),
            body = BannerText.body(spec),
            modifier = modifier,
            announce = spec.announce,
            onDismiss = if (spec.dismissible) onDismiss else null,
            progress = progress,
            actions = if (actions.isEmpty()) {
                null
            } else {
                {
                    actions.forEachIndexed { index, action ->
                        val label = BannerText.action(action)
                        if (index == 0) {
                            InkButton(label, onClick = { onAction(action) })
                        } else {
                            SecondaryButton(label, onClick = { onAction(action) })
                        }
                    }
                }
            },
        )
    }
}

/**
 * The banner layout without the catalogue: fills the width (screens give the 16 dp margins); padding 14/16, 8 at the
 * bottom with actions; radius 16; the tone's fill ([Tone.Plain] adds a 1 dp line border on surface). A 10 dp dot
 * (6 dp from the top), then the title (`button`, a heading) in the tone's text colour, the body (`body14`, text3,
 * 3 dp below) and the [actions] in a [FlowRow] (gap 8; top 10, compensated to 4 for the 48 dp buttons).
 * [announce] makes the banner a polite live region.
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // The banner anatomy of ui.md section 6, one slot per part.
fun BannerCard(
    tone: Tone,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    announce: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    progress: Float? = null,
    actions: (@Composable FlowRowScope.() -> Unit)? = null,
) {
    val c = CassiniTheme.colors
    val colors = c.tone(tone)
    val shape = CassiniTheme.shapes.card
    val border = colors.border
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.bg)
            .then(
                if (tone == Tone.Plain && border != null) {
                    Modifier
                        .border(1.dp, border, shape)
                        .padding(1.dp)
                } else {
                    Modifier
                },
            )
            .then(if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(
                start = 16.dp,
                top = 14.dp,
                end = 16.dp,
                bottom = if (actions != null) 8.dp else 14.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(colors.dot),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    modifier = Modifier
                        .padding(end = if (onDismiss != null) 32.dp else 0.dp)
                        .semantics { heading() },
                    style = CassiniTheme.type.button,
                    color = colors.fg,
                )
                if (body != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(text = body, style = CassiniTheme.type.body14, color = c.text3)
                }
                if (progress != null) {
                    Spacer(Modifier.height(10.dp))
                    LinearTrack(progress)
                }
                if (actions != null) {
                    FlowRow(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        content = actions,
                    )
                }
            }
        }
        if (onDismiss != null) {
            IconButton44(
                glyph = Glyph.Close,
                contentDescription = stringResource(R.string.ui_action_dismiss),
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp),
                iconSize = 20.dp,
                stroke = 1.8f,
                tint = c.text2,
            )
        }
    }
}

@Preview
@Composable
internal fun BannerPreview() {
    CassiniTheme {
        Column(
            modifier = Modifier
                .width(390.dp)
                .background(CassiniTheme.colors.bg)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Banner(BannerSpec(BannerKind.TX_PAUSED, BannerArgs(ms1 = 760_000, ms2 = 2_052_000)), onAction = {})
            Banner(
                BannerSpec(BannerKind.RECOVERED, BannerArgs(count = 1, ms1 = 312_000)),
                onAction = {},
                onDismiss = {},
            )
            Banner(BannerSpec(BannerKind.LIB_NO_MATCH, BannerArgs(text = "fattura")), onAction = {})
        }
    }
}
