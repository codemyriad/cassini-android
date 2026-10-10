// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md sections 6 and 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import org.cassini.ui.R
import org.cassini.ui.components.IconButton44
import org.cassini.ui.components.LargeChip
import org.cassini.ui.components.PillSearchField
import org.cassini.ui.components.SpeakerNameUi
import org.cassini.ui.components.SuggestionCard
import org.cassini.ui.components.TurnHeader
import org.cassini.ui.components.TurnMatches
import org.cassini.ui.components.TurnMode
import org.cassini.ui.components.TurnParagraph
import org.cassini.ui.components.TurnUi
import org.cassini.ui.components.VersionChip
import org.cassini.ui.components.WordRef
import org.cassini.ui.components.display
import org.cassini.ui.format.Counts
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph
import org.cassini.ui.theme.Tone

/** The chips' 48 dp layouts hold 8 dp above and below their 32 dp visuals; the chips row gives them back. */
private val ChipSlack = 8.dp

/** Auto-follow puts the active line at 30 % of the transcript's height, once it leaves the top 70 %. */
private const val FOLLOW_AT = 0.3f
private const val FOLLOW_BAND = 0.7f

/** The extra space between a turn header and its paragraph in Read mode (see [Turn]). */
private val ParagraphGap = 1.5.dp

/** Auto-follow resumes after 4 s without scrolling (ui.md section 6, TurnParagraph). */
private const val FOLLOW_RESUME_MS = 4_000L

/**
 * The chips row of Read & listen (board 03): the trust chip (a tap toggles its banner), the version chip with 2 or
 * more readable variants (opens the Versions sheet) and "3 speakers" (hidden at 0), 8 dp apart, wrapping. Board
 * padding top 6, horizontal 16, bottom 10. The tappable chips are 48 dp high around their 32 dp visuals: the row is
 * drawn 4 dp into the top bar's layout (whose 48 dp buttons end 2 dp below their visuals) and the wrapped row
 * takes the chips' bottom slack as its gap, so the board's pixels do not move (ui.md section 5). With no speaker
 * count the last row is a chip row, and its slack adds 8 dp above the banners.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoteChipsRow(chips: NoteChipsUi, onEvent: (NoteEvent) -> Unit) {
    val c = CassiniTheme.colors
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .trimVertical(top = ChipSlack - 4.dp, bottom = 0.dp)
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        // The 8 dp below a chip's visual is its slack; the speaker count's line is 32 dp with no slack.
        verticalArrangement = Arrangement.spacedBy(8.dp - ChipSlack),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val (label, tone) = trustChip(chips.trust)
        LargeChip(text = label, tone = tone, onClick = { onEvent(NoteEvent.TrustChip) })
        chips.version?.let { version ->
            val suffix = when (version.kind) {
                VersionKindUi.ORIGINAL -> null
                VersionKindUi.MERGED -> stringResource(R.string.ui_version_suffix_merged)
                VersionKindUi.TRANSCRIBED_AGAIN -> stringResource(R.string.ui_version_suffix_again)
            }
            val text = stringResource(R.string.ui_version_chip, version.index, version.count)
            VersionChip(
                text = if (suffix == null) text else stringResource(R.string.ui_join_space, text, suffix),
                onClick = { onEvent(NoteEvent.OpenVersions) },
            )
        }
        if (chips.speakers > 0) {
            Box(Modifier.heightIn(min = 32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = Counts.plural(R.plurals.ui_count_speakers, chips.speakers),
                    style = CassiniTheme.type.caption,
                    color = c.text2,
                )
            }
        }
    }
}

/** The trust chip's copy and tone (features.md section 3.3). */
@Composable
internal fun trustChip(trust: TrustUi): Pair<String, Tone> = when (trust) {
    TrustUi.VERIFIED -> stringResource(R.string.ui_trust_verified) to Tone.Ok
    TrustUi.NOT_VERIFIED -> stringResource(R.string.ui_trust_not_verified) to Tone.Warn
    TrustUi.AUDIO_CHANGED -> stringResource(R.string.ui_trust_audio_changed) to Tone.Warn
    TrustUi.UNREADABLE -> stringResource(R.string.ui_trust_unreadable) to Tone.Error
    TrustUi.NEWER_FORMAT -> stringResource(R.string.ui_trust_newer_format) to Tone.Error
}

/**
 * Lays the content out [top] higher and [bottom] shorter than it measures (negative values add space), as a
 * negative margin. The touch areas keep their full size: nothing clips them.
 */
internal fun Modifier.trimVertical(top: Dp, bottom: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val topPx = top.roundToPx()
    val height = (placeable.height - topPx - bottom.roundToPx()).coerceAtLeast(0)
    layout(placeable.width, height) { placeable.place(0, -topPx) }
}

