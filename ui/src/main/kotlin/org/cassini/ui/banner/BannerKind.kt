package org.cassini.ui.banner

import androidx.annotation.StringRes
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.cassini.ui.R
import org.cassini.ui.banner.BannerAction.CANCEL
import org.cassini.ui.banner.BannerAction.CHOOSE_ANOTHER
import org.cassini.ui.banner.BannerAction.CONTINUE
import org.cassini.ui.banner.BannerAction.DOWNLOAD
import org.cassini.ui.banner.BannerAction.DOWNLOAD_AGAIN
import org.cassini.ui.banner.BannerAction.IMPORT
import org.cassini.ui.banner.BannerAction.OPEN
import org.cassini.ui.banner.BannerAction.OPEN_NOTE
import org.cassini.ui.banner.BannerAction.OPEN_SETTINGS
import org.cassini.ui.banner.BannerAction.PLAY_ANYWAY
import org.cassini.ui.banner.BannerAction.RECORD
import org.cassini.ui.banner.BannerAction.RESUME
import org.cassini.ui.banner.BannerAction.RETRY
import org.cassini.ui.banner.BannerAction.START_OVER
import org.cassini.ui.banner.BannerAction.TRANSCRIBE
import org.cassini.ui.banner.BannerAction.TRANSCRIBE_AGAIN
import org.cassini.ui.banner.BannerAction.TRY_AGAIN
import org.cassini.ui.banner.BannerAction.TURN_ON
import org.cassini.ui.banner.BannerArg.BYTES
import org.cassini.ui.banner.BannerArg.BYTES2
import org.cassini.ui.banner.BannerArg.BY_SYSTEM
import org.cassini.ui.banner.BannerArg.COUNT
import org.cassini.ui.banner.BannerArg.M4A
import org.cassini.ui.banner.BannerArg.MS1
import org.cassini.ui.banner.BannerArg.MS2
import org.cassini.ui.banner.BannerArg.PART
import org.cassini.ui.banner.BannerArg.PERCENT
import org.cassini.ui.banner.BannerArg.TEXT
import org.cassini.ui.banner.BannerGroup.TRUST
import org.cassini.ui.theme.Tone
import org.cassini.ui.banner.BannerGroup.DOWNLOAD as DL
import org.cassini.ui.banner.BannerGroup.LIBRARY as LIB
import org.cassini.ui.banner.BannerGroup.RECORDING as REC
import org.cassini.ui.banner.BannerGroup.TRANSCRIPTION as TX
import org.cassini.ui.theme.Tone.Error as ERROR
import org.cassini.ui.theme.Tone.Info as INFO
import org.cassini.ui.theme.Tone.Ok as OK
import org.cassini.ui.theme.Tone.Plain as PLAIN
import org.cassini.ui.theme.Tone.Warn as WARN

/** The subsections of features.md section 13, which are also the columns of the States board. */
enum class BannerGroup { TRUST, RECORDING, TRANSCRIPTION, DOWNLOAD, LIBRARY }

/**
 * The banner catalogue (features.md section 13, board 10): every kind with its [tone], [title] and [body]
 * resources, the [BannerArgs] fields it [uses], its catalogue [actions] (first ink-filled, second outlined), whether
 * the app emits it ([appEmitted] is false for the catalogue-only TX_CALCULATING and TX_BATTERY) and whether the
 * user can [dismissible] it. [title] is a plurals resource for RECOVERED, REC_NEAR_LIMIT and TX_QUEUE_HELD; [body]
 * is 0 when the kind has no body. [BannerText] turns a kind and its arguments into text.
 */
