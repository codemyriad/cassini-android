package org.cassini.ui.banner

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import org.cassini.ui.R
import org.cassini.ui.format.ModelPartUi

/**
 * One banner to show (ui.md section 7). [actions] default to the kind's catalogue actions; [dismissible] adds the
 * trailing close button; [announce] makes the banner a polite live region.
 *
 * [id] names the banner in the screen's BannerAction and DismissBanner events, so it must be unique in one list.
 * It defaults to the kind; when two banners of one kind can show together, the ViewModel sets a per-notice id (for
 * example the note id or the notice key).
 */
@Immutable
data class BannerSpec(
    val kind: BannerKind,
    val args: BannerArgs = BannerArgs(),
    val actions: ImmutableList<BannerAction> = kind.actions,
    val announce: Boolean = false,
    val dismissible: Boolean = kind.dismissible,
    val id: String = kind.name,
)

/**
 * The semantic values a banner's copy needs; [BannerKind.uses] says which ones a kind reads. Durations are
 * milliseconds and sizes bytes, so the copy is formatted in the shown language.
 *
 * [part] is an addition to ui.md section 7: the download banner names the part it downloads. [bySystem] is an
 * addition too: DL_STOPPED says that Android stopped the download, not the connection.
 */
@Immutable
data class BannerArgs(
    val count: Int = 0,
    val text: String? = null,
    val ms1: Long = 0,
    val ms2: Long = 0,
    val bytes: Long = 0,
    val bytes2: Long = 0,
    val percent: Int? = null,
    val m4a: Boolean = false,
    val part: ModelPartUi? = null,
    val bySystem: Boolean = false,
)

/** The fields of [BannerArgs] a kind reads. */
enum class BannerArg { COUNT, TEXT, MS1, MS2, BYTES, BYTES2, PERCENT, M4A, PART, BY_SYSTEM }

/** A banner button (ui.md section 7) with its label. */
enum class BannerAction(@StringRes val label: Int) {
    RESUME(R.string.ui_action_resume),
    START_OVER(R.string.ui_action_start_over),
    TRY_AGAIN(R.string.ui_action_try_again),
    TRANSCRIBE(R.string.ui_action_transcribe),
    TRANSCRIBE_AGAIN(R.string.ui_action_transcribe_again),
    PLAY_ANYWAY(R.string.ui_action_play_anyway),
    OPEN(R.string.ui_action_open),
    OPEN_NOTE(R.string.ui_action_open_note),
    OPEN_SETTINGS(R.string.ui_action_open_settings),
    TURN_ON(R.string.ui_action_turn_on),
    DOWNLOAD(R.string.ui_action_download),
    DOWNLOAD_AGAIN(R.string.ui_action_download_again),
    RETRY(R.string.ui_action_retry),
    CANCEL(R.string.ui_action_cancel),
    CHOOSE_ANOTHER(R.string.ui_action_choose_another),
    RECORD(R.string.ui_action_record),
    IMPORT(R.string.ui_action_import),
    CONTINUE(R.string.ui_action_continue),
}
