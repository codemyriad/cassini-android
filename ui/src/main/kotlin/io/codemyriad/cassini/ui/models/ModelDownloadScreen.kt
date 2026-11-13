// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.models

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.Banner
import io.codemyriad.cassini.ui.components.GroupCard
import io.codemyriad.cassini.ui.components.IconButton44
import io.codemyriad.cassini.ui.components.PrimaryButton
import io.codemyriad.cassini.ui.components.PrimaryButtonSize
import io.codemyriad.cassini.ui.components.TextButton
import io.codemyriad.cassini.ui.components.TextButtonSize
import io.codemyriad.cassini.ui.components.screenContentWidth
import io.codemyriad.cassini.ui.format.ModelPartUi
import io.codemyriad.cassini.ui.format.Sizes
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

/**
 * The Model download screen (board 05, features.md section 5; ui.md section 8). A Column on bg with the board's
 * padding 16/20/24 inside the safe drawing insets: Close, then the content (weight 1, scrolls, gap 20): the logo, the
 * title, the body with the bold total, the card of missing parts, the footnote and the added "Where the models come
 * from · Privacy" row (ui.md section 11, item 7). At the bottom: the banner when there is one, the large primary
 * button ("Download and transcribe" with a note, else "Download") and "Not now — keep the audio".
 *
 * The board's Close is a 44 dp visual at margin -10; its 48 dp layout sits 2 dp higher and 12 dp to the start, and
 * the content's top padding takes the 4 dp back (22 instead of 24), so no pixel moves.
 */
@Composable
fun ModelDownloadScreen(
    state: ModelDownloadUiState,
    onEvent: (ModelDownloadEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CassiniTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .screenContentWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
    ) {
        IconButton44(
            glyph = Glyph.Close,
            contentDescription = stringResource(R.string.ui_action_close),
            onClick = { onEvent(ModelDownloadEvent.Close) },
            modifier = Modifier.offset(x = (-12).dp),
            stroke = 1.8f,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 22.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // L-04: the cassini.talk Saturn, saturn-mid above header size, 56 dp tall as the board's logo.
            Image(
                painterResource(R.drawable.ui_saturn_mid),
                contentDescription = null,
                modifier = Modifier.size(86.dp, 56.dp).testTag("saturn-mid"),
            )
            // Android lays a 28 sp line out about 2 dp taller than its 32.2 dp line box (it never goes below the
            // font's own height); 1 dp less above and below the title keeps it and the body on the board's pixels.
            Text(
                text = stringResource(R.string.ui_models_title),
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val trim = TitleTrim.roundToPx()
                        layout(placeable.width, placeable.height - 2 * trim) { placeable.place(0, -trim) }
                    }
                    .semantics { heading() },
                style = CassiniTheme.type.hero,
                color = c.ink,
            )
            if (state.parts.isNotEmpty()) {
                ModelDownloadBody(state)
                GroupCard(bordered = true) {
                    state.parts.forEach { PartRow(it) }
                }
                ModelDownloadFootnote(state, onEvent)
            }
        }
        ModelDownloadActions(state, onEvent)
    }
}

/** The free-space footnote and the "Sources · Privacy" link under the parts. */
@Composable
private fun ModelDownloadFootnote(state: ModelDownloadUiState, onEvent: (ModelDownloadEvent) -> Unit) {
    Text(
        text = stringResource(R.string.ui_models_footnote, Sizes.storage(state.requiredFreeBytes)),
        style = CassiniTheme.type.body14,
        color = CassiniTheme.colors.text2,
    )
    TextButton(
        text = stringResource(
            R.string.ui_join_dot,
            stringResource(R.string.ui_models_sources),
            stringResource(R.string.ui_models_privacy),
        ),
        onClick = { onEvent(ModelDownloadEvent.OpenSources) },
        // The text lines up with the content: the button's own 14 dp padding goes to the start.
        modifier = Modifier.offset(x = (-14).dp),
    )
}

/** What the title gives back above and below: see the comment at its use. */
private val TitleTrim = 1.dp

/** "Cassini needs a one-time download of about **730 MB**. …": `heroBody` in text3 with the total in bold ink. */
@Composable
private fun ModelDownloadBody(state: ModelDownloadUiState) {
    val c = CassiniTheme.colors
    // A no-break space keeps the bold figure and its unit on one line.
    val total = Sizes.totalMegabytes(state.parts.map { it.bytes }).replace(' ', '\u00A0')
    val singlePart = state.parts.singleOrNull()?.part
    val neutralCopy = singlePart != null && (!state.withNote || singlePart == ModelPartUi.SPEECH)
    val full = if (neutralCopy) {
        stringResource(R.string.ui_settings_optional_size, total) + "\n\n" +
            stringResource(R.string.ui_settings_models_footnote)
    } else {
        stringResource(R.string.ui_models_body, total)
    }
    val at = full.indexOf(total)
    Text(
        text = buildAnnotatedString {
            append(full)
            if (at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.W700, color = c.ink), at, at + total.length)
        },
        style = CassiniTheme.type.heroBody,
        color = c.text3,
    )
}

/** One missing part: padding 14/16, the name in `body15` ink and the size in `monoSize` text2 at the end. */
@Composable
private fun PartRow(part: ModelPartSizeUi) {
    val c = CassiniTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            // Board padding 14 with a 19.5 dp mono line; Android sets that line 0.5 dp taller, so 13.5 here.
            .padding(horizontal = 16.dp, vertical = 13.5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = part.part.label(),
            modifier = Modifier.weight(1f),
            style = CassiniTheme.type.body15,
            color = c.ink,
        )
        Text(
            text = Sizes.megabytes(part.bytes),
            style = CassiniTheme.type.monoSize,
            color = c.text2,
        )
    }
}

/** The bottom column (gap 10): the banner, the primary button and "Not now — keep the audio". */
@Composable
private fun ModelDownloadActions(state: ModelDownloadUiState, onEvent: (ModelDownloadEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val banner = state.banner
        if (banner != null) {
            Banner(
                spec = banner,
                onAction = { onEvent(ModelDownloadEvent.BannerAction(banner.id, it)) },
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (state.parts.isNotEmpty()) {
            PrimaryButton(
                text = stringResource(
                    if (state.withNote) R.string.ui_models_download_and_transcribe else R.string.ui_models_download,
                ),
                onClick = { onEvent(ModelDownloadEvent.Download) },
                size = PrimaryButtonSize.Large,
                busy = state.busy,
            )
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(
                text = stringResource(R.string.ui_models_not_now),
                onClick = { onEvent(ModelDownloadEvent.NotNow) },
                size = TextButtonSize.Tall,
                enabled = !state.busy,
            )
        }
    }
}