@Suppress("LongParameterList") // One row of the catalogue table per constructor call.
enum class BannerKind(
    val tone: Tone,
    val group: BannerGroup,
    val title: Int,
    @StringRes val body: Int,
    val uses: Set<BannerArg> = emptySet(),
    actions: List<BannerAction> = emptyList(),
    val appEmitted: Boolean = true,
    val dismissible: Boolean = false,
) {
    // 13.1 Document trust
    TRUST_VERIFIED(OK, TRUST, R.string.ui_banner_trust_verified_title, R.string.ui_banner_trust_verified_body),
    TRUST_NOT_VERIFIED(
        WARN,
        TRUST,
        R.string.ui_banner_trust_not_verified_title,
        R.string.ui_banner_trust_not_verified_body,
        actions = listOf(PLAY_ANYWAY),
    ),
    TRUST_AUDIO_CHANGED(
        WARN,
        TRUST,
        R.string.ui_banner_trust_audio_changed_title,
        R.string.ui_banner_trust_audio_changed_body,
        actions = listOf(TRANSCRIBE_AGAIN),
    ),
    TRUST_UNREADABLE(
        ERROR,
        TRUST,
        R.string.ui_banner_trust_unreadable_title,
        R.string.ui_banner_trust_unreadable_body,
        actions = listOf(TRANSCRIBE_AGAIN),
    ),
    TRUST_NEWER_FORMAT(
        ERROR,
        TRUST,
        R.string.ui_banner_trust_newer_format_title,
        R.string.ui_banner_trust_newer_format_body,
    ),
    PLAIN_AUDIO(
        PLAIN,
        TRUST,
        R.string.ui_banner_plain_audio_title,
        R.string.ui_banner_plain_audio_body,
        actions = listOf(TRANSCRIBE),
    ),
    UNPLAYABLE(ERROR, TRUST, R.string.ui_banner_unplayable_title, R.string.ui_banner_unplayable_body),

    // 13.2 Recording
    REC_PAUSED_OTHER_APP(
        WARN,
        REC,
        R.string.ui_banner_rec_paused_other_app_title,
        R.string.ui_banner_rec_paused_other_app_body,
        actions = listOf(RESUME),
    ),
    REC_STORAGE_LOW(
        WARN,
        REC,
        R.string.ui_banner_rec_storage_low_title,
        R.string.ui_banner_rec_storage_low_body,
        uses = setOf(MS1),
    ),
    REC_NEAR_LIMIT(
        WARN,
        REC,
        R.plurals.ui_banner_rec_near_limit_title,
        R.string.ui_banner_rec_near_limit_body,
        uses = setOf(MS1),
    ),
    REC_STOPPED_STORAGE(
        WARN,
        REC,
        R.string.ui_banner_rec_stopped_storage_title,
        R.string.ui_banner_rec_stopped_storage_body,
        uses = setOf(BYTES),
        actions = listOf(OPEN_NOTE),
    ),
    REC_MIC_OFF(
        ERROR,
        REC,
        R.string.ui_banner_rec_mic_off_title,
        R.string.ui_banner_rec_mic_off_body,
        actions = listOf(OPEN_SETTINGS),
    ),
    REC_MIC_FAILED(
        ERROR,
        REC,
        R.string.ui_banner_rec_mic_failed_title,
        R.string.ui_banner_rec_mic_failed_body,
        actions = listOf(OPEN_NOTE),
    ),
    REC_COULD_NOT_START(
        ERROR,
        REC,
        R.string.ui_banner_rec_could_not_start_title,
        R.string.ui_banner_rec_could_not_start_body,
        actions = listOf(TRY_AGAIN),
    ),
    RECOVERED(
        INFO,
        REC,
        R.plurals.ui_banner_recovered_title,
        R.string.ui_banner_recovered_body,
        uses = setOf(COUNT, MS1),
        actions = listOf(OPEN),
        dismissible = true,
    ),

    // 13.3 Transcription
    TX_PAUSED(
        WARN,
        TX,
        R.string.ui_banner_tx_paused_title,
        R.string.ui_banner_tx_paused_body,
        uses = setOf(MS1, MS2),
        actions = listOf(RESUME, START_OVER),
    ),
    TX_PAUSED_EARLY(
        WARN,
        TX,
        R.string.ui_banner_tx_paused_early_title,
        R.string.ui_banner_tx_paused_early_body,
        actions = listOf(RESUME),
    ),
    TX_QUEUE_HELD(
        WARN,
        TX,
        R.plurals.ui_banner_tx_queue_held_title,
        R.string.ui_banner_tx_queue_held_body,
        uses = setOf(COUNT),
        actions = listOf(CONTINUE),
    ),
    TX_CALCULATING(
        INFO,
        TX,
        R.string.ui_banner_tx_calculating_title,
        R.string.ui_banner_tx_calculating_body,
        appEmitted = false,
    ),
    TX_BATTERY(INFO, TX, R.string.ui_banner_tx_battery_title, R.string.ui_banner_tx_battery_body, appEmitted = false),
    TX_ONE_VOICE(
        WARN,
        TX,
        R.string.ui_banner_tx_one_voice_title,
        R.string.ui_banner_tx_one_voice_body,
        actions = listOf(TRANSCRIBE_AGAIN),
    ),
    TX_CHECKPOINT_MISMATCH(
        ERROR,
        TX,
        R.string.ui_banner_tx_checkpoint_mismatch_title,
        R.string.ui_banner_tx_checkpoint_mismatch_body,
        actions = listOf(START_OVER),
    ),
    TX_MEMORY(
        ERROR,
        TX,
        R.string.ui_banner_tx_memory_title,
        R.string.ui_banner_tx_memory_body,
        actions = listOf(TRY_AGAIN),
    ),

    /** [BannerArgs.text] is the FailureMessages text; without it the body is only "Your recording is kept." */
    TX_FAILED(
        ERROR,
        TX,
        R.string.ui_banner_tx_failed_title,
        R.string.ui_banner_tx_failed_body,
        uses = setOf(TEXT),
        actions = listOf(TRY_AGAIN),
    ),

    // 13.4 Model download
    /** The title follows [BannerArgs.part] (speech recognition when null); the percent comes from the bytes when
     * [BannerArgs.percent] is null. */
    DL_DOWNLOADING(
        INFO,
        DL,
        R.string.ui_part_downloading_speech,
        R.string.ui_banner_dl_downloading_body,
        uses = setOf(PART, PERCENT, BYTES, BYTES2),
        actions = listOf(CANCEL),
    ),
    DL_CHECKING(
        INFO,
        DL,
        R.string.ui_banner_dl_checking_title,
        R.string.ui_banner_dl_checking_body,
        uses = setOf(PERCENT),
    ),
    DL_NO_CONNECTION(
        ERROR,
        DL,
        R.string.ui_banner_dl_no_connection_title,
        R.string.ui_banner_dl_no_connection_body,
        actions = listOf(RETRY),
    ),
    DL_STOPPED(
        ERROR,
        DL,
        R.string.ui_banner_dl_stopped_title,
        R.string.ui_banner_dl_stopped_body,
        uses = setOf(BY_SYSTEM),
        actions = listOf(RETRY),
    ),
    DL_NO_SPACE(
        ERROR,
        DL,
        R.string.ui_banner_dl_no_space_title,
        R.string.ui_banner_dl_no_space_body,
        uses = setOf(BYTES),
        actions = listOf(RETRY),
    ),
    DL_FILE_CHECK(
        ERROR,
        DL,
        R.string.ui_banner_dl_file_check_title,
        R.string.ui_banner_dl_file_check_body,
        actions = listOf(DOWNLOAD_AGAIN),
    ),
    DL_INSTALL_FAILED(
        ERROR,
        DL,
        R.string.ui_banner_dl_install_failed_title,
        R.string.ui_banner_dl_install_failed_body,
        actions = listOf(TRY_AGAIN),
    ),
    DL_RECORD_NOW(
        INFO,
        DL,
        R.string.ui_banner_dl_record_now_title,
        R.string.ui_banner_dl_record_now_body,
        actions = listOf(DOWNLOAD),
    ),

    // 13.5 Library and import
    LIB_EMPTY(
        PLAIN,
        LIB,
        R.string.ui_banner_lib_empty_title,
        R.string.ui_banner_lib_empty_body,
        actions = listOf(RECORD, IMPORT),
    ),
    LIB_NO_MATCH(
        PLAIN,
        LIB,
        R.string.ui_banner_lib_no_match_title,
        R.string.ui_banner_lib_no_match_body,
        uses = setOf(TEXT),
    ),
    IMPORTING(INFO, LIB, R.string.ui_banner_importing_title, 0, uses = setOf(TEXT)),
    IMPORT_TOO_LARGE(
        ERROR,
        LIB,
        R.string.ui_banner_import_too_large_title,
        R.string.ui_banner_import_too_large_body,
        actions = listOf(CHOOSE_ANOTHER),
    ),
    IMPORT_NO_SPACE(
        ERROR,
        LIB,
        R.string.ui_banner_import_no_space_title,
        R.string.ui_banner_import_no_space_body,
        uses = setOf(BYTES),
        actions = listOf(TRY_AGAIN),
    ),
    IMPORT_WAV(
        ERROR,
        LIB,
        R.string.ui_banner_import_wav_title,
        R.string.ui_banner_import_wav_body,
        actions = listOf(CHOOSE_ANOTHER),
    ),
    IMPORT_OPEN(
        ERROR,
        LIB,
        R.string.ui_banner_import_open_title,
        R.string.ui_banner_import_open_body,
        actions = listOf(CHOOSE_ANOTHER),
    ),

    /** The board's body names M4A ([BannerArgs.m4a] true); the Opus capture format drops "(M4A)". */
    NO_ENCODER(
        WARN,
        LIB,
        R.string.ui_banner_no_encoder_title,
        R.string.ui_banner_no_encoder_body_m4a,
        uses = setOf(M4A),
        dismissible = true,
    ),
    NOTIFICATIONS_OFF(
        INFO,
        LIB,
        R.string.ui_banner_notifications_off_title,
        R.string.ui_banner_notifications_off_body,
        actions = listOf(TURN_ON),
    ),
    VOICES_UNREADABLE(
        ERROR,
        LIB,
        R.string.ui_banner_voices_unreadable_title,
        R.string.ui_banner_voices_unreadable_body,
    ),

    /** The database did not open (a full disk, an I/O or permission error): the Library cannot list the notes. */
    LIB_UNAVAILABLE(
        ERROR,
        LIB,
        R.string.ui_banner_lib_unavailable_title,
        R.string.ui_banner_lib_unavailable_body,
    ),
    ;

    /** The catalogue actions, the default of [BannerSpec.actions]. */
    val actions: ImmutableList<BannerAction> = actions.toImmutableList()
}
