// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md sections 6 and 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.note

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.banner.BannerSpec
import io.codemyriad.cassini.ui.components.Banner
import io.codemyriad.cassini.ui.components.ProcessingCardUi
import io.codemyriad.cassini.ui.components.QueuedCard
import io.codemyriad.cassini.ui.components.SuggestionCard
import io.codemyriad.cassini.ui.components.TurnHeader
import io.codemyriad.cassini.ui.components.TurnMode
import io.codemyriad.cassini.ui.components.TurnParagraph
import io.codemyriad.cassini.ui.components.TurnUi
import io.codemyriad.cassini.ui.components.display
import io.codemyriad.cassini.ui.theme.CassiniTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.drop

/**
 * How far the first draft header reaches up over the "Draft transcript" row. Board 02 sets the first speaker line
 * about 11 dp below that row (its 6 dp padding, the article's 4 and the label's 4, less the line's leading); the
 * 48 dp header row puts its line 26 dp from its top. Measured on board 02. The header overlaps only that row's
 * lower part, which holds no control.
 */
private val FirstHeaderLift = 15.5.dp

/** Board 02: 14 dp between the draft's turns (the article's gap). */
private val DraftTurnGap = 14.dp

/** The large step that scrolls the draft to its end; the list stops at the end. */
private const val TO_END_PX = 1_000_000f

/** The margins of a banner or card at the progress card's place: 8 dp above, 16 dp at the sides. */
private val TopItemPadding = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)

/**
 * The body of every Note mode except Reading (features.md section 3, table; ui.md section 8: "Other non-Reading
 * Note modes use the same top bar with their banner or QueuedCard at the card's place"):
 *
 * - Audio only and Unplayable: the banners.
 * - Queued: the Queued card and the banners; over a document, the Read & listen content follows.
 * - Processing: the compact status, optional details sheet, banners and live draft (WP5).
 * - Paused: the pause banner and the read-only draft.
 * - Failed: the failure banner and the draft so far; over a document, the Read & listen content follows.
 *
 * Without a player, the content keeps clear of the navigation bar.
 *
 * Processing, Paused and Failed without a document share one [DraftList] call, so a change between them (Stop,
 * Resume, a failure) keeps the draft's scroll position and its auto-follow. The Read & listen content below a card
 * uses the screen's [listState], as Reading does, so Remove from queue keeps the position too.
 */
@Composable
internal fun ColumnScope.NoteModeBody(state: NoteUiState, listState: LazyListState, onEvent: (NoteEvent) -> Unit) {
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottom = if (state.playback == null) navigationBar else 0.dp
    val mode = state.mode
    val draft = mode is NoteMode.Processing || mode is NoteMode.Paused || (mode is NoteMode.Failed && !mode.overDocument)
    if (draft) {
        DraftList(state, card = (mode as? NoteMode.Processing)?.card, bottom = bottom, onEvent = onEvent)
    } else {
        OtherModeBody(state, listState, onEvent)
    }
}

/** The modes without a draft: banners, the Queued card, and the document below a card or a failure banner. */
@Composable
private fun ColumnScope.OtherModeBody(state: NoteUiState, listState: LazyListState, onEvent: (NoteEvent) -> Unit) {
    when (val mode = state.mode) {
        NoteMode.AudioOnly, NoteMode.Unplayable -> {
            TopBanners(state.banners, onEvent)
            Spacer(Modifier.weight(1f))
        }
        is NoteMode.Queued -> {
            QueuedCard(ahead = mode.ahead, onRemove = { onEvent(NoteEvent.RemoveFromQueue) }, modifier = TopItemPadding)
            if (mode.overDocument) {
                DocumentBelow(state, banners = true, listState, onEvent)
            } else {
                TopBanners(state.banners, onEvent)
                Spacer(Modifier.weight(1f))
            }
        }
        is NoteMode.Failed -> {
            TopBanners(state.banners, onEvent)
            DocumentBelow(state, banners = false, listState, onEvent)
        }
        else -> Spacer(Modifier.weight(1f))
    }
}

/** The banners at the progress card's place, 8 dp apart. */
@Composable
private fun TopBanners(banners: ImmutableList<BannerSpec>, onEvent: (NoteEvent) -> Unit) {
    banners.forEach { spec -> NoteBanner(spec, onEvent, TopItemPadding) }
}

@Composable
private fun NoteBanner(spec: BannerSpec, onEvent: (NoteEvent) -> Unit, modifier: Modifier) {
    Banner(
        spec = spec,
        onAction = { onEvent(NoteEvent.BannerAction(spec.id, it)) },
        modifier = modifier,
        onDismiss = { onEvent(NoteEvent.DismissBanner(spec.id)) },
    )
}

/**
 * The Read & listen content below a Queued card or a failure banner (a document being transcribed again). The
 * chips row is drawn 4 dp up into what is above it (see [NoteChipsRow]); 8 dp more keep it clear of the card.
 */
@Composable
private fun ColumnScope.DocumentBelow(
    state: NoteUiState,
    banners: Boolean,
    listState: LazyListState,
    onEvent: (NoteEvent) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    ReadAndListenBody(state = if (banners) state else state.copy(banners = persistentListOf()), listState, onEvent)
}

/**
 * One draft list survives Processing, Paused and Failed. The compact status remains above the list;
 * the details sheet uses current state and is never owned by a lazy item. New text follows only while
 * the user is at the end; opening details or stopping/resuming does not move the draft.
 */
