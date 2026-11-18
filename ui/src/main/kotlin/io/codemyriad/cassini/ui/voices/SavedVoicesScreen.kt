// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.voices

import android.icu.text.ListFormatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.banner.BannerKind
import io.codemyriad.cassini.ui.banner.BannerSpec
import io.codemyriad.cassini.ui.components.Avatar
import io.codemyriad.cassini.ui.components.Banner
import io.codemyriad.cassini.ui.components.CassiniMenu
import io.codemyriad.cassini.ui.components.CassiniMenuItem
import io.codemyriad.cassini.ui.components.CassiniTopBar
import io.codemyriad.cassini.ui.components.DestructiveOutlineButton
import io.codemyriad.cassini.ui.components.GroupCard
import io.codemyriad.cassini.ui.components.IconButton44
import io.codemyriad.cassini.ui.components.firstGrapheme
import io.codemyriad.cassini.ui.components.screenContentWidth
import io.codemyriad.cassini.ui.format.Counts
import io.codemyriad.cassini.ui.format.Durations
import io.codemyriad.cassini.ui.format.LocalFormat
import io.codemyriad.cassini.ui.theme.CassiniIcon
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph
import io.codemyriad.cassini.ui.theme.SpeakerTone
import io.codemyriad.cassini.ui.theme.sans

/** Saved-voice avatars cycle blue, teal, amber, violet by list position (features.md section 0). */

private val UnreadableBanner = BannerSpec(BannerKind.VOICES_UNREADABLE, announce = true)

/**
 * Saved voices (board 07, features.md section 10; ui.md section 8). A Column on bg: the top bar ("Back to
 * settings"), then a scrolling part with the privacy line, the VOICES_UNREADABLE banner when
 * [SavedVoicesUiState.readOnly], the list card (or the "No saved voices yet" card when there is nobody) and the
 * footnote; at the bottom "Forget everyone…" (hidden when empty, disabled when read-only). While read-only, the
 * empty card and the footnote do not show: the file can't be read, so the list is not known to be empty, and no
 * voice can be added.
 *
 * Each row's options button opens its [CassiniMenu] (Rename…, Forget… in redInk) while
 * [SavedVoicesUiState.menuFor] names the row. On read-only rows the options button shows but is disabled.
 */
@Composable
fun SavedVoicesScreen(
    state: SavedVoicesUiState,
    onEvent: (SavedVoicesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(CassiniTheme.colors.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .screenContentWidth()
            .navigationBarsPadding(),
    ) {
        CassiniTopBar(
            title = stringResource(R.string.ui_voices_title),
            onBack = { onEvent(SavedVoicesEvent.Back) },
            backLabel = stringResource(R.string.ui_back_to_settings),
        )
        val empty = state.people.isEmpty() && !state.readOnly
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = if (empty) Arrangement.Center else Arrangement.Top,
            horizontalAlignment = if (empty) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            SavedVoicesContent(state, onEvent)
        }
        SavedVoicesPrivacyFootnote(
            modifier = Modifier.padding(
                start = 20.dp,
                end = 20.dp,
                top = 8.dp,
                bottom = if (state.people.isNotEmpty()) 0.dp else 24.dp,
            ),
        )
        if (state.people.isNotEmpty()) {
            DestructiveOutlineButton(
                text = stringResource(R.string.ui_voices_forget_everyone),
                onClick = { onEvent(SavedVoicesEvent.ForgetEveryone) },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                enabled = !state.readOnly,
            )
        }
    }
    val dialog = state.dialog
    if (dialog != null) SavedVoicesDialogWindow(dialog, onEvent)
}

@Composable
private fun SavedVoicesContent(state: SavedVoicesUiState, onEvent: (SavedVoicesEvent) -> Unit) {
    val c = CassiniTheme.colors
    if (state.readOnly) {
        Banner(
            spec = UnreadableBanner,
            onAction = {},
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        )
    }
    if (state.people.isEmpty()) {
        // A file that cannot be read is not an empty list: the banner above says so, and nothing contradicts it.
        if (!state.readOnly) EmptyVoicesState()
        return
    }
    val stale = state.people.filter { it.stale }
    if (stale.isNotEmpty()) {
        val names = ListFormatter.getInstance(LocalFormat.current.locale).format(stale.map { it.name })
        StaleVoicesNotice(count = stale.size, names = names)
    }
    Text(
        text = Counts.plural(R.plurals.ui_settings_people, state.people.size),
        modifier = Modifier
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = if (stale.isEmpty() && !state.readOnly) 28.dp else 20.dp,
                bottom = 8.dp,
            )
            .semantics { heading() },
        style = ListHeadingStyle,
        color = c.text2,
    )
    GroupCard(
        modifier = Modifier.padding(horizontal = 16.dp),
        bordered = true,
        shape = CassiniTheme.shapes.list,
    ) {
        state.people.forEach { person ->
            PersonRow(
                person = person,
                tone = person.tone,
                menuOpen = state.menuFor == person.id,
                editable = !state.readOnly,
                onEvent = onEvent,
            )
        }
    }
    // "To add someone, name a speaker…": while read-only, no voice can be added.
    if (!state.readOnly) {
        Text(
            text = stringResource(R.string.ui_voices_caption),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 16.dp),
            style = FootnoteStyle,
            color = c.text2,
        )
    }
}

