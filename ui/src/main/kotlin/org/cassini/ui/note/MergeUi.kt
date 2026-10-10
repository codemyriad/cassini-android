package org.cassini.ui.note

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import org.cassini.ui.components.SpeakerNameUi
import org.cassini.ui.theme.SpeakerTone

/**
 * The Merge sheet and its confirmation (no board; features.md section 4, item 8; ui.md sections 7 and 8).
 * [source] is speaker A of "Merge <A> into…"; [targets] are the other speakers; [confirm] opens the confirmation
 * for that target. [sealedVersion] is the shown version N of a sealed document ("This adds version N+1; version N
 * stays available."); null means the draft ("This changes the draft.").
 *
 * [source] and [sourceTone] are additions to ui.md section 7: the sheet title and the confirmation name speaker A.
 */
@Immutable
data class MergeUi(
    val sourceId: String,
    val source: SpeakerNameUi,
    val sourceTone: SpeakerTone,
    val targets: ImmutableList<MergeTargetUi>,
    val confirm: MergeTargetUi? = null,
    val sealedVersion: Int? = null,
)

/** A speaker that [MergeUi.source] can merge into: label, tint and speaking time. */
@Immutable
data class MergeTargetUi(val speakerId: String, val label: SpeakerNameUi, val tone: SpeakerTone, val speakingMs: Long)

/** What the Merge sheet asks for. */
sealed interface MergeEvent {
    /** A row: open the confirmation for that speaker. */
    @Immutable
    data class PickTarget(val speakerId: String) : MergeEvent

    data object Confirm : MergeEvent

    data object CancelConfirm : MergeEvent
}