/**
 * The in-note search header (no board; ui.md section 8), in the top bar's place and height: Close search (48 dp),
 * the compact search field "Search in transcript", the counter "2 of 7" (or "No matches"), then Previous and Next
 * match. Opening with an empty query focuses the field.
 */
@Composable
internal fun TranscriptSearchHeader(search: TranscriptSearchUi, onEvent: (NoteEvent) -> Unit) {
    val c = CassiniTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val openedEmpty = remember { search.query.isEmpty() }
    if (openedEmpty) LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton44(Glyph.Back, stringResource(R.string.ui_search_close), { onEvent(NoteEvent.CloseSearch) })
        PillSearchField(
            query = search.query,
            onQueryChange = { onEvent(NoteEvent.SearchQuery(it)) },
            placeholder = stringResource(R.string.ui_note_search),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus),
            onSearch = { keyboard?.hide() },
            onClear = { onEvent(NoteEvent.SearchQuery("")) },
            compact = true,
        )
        if (search.query.isNotBlank()) {
            val t = CassiniTheme.type
            Text(
                text = if (search.total > 0) {
                    stringResource(R.string.ui_search_counter, search.current + 1, search.total)
                } else {
                    stringResource(R.string.ui_search_no_matches)
                },
                modifier = Modifier.padding(start = 8.dp, end = 2.dp),
                style = if (search.total > 0) t.monoTime else t.caption,
                color = c.text2,
                maxLines = 1,
            )
        }
        val any = search.total > 0
        IconButton44(
            glyph = Glyph.ChevronUp,
            contentDescription = stringResource(R.string.ui_search_previous),
            onClick = { onEvent(NoteEvent.PreviousMatch) },
            enabled = any,
        )
        IconButton44(
            glyph = Glyph.ChevronDown,
            contentDescription = stringResource(R.string.ui_search_next),
            onClick = { onEvent(NoteEvent.NextMatch) },
            enabled = any,
        )
    }
}

/**
 * Where the active line is: the list's and the active paragraph's coordinates, the line's top in the paragraph and
 * the list's height (px).
 * Plain fields, not state: only the follow effect reads them, after a frame.
 */
private class ActiveLine {
    var list: LayoutCoordinates? = null
    var paragraph: LayoutCoordinates? = null
    var top: Float? = null
    var height: Int = 0

    /** The line's top in the list's coordinates, or null while the active paragraph is not laid out. */
    fun y(): Float? {
        val list = list
        val paragraph = paragraph?.takeIf { it.isAttached }
        val top = top
        return if (list != null && paragraph != null && top != null) {
            list.localPositionOf(paragraph, Offset(0f, top)).y
        } else {
            null
        }
    }
}

/**
 * The transcript pane (board 03): surface with a 1 dp line top border, padding 0/20/16. One item per turn, keyed by
 * its id: the speaker header (or the inline suggestion card) and the paragraph. Only the turn that holds the
 * active word gets it, so a new word recomposes two turns at most.
 *
 * While [following], a new active word scrolls its line to 30 % of the pane once it leaves the top 70 %; a drag
 * pauses following ([NoteEvent.PauseFollowing]) and, while [followPaused], 4 s without scrolling resume it
 * ([NoteEvent.FollowPlayback]). The current search match scrolls into view.
 */
@Composable
@Suppress("LongParameterList") // The transcript inputs and the follow state.
internal fun TranscriptPane(
    turns: ImmutableList<TurnUi>,
    activeWord: WordRef?,
    search: TranscriptSearchUi?,
    following: Boolean,
    followPaused: Boolean,
    listState: LazyListState,
    onEvent: (NoteEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CassiniTheme.colors
    val matches = remember(search) { search?.byTurn() ?: emptyMap() }
    val active = remember { ActiveLine() }
    val onLine = remember { { top: Float -> active.top = top } }
    val onPlaced = remember { { coordinates: LayoutCoordinates -> active.paragraph = coordinates } }
    FollowEffects(turns, activeWord, following, followPaused, listState, active, onEvent)
    SearchScroll(turns, search, listState)
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(c.surface)
            .drawBehind { drawRect(c.line, Offset.Zero, Size(size.width, 1.dp.toPx())) }
            .onGloballyPositioned {
                active.list = it
                active.height = it.size.height
            },
        state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 0.5.dp, bottom = 16.dp),
    ) {
        items(turns, key = { it.id }) { turn ->
            val activeIndex = activeWord?.takeIf { it.turnId == turn.id }?.index
            Turn(
                turn = turn,
                activeIndex = activeIndex,
                matches = matches[turn.id] ?: TurnMatches.None,
                onEvent = onEvent,
                onActiveLine = if (activeIndex != null) onLine else null,
                onParagraphPlaced = if (activeIndex != null) onPlaced else null,
            )
        }
    }
}