/** One amber notice above the list when one or more voices are stale (D106 §5). */
@Composable
private fun StaleVoicesNotice(count: Int, names: String) {
    val c = CassiniTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp)
            .clip(CassiniTheme.shapes.card)
            .background(c.amberTint)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = Counts.plural(R.plurals.ui_voices_stale_title, count),
            modifier = Modifier.semantics { heading() },
            style = CassiniTheme.type.button,
            color = c.amberInk,
        )
        Text(
            text = stringResource(R.string.ui_voices_stale_body, names),
            style = CassiniTheme.type.body14,
            color = c.ink,
        )
    }
}

/** Centred empty state without icon or Forget everyone (D106 §5). */
@Composable
private fun EmptyVoicesState() {
    val c = CassiniTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.ui_voices_empty),
            style = EmptyTitleStyle,
            color = c.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.ui_voices_empty_body),
            style = EmptyBodyStyle,
            color = c.text2,
            textAlign = TextAlign.Center,
        )
    }
}

/** Privacy footnote at the bottom of Saved voices, both with voices and when empty (D106 §5). */
@Composable
private fun SavedVoicesPrivacyFootnote(modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        CassiniIcon(Glyph.Shield, 14.dp, 2f, c.accent, null, Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text = stringResource(R.string.ui_voices_footnote_made_on), style = FootnoteStyle, color = c.text2)
            Text(text = stringResource(R.string.ui_voices_footnote_names_only), style = FootnoteStyle, color = c.text2)
        }
    }
}

private val ListHeadingStyle = sans(14, 500).copy(lineHeight = 20.sp)
private val EmptyTitleStyle = sans(22, 500, lh = 1.25f)
private val EmptyBodyStyle = sans(15, lh = 1.45f)

/** The footnote: 13 sp in text2 with line height 1.45. */
private val FootnoteStyle = sans(13, lh = 1.45f)

/**
 * A person: avatar 40 (the initial in the tone's ink), gap 12, the name (`rowTitle`), the meta line ("14 notes ·
 * 22 min confirmed speech", `caption` text2) and the stale hint (`caption` amberInk, 2 dp below), then the options
 * button (MoreSmall 20, "Options for <name>"). Board padding 12/8/12/14 with a 44 dp button, compensated to
 * 10/6/9.5/14 for the 48 dp layout.
 */
@Composable
private fun PersonRow(
    person: PersonUi,
    tone: SpeakerTone,
    menuOpen: Boolean,
    editable: Boolean,
    onEvent: (SavedVoicesEvent) -> Unit,
) {
    val c = CassiniTheme.colors
    val type = CassiniTheme.type
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(text = firstGrapheme(person.name.trim(), LocalFormat.current.locale), tone = tone)
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {}
                .padding(top = 1.dp, bottom = 2.dp),
        ) {
            Text(text = person.name, style = type.rowTitle, color = c.ink)
            Text(
                text = stringResource(
                    R.string.ui_join_dot,
                    Counts.plural(R.plurals.ui_count_notes, person.notes),
                    Durations.roughMinutes(person.confirmedSeconds * MS_PER_SECOND),
                ),
                style = type.caption.copy(lineHeight = type.body16.lineHeight),
                color = c.text2,
            )
            if (person.stale) {
                Box(
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .clip(CircleShape)
                        .background(c.amberTint)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ui_voices_needs_naming),
                        style = type.tag12,
                        color = c.amberInk,
                    )
                }
            }
        }
        Box {
            IconButton44(
                glyph = Glyph.MoreSmall,
                contentDescription = stringResource(R.string.ui_voices_options, person.name),
                onClick = { onEvent(SavedVoicesEvent.OpenMenu(person.id)) },
                iconSize = 20.dp,
                stroke = 0f,
                enabled = editable,
            )
            CassiniMenu(expanded = menuOpen, onDismissRequest = { onEvent(SavedVoicesEvent.DismissMenu) }) {
                SavedVoiceMenuItems(person.id, onEvent)
            }
        }
    }
}

/** The person menu: "Rename…" and "Forget…" in redInk. Also drawn inline by the screenshot frames. */
@Composable
fun SavedVoiceMenuItems(id: String, onEvent: (SavedVoicesEvent) -> Unit) {
    CassiniMenuItem(
        text = stringResource(R.string.ui_action_rename_more),
        onClick = { onEvent(SavedVoicesEvent.Rename(id)) },
    )
    CassiniMenuItem(
        text = stringResource(R.string.ui_voices_forget),
        onClick = { onEvent(SavedVoicesEvent.Forget(id)) },
        destructive = true,
    )
}

private const val MS_PER_SECOND = 1_000L