@Composable
private fun ColumnScope.DraftList(
    state: NoteUiState,
    card: ProcessingCardUi?,
    bottom: Dp,
    onEvent: (NoteEvent) -> Unit,
) {
    val listState = rememberLazyListState()
    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .drop(1)
            .collect { scrolling -> if (!scrolling) stick = !listState.canScrollForward }
    }
    val turns = state.turns
    val currentTurns by rememberUpdatedState(turns)
    LaunchedEffect(listState) {
        // drop(1): the turns shown on opening do not scroll; only new text does.
        snapshotFlow { currentTurns }
            .drop(1)
            .collect { latest ->
                if (stick && latest.isNotEmpty()) {
                    listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
                    listState.scrollBy(TO_END_PX)
                }
            }
    }
    var detailsOpen by rememberSaveable(state.noteId) { mutableStateOf(false) }
    LaunchedEffect(card != null) {
        if (card == null) detailsOpen = false
    }
    Column(Modifier.weight(1f).fillMaxWidth()) {
        if (card != null) {
            TranscribingStatus(
                progress = card,
                stopBusy = (state.mode as? NoteMode.Processing)?.stopBusy == true,
                onDetails = { detailsOpen = true },
                onStop = { onEvent(NoteEvent.Stop) },
            )
        }
        DraftItems(state, listState, bottom, onEvent)
    }
    if (detailsOpen && card != null) {
        TranscribingDetails(
            progress = card,
            stopBusy = (state.mode as? NoteMode.Processing)?.stopBusy == true,
            notificationsOff = state.notificationsOff,
            onStop = { onEvent(NoteEvent.Stop) },
            onDownloadAction = { action ->
                card.download?.let { onEvent(NoteEvent.BannerAction(it.banner().id, action)) }
            },
            onDismiss = { detailsOpen = false },
        )
    }
}

/** The scrolling banners, transcript heading and keyed turns below the compact status. */
@Composable
private fun ColumnScope.DraftItems(
    state: NoteUiState,
    listState: LazyListState,
    bottom: Dp,
    onEvent: (NoteEvent) -> Unit,
) {
    val turns = state.turns
    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .testTag("transcribing-draft"),
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp + bottom),
    ) {
        // Ids are unique in a list (BannerSpec); the index only keeps a duplicate from crashing the list.
        state.banners.forEachIndexed { index, spec ->
            item(key = "banner-$index-${spec.id}", contentType = "banner") { NoteBanner(spec, onEvent, TopItemPadding) }
        }
        if (turns.isNotEmpty()) {
            item(key = "draft-header", contentType = "draft-header") { DraftHeader() }
            itemsIndexed(turns, key = { _, turn -> turn.id }, contentType = { _, _ -> "turn" }) { index, turn ->
                DraftTurn(turn, first = index == 0, onEvent = onEvent)
            }
        }
    }
}

/** The transcript-so-far heading scrolls with the text; unsettled words carry their own lighter ink. */
@Composable
private fun DraftHeader() {
    Text(
        text = stringResource(R.string.ui_tx_transcript),
        modifier = Modifier.fillMaxWidth()
            .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 6.dp)
            .semantics { heading() },
        style = CassiniTheme.type.button,
        color = CassiniTheme.colors.ink,
    )
}

/**
 * One draft turn (board 02), 20 dp from the sides: the speaker header (a 48 dp button when renaming is allowed) or
 * the suggestion card, then the paragraph with the unsettled words in lighter ink. The words are not
 * links. Turns are 14 dp apart; the 48 dp header adds about 7 dp per turn (ui.md section 11, accepted deviation
 * 2), except the first, which reaches up over the header row's padding ([FirstHeaderLift]).
 */
@Composable
private fun DraftTurn(turn: TurnUi, first: Boolean, onEvent: (NoteEvent) -> Unit) {
    val name = turn.name.display()
    val onSpeaker = if (turn.renamable) ({ onEvent(NoteEvent.SpeakerTap(turn.speakerId)) }) else null
    Column(Modifier.padding(start = 20.dp, end = 20.dp)) {
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
                modifier = Modifier.padding(top = if (first) 4.dp else DraftTurnGap),
                continuation = turn.continuation,
                over = turn.over,
                crosstalk = turn.crosstalk,
            )
        } else {
            TurnHeader(
                name = name,
                tone = turn.tone,
                timeMs = turn.startMs,
                auto = turn.auto,
                continuation = turn.continuation,
                over = turn.over,
                crosstalk = turn.crosstalk,
                onClick = onSpeaker,
                modifier = if (first) Modifier.trimVertical(top = FirstHeaderLift, bottom = 0.dp) else Modifier,
            )
        }
        turn.inlineSuggestions.forEach { inline ->
            SuggestionCard(
                speaker = inline.name,
                tone = inline.tone,
                timeMs = inline.startMs,
                name = inline.suggestion.name,
                onYes = { onEvent(NoteEvent.AcceptSuggestion(inline.speakerId, inline.suggestion.voiceId)) },
                onNo = { onEvent(NoteEvent.RejectSuggestion(inline.speakerId, inline.suggestion.voiceId)) },
                onSpeaker = if (turn.renamable) ({ onEvent(NoteEvent.SpeakerTap(inline.speakerId)) }) else null,
            )
        }
        TurnParagraph(
            words = turn.words,
            activeIndex = null,
            onWordTap = {},
            modifier = Modifier.padding(top = if (suggestion != null) 6.dp else 0.dp),
            mode = TurnMode.Draft,
            unsettledFrom = turn.unsettledFrom,
        )
    }
}
