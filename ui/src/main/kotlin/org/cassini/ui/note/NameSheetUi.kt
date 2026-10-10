package org.cassini.ui.note

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import org.cassini.ui.components.SpeakerNameUi
import org.cassini.ui.theme.SpeakerTone

/**
 * The Name a speaker sheet (board 04, features.md section 4; ui.md section 7), as the `SpeakerNaming` reducer
 * produces it.
 *
 * - Header: [label] in [tone], "Speaks for <[speakingMs]> in this note", Play sample enabled by [canSample];
 *   [sampling] while the sample plays.
 * - [name] is the field's text. [nameFieldFocused] gives the field the focused look without a caret (fixtures,
 *   board 04); the screen otherwise follows real focus.
 * - [voices] are the rows shown (at most 3 until "Show all" is pressed); [showAll] shows the "Show all" row because
 *   more exist. [ranked] picks the header: "Saved voices, closest first" with a voiceprint, else "Saved voices"
 *   (sorted by name). An empty list hides the card.
 * - [samePerson] is the saved name of the "Is this the same person?" question, [renameSaved] the saved name of the
 *   "Also rename the saved voice?" question, [notName] the voice name of the "Not <name>?" button; null hides each.
 * - [remember] is the checkbox; [rememberEnabled] false disables it with [rememberReason].
 * - [canMerge] shows "Merge into…"; [save] is the Save name state; [draft] is true while the job runs.
 *
 * [name] is a text field value: the ViewModel must apply [NameSheetEvent.NameChanged] synchronously, in Compose
 * snapshot state, not through a collected flow, or the field can drop typed characters and break IME composition.
 */
@Immutable
data class NameSheetUi(
    val speakerId: String,
    val label: SpeakerNameUi,
    val tone: SpeakerTone,
    val speakingMs: Long,
    val canSample: Boolean,
    val sampling: Boolean = false,
    val name: String,
    val voices: ImmutableList<VoiceOptionUi>,
    val showAll: Boolean = false,
    val ranked: Boolean,
    val samePerson: String? = null,
    val renameSaved: String? = null,
    val notName: String? = null,
    val remember: Boolean,
    val rememberEnabled: Boolean,
    val rememberReason: RememberReason? = null,
    val canMerge: Boolean,
    val save: SaveState,
    val nameFieldFocused: Boolean = false,
    val draft: Boolean = false,
)

/** A saved voice in the Name sheet list; [likely] adds the "Likely" tag and the surfaceWash row. */
@Immutable
data class VoiceOptionUi(val id: String, val name: String, val likely: Boolean = false)

/** Why Remember is disabled (features.md section 4, item 7). */
enum class RememberReason {
    /** "Too little speech to remember this voice." (under 4 s). */
    TOO_LITTLE_SPEECH,

    /** "Recognising saved voices isn't installed yet." */
    NO_MODEL,
}

/** The Save name button (features.md section 4, item 8; ui.md section 11, item 12). */
enum class SaveState {
    ENABLED,

    /** Nothing to save: 38 % alpha, no click. */
    DISABLED,

    /** A question is open: Save looks enabled, but a press sends [NameSheetEvent.SaveBlocked] instead of saving. */
    BLOCKED_BY_QUESTION,
}

/** What the Name sheet asks for. */
sealed interface NameSheetEvent {
    @Immutable
    data class NameChanged(val text: String) : NameSheetEvent

    data object PlaySample : NameSheetEvent

    /** A voice row: fill the field with its name and bind the voice. */
    @Immutable
    data class PickVoice(val voiceId: String) : NameSheetEvent

    data object ShowAll : NameSheetEvent

    data object SamePerson : NameSheetEvent

    data object SomeoneElse : NameSheetEvent

    data object RenameSaved : NameSheetEvent

    data object NotName : NameSheetEvent

    @Immutable
    data class RememberChanged(val remember: Boolean) : NameSheetEvent

    data object MergeInto : NameSheetEvent

    data object Cancel : NameSheetEvent

    data object Save : NameSheetEvent

    /**
     * Save was pressed while [SaveState.BLOCKED_BY_QUESTION]: the screen moves accessibility focus to the question
     * and announces "Answer the question above first"; the ViewModel may pulse the card.
     */
    data object SaveBlocked : NameSheetEvent
}