/**
 * Auto-follow (see [TranscriptPane]): a drag pauses it, 4 s without scrolling resume it, and a new active word
 * scrolls its line to 30 % of the pane once it leaves the top 70 %.
 */
@Composable
@Suppress("LongParameterList") // The transcript, the follow state and where the active line is.
private fun FollowEffects(
    turns: ImmutableList<TurnUi>,
    activeWord: WordRef?,
    following: Boolean,
    followPaused: Boolean,
    listState: LazyListState,
    active: ActiveLine,
    onEvent: (NoteEvent) -> Unit,
) {
    val event by rememberUpdatedState(onEvent)
    val follows by rememberUpdatedState(following)
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            // Only a drag during playback pauses auto-follow: with playback paused there is nothing to follow.
            if (it is DragInteraction.Start && follows) event(NoteEvent.PauseFollowing)
        }
    }
    if (followPaused) {
        LaunchedEffect(listState.isScrollInProgress) {
            if (!listState.isScrollInProgress) {
                delay(FOLLOW_RESUME_MS)
                event(NoteEvent.FollowPlayback)
            }
        }
    }
    if (following && activeWord != null) {
        LaunchedEffect(activeWord) {
            val index = turns.indexOfFirst { it.id == activeWord.turnId }
            if (index < 0) return@LaunchedEffect
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
            withFrameNanos {}
            val y = active.y() ?: return@LaunchedEffect
            val pane = active.height
            if (y < 0f || y > pane * FOLLOW_BAND) listState.animateScrollBy(y - pane * FOLLOW_AT)
        }
    }
}

/** Scrolls the turn of the current search match into view when it is off screen. */
@Composable
private fun SearchScroll(turns: ImmutableList<TurnUi>, search: TranscriptSearchUi?, listState: LazyListState) {
    val current = search?.matches?.getOrNull(search.current) ?: return
    LaunchedEffect(current) {
        val index = turns.indexOfFirst { it.id == current.turnId }
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.animateScrollToItem(index)
        }
    }
}

/**
 * One turn: the 48 dp speaker header, or the suggestion card with the paragraph 6 dp below it. Below a header the
 * paragraph starts [ParagraphGap] lower than TurnHeader's 6 dp give: board 03 measures 1.5 dp more between the
 * speaker line and the first text line, because the browser's 1.6 line box is 27.2 dp and the header's mono time
 * sets a 17 dp line.
 */
@Composable
@Suppress("LongParameterList") // The turn, its active word and matches, the events and the follow hooks.
private fun Turn(
    turn: TurnUi,
    activeIndex: Int?,
    matches: TurnMatches,
    onEvent: (NoteEvent) -> Unit,
    onActiveLine: ((Float) -> Unit)?,
    onParagraphPlaced: ((LayoutCoordinates) -> Unit)?,
) {
    val name = turn.name.display()
    val onSpeaker = if (turn.renamable) ({ onEvent(NoteEvent.SpeakerTap(turn.speakerId)) }) else null
    Column {
        val suggestion = turn.suggestion
        if (suggestion != null) {
            SuggestionCard(
                speaker = name,
                tone = turn.tone,
                timeMs = turn.startMs,
                name = suggestion.name,
                onYes = { onEvent(NoteEvent.AcceptSuggestion(turn.speakerId, suggestion.voiceId)) },
                onNo = { onEvent(NoteEvent.RejectSuggestion(turn.speakerId, suggestion.voiceId)) },
                onSpeaker = onSpeaker,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            TurnHeader(name = name, tone = turn.tone, timeMs = turn.startMs, auto = turn.auto, onClick = onSpeaker)
        }
        TurnParagraph(
            words = turn.words,
            activeIndex = activeIndex,
            onWordTap = { onEvent(NoteEvent.WordTap(WordRef(turn.id, it))) },
            modifier = Modifier
                .then(if (onParagraphPlaced != null) Modifier.onGloballyPositioned(onParagraphPlaced) else Modifier)
                .padding(top = if (suggestion != null) 6.dp else ParagraphGap),
            mode = TurnMode.Read,
            placeholder = turn.name is SpeakerNameUi.Placeholder,
            matches = matches,
            onPlayFromStart = { onEvent(NoteEvent.PlayTurn(turn.id)) },
            onActiveLine = onActiveLine,
        )
    }
}

/** The search hits per turn id, with the current hit marked in its own turn. */
private fun TranscriptSearchUi.byTurn(): Map<String, TurnMatches> {
    val current = matches.getOrNull(current)
    return matches.groupBy { it.turnId }.mapValues { (_, hits) ->
        TurnMatches(
            ranges = hits.map { it.range }.toImmutableList(),
            current = current?.let { hits.indexOf(it) }?.takeIf { it >= 0 },
        )
    }
}
