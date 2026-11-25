@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.banner.BannerAction
import io.codemyriad.cassini.ui.banner.BannerKind
import io.codemyriad.cassini.ui.banner.BannerSpec
import io.codemyriad.cassini.ui.components.Banner
import io.codemyriad.cassini.ui.components.HelperText
import io.codemyriad.cassini.ui.components.SectionHeader
import io.codemyriad.cassini.ui.components.screenContentWidth
import io.codemyriad.cassini.ui.format.Counts
import io.codemyriad.cassini.ui.format.Durations
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

/** D103: a short map, the choices, then source and licence details. */
@Composable
@Suppress("LongParameterList") // The optional ZIP action and the extra row are owned by :app controllers.
fun SettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
    initialPage: SettingsPage = SettingsPage.MAP,
    exportEnabled: Boolean = false,
    onExport: () -> Unit = {},
    extraRow: SettingsExtraRow? = null,
    onExtraRow: () -> Unit = {},
) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    val back = {
        when (page) {
            SettingsPage.MAP -> onEvent(SettingsEvent.Back)
            SettingsPage.BACKUP_DETAILS -> page = SettingsPage.BACKUP
            else -> page = SettingsPage.MAP
        }
    }
    BackHandler(page != SettingsPage.MAP, onBack = back)
    key(page) {
        Column(
            modifier.fillMaxSize().background(CassiniTheme.colors.bg)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .screenContentWidth()
                .navigationBarsPadding(),
        ) {
            SettingsTopBar(
                stringResource(page.title),
                onBack = back,
                backLabel = stringResource(page.backLabel),
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                when (page) {
                    SettingsPage.MAP -> SettingsMap(state, onEvent, extraRow, onExtraRow) { page = it }
                    SettingsPage.TRANSCRIPTION -> TranscriptionSettings(state, onEvent)
                    SettingsPage.SPEAKERS -> SpeakerSettings(state, onEvent)
                    SettingsPage.BACKUP -> state.backup?.let {
                        BackupSection(it, onEvent, { page = SettingsPage.BACKUP_DETAILS }, exportEnabled, onExport)
                    }
                    SettingsPage.BACKUP_DETAILS -> state.backup?.let { BackupDetails(it) }
                    SettingsPage.ABOUT -> AboutSettings(state, onEvent)
                }
            }
        }
    }
}

@Composable
private fun SettingsMap(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    extraRow: SettingsExtraRow?,
    onExtraRow: () -> Unit,
    open: (SettingsPage) -> Unit,
) {
    SectionHeader(stringResource(R.string.ui_settings_transcripts), first = true)
    SettingsGroup {
        MapRow(
            stringResource(R.string.ui_transcription_title),
            stringResource(
                if (state.speechModel == SpeechModelUi.INCLUDED) R.string.ui_model_whistle else R.string.ui_model_parakeet,
            ),
            Glyph.Wave,
        ) { open(SettingsPage.TRANSCRIPTION) }
        MapRow(
            stringResource(R.string.ui_speakers_title),
            stringResource(if (state.tellSpeakersApart) R.string.ui_speaker_on else R.string.ui_setting_off),
            Glyph.People,
        ) { open(SettingsPage.SPEAKERS) }
        MapRow(
            stringResource(R.string.ui_settings_voices),
            Counts.plural(R.plurals.ui_settings_people, state.voices),
            Glyph.People,
        ) {
            onEvent(SettingsEvent.OpenVoices)
        }
    }
    HelperText(
        stringResource(R.string.ui_first_run_privacy_phone) + " " +
            stringResource(R.string.ui_first_run_privacy_backups),
    )
    SectionHeader(stringResource(R.string.ui_settings_meetings))
    SettingsGroup {
        MapRow(stringResource(R.string.ui_settings_backup), backupAnswer(state.backup), Glyph.Shield) {
            open(SettingsPage.BACKUP)
        }
        MapRow(
            stringResource(R.string.ui_settings_trash),
            Counts.plural(R.plurals.ui_settings_meeting_count, state.trash),
            Glyph.Trash,
        ) {
            onEvent(SettingsEvent.OpenTrash)
        }
    }
    HelperText(
        stringResource(
            R.string.ui_settings_library_stats,
            Counts.plural(R.plurals.ui_settings_meeting_count, state.meetings),
            libraryAudioDuration(state.audioDurationMs),
        ),
    )
    if (extraRow != null) ExtraRow(extraRow, onExtraRow)
    SectionHeader(stringResource(R.string.ui_settings_app))
    LanguageSection(state.language, onEvent)
    if (state.notificationsOff) {
        Banner(
            BannerSpec(BannerKind.NOTIFICATIONS_OFF),
            onAction = { if (it == BannerAction.TURN_ON) onEvent(SettingsEvent.TurnOnNotifications) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
    AboutRow(state.version) { open(SettingsPage.ABOUT) }
}

@Composable
private fun ExtraRow(row: SettingsExtraRow, onClick: () -> Unit) {
    SettingsGroup(Modifier.padding(top = 12.dp)) {
        MapRow(row.title, row.answer, Glyph.PhoneTransfer, onClick = onClick)
    }
}

@Composable
private fun backupAnswer(backup: BackupSettingsUi?): String = when {
    backup == null -> stringResource(R.string.ui_settings_model_checking)
    backup.cloudOn || backup.transferOn -> stringResource(R.string.ui_setting_on)
    else -> stringResource(R.string.ui_setting_off)
}

@Composable
private fun LanguageSection(current: LanguageChoice, onEvent: (SettingsEvent) -> Unit) {
    var sheet by rememberSaveable { mutableStateOf(false) }
    if (sheet) {
        LanguageSheet(
            current,
            onPick = {
                sheet = false
                onEvent(SettingsEvent.Language(it))
            },
            onDismiss = { sheet = false },
        )
    }
    SettingsGroup {
        MapRow(stringResource(R.string.ui_settings_language_label), languageName(current), Glyph.Language) {
            sheet = true
        }
    }
}

/** Catalog settings.phone.audioHours: whole hours from one hour, HARMONY §6 duration below it. */
@Composable
private fun libraryAudioDuration(ms: Long): String = if (ms < 3_600_000L) {
    Durations.roughMinutes(ms)
} else {
    Counts.plural(R.plurals.ui_settings_audio_hours, (ms / 3_600_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
}

private val SettingsPage.title: Int
    get() = when (this) {
        SettingsPage.MAP -> R.string.ui_settings_title
        SettingsPage.TRANSCRIPTION -> R.string.ui_transcription_title
        SettingsPage.SPEAKERS -> R.string.ui_speakers_title
        SettingsPage.BACKUP -> R.string.ui_settings_backup
        SettingsPage.BACKUP_DETAILS -> R.string.ui_backup_more_detail
        SettingsPage.ABOUT -> R.string.ui_about_title
    }

private val SettingsPage.backLabel: Int
    get() = when (this) {
        SettingsPage.MAP -> R.string.ui_back_to_library
        SettingsPage.BACKUP_DETAILS -> R.string.ui_back
        else -> R.string.ui_back_to_settings
    }
