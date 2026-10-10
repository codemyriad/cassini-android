// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.voices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.banner.BannerSpec
import org.cassini.ui.components.Avatar
import org.cassini.ui.components.Banner
import org.cassini.ui.components.BannerCard
import org.cassini.ui.components.CassiniMenu
import org.cassini.ui.components.CassiniMenuItem
import org.cassini.ui.components.CassiniTopBar
import org.cassini.ui.components.DestructiveOutlineButton
import org.cassini.ui.components.GroupCard
import org.cassini.ui.components.IconButton44
import org.cassini.ui.components.PrivacyLine
import org.cassini.ui.components.PrivacyLineStyle
import org.cassini.ui.components.firstGrapheme
import org.cassini.ui.format.Counts
import org.cassini.ui.format.Durations
import org.cassini.ui.format.LocalFormat
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph
import org.cassini.ui.theme.SpeakerTone
import org.cassini.ui.theme.Tone
import org.cassini.ui.theme.sans

/** Saved-voice avatars cycle blue, teal, amber, violet by list position (features.md section 0). */
private val AvatarTones = listOf(SpeakerTone.Blue, SpeakerTone.Teal, SpeakerTone.Amber, SpeakerTone.Violet)

private val UnreadableBanner = BannerSpec(BannerKind.VOICES_UNREADABLE, announce = true)

/**
 * Saved voices (board 07, features.md section 10; ui.md section 8). A Column on bg: the top bar ("Back to
 * settings"), then a scrolling part with the privacy line, the VOICES_UNREADABLE banner when
 * [SavedVoicesUiState.readOnly], the list card (or the "No saved voices yet" card when there is nobody) and the
 * footnote; at the bottom "Forget everyone…" (hidden when empty, disabled when read-only).
 *
 * Each row's options button opens its [CassiniMenu] (Rename…, Forget… in redInk) while
 * [SavedVoicesUiState.menuFor] names the row. Read-only rows have no options button.
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
            .navigationBarsPadding(),
    ) {
        CassiniTopBar(
            title = stringResource(R.string.ui_voices_title),
            onBack = { onEvent(SavedVoicesEvent.Back) },
            backLabel = stringResource(R.string.ui_back_to_settings),
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            SavedVoicesContent(state, onEvent)
        }
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
    PrivacyLine(stringResource(R.string.ui_voices_privacy), style = PrivacyLineStyle.SavedVoices)
    if (state.readOnly) {
        Banner(
            spec = UnreadableBanner,
            onAction = {},
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }
    if (state.people.isEmpty()) {
        BannerCard(
            tone = Tone.Plain,
            title = stringResource(R.string.ui_voices_empty),
            body = null,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    } else {
        GroupCard(
            modifier = Modifier.padding(horizontal = 16.dp),
            bordered = true,
            shape = CassiniTheme.shapes.list,
        ) {
            state.people.forEachIndexed { index, person ->
                PersonRow(
                    person = person,
                    tone = AvatarTones[index % AvatarTones.size],
                    menuOpen = state.menuFor == person.id,
                    editable = !state.readOnly,
                    onEvent = onEvent,
                )
            }
        }
    }
    Text(
        text = stringResource(R.string.ui_voices_footnote),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
        style = FootnoteStyle,
        color = c.text2,
    )
}

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
            // The board's button rows measure 67.5 dp, not 68: 0.5 dp less below the button.
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 9.5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(text = firstGrapheme(person.name.trim(), LocalFormat.current.locale), tone = tone)
        // The text's own padding restores the board's 12 dp when the text, not the button, sets the height.
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {}
                .padding(top = 2.dp, bottom = 2.5.dp),
        ) {
            Text(text = person.name, style = type.rowTitle, color = c.ink)
            Text(
                text = stringResource(
                    R.string.ui_join_dot,
                    Counts.plural(R.plurals.ui_count_notes, person.notes),
                    Durations.confirmedSpeech(person.confirmedSeconds * MS_PER_SECOND),
                ),
                // An inline 13 px span in the board's 16 px line box (19.5 dp).
                style = type.caption.copy(lineHeight = type.body16.lineHeight),
                color = c.text2,
            )
            if (person.stale) {
                Text(
                    text = stringResource(R.string.ui_voices_stale),
                    modifier = Modifier.padding(top = 2.dp),
                    style = type.caption,
                    color = c.amberInk,
                )
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
