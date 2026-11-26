@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.HelperText
import io.codemyriad.cassini.ui.theme.Glyph

/**
 * Settings › Experimental features (D-1178): previews that people reach only after eight taps on the version in About.
 * "Voice recorder" turns the recorder integration on and shows its Recorder row ([recorderRow], owned by `:app`);
 * the last switch locks the features again, turning every one of them off. The page stays open after that, so the
 * switch can go back on; the map hides its row once the page is left.
 */
@Composable
internal fun ExperimentalSettings(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    recorderRow: SettingsExtraRow?,
    onRecorderRow: () -> Unit,
) {
    HelperText(stringResource(R.string.ui_experimental_note), Modifier.padding(top = 4.dp, bottom = 8.dp))
    SettingsGroup {
        BackupSwitch(
            stringResource(R.string.ui_experimental_voice_recorder),
            body = stringResource(R.string.ui_experimental_voice_recorder_subtitle),
            checked = state.voiceRecorderEnabled,
            enabled = state.experimentalUnlocked,
        ) { onEvent(SettingsEvent.VoiceRecorder(it)) }
        if (state.voiceRecorderEnabled && recorderRow != null) {
            MapRow(recorderRow.title, recorderRow.answer, Glyph.PhoneTransfer, onClick = onRecorderRow)
        }
    }
    SettingsGroup(Modifier.padding(top = 22.dp)) {
        BackupSwitch(
            stringResource(R.string.ui_experimental_title),
            body = stringResource(R.string.ui_experimental_switch_subtitle),
            checked = state.experimentalUnlocked,
            enabled = true,
        ) { onEvent(SettingsEvent.ExperimentalFeatures(it)) }
    }
}

/** The Experimental features row of the map, above About; only while they are unlocked. */
@Composable
internal fun ExperimentalRow(state: SettingsUiState, onClick: () -> Unit) {
    SettingsGroup(Modifier.padding(top = 12.dp)) {
        MapRow(
            stringResource(R.string.ui_experimental_title),
            stringResource(if (state.voiceRecorderEnabled) R.string.ui_setting_on else R.string.ui_setting_off),
            Glyph.Settings,
            subtitle = stringResource(R.string.ui_experimental_subtitle),
            onClick = onClick,
        )
    }
}

/**
 * The taps on the version line of About that unlock Experimental features, as Android's build number unlocks its
 * developer options: [UNLOCK_TAPS] taps in a row, each within [TAP_RESET_MS] of the last. Taps 5–7 count down, the
 * eighth unlocks; once unlocked, a tap only says so and never locks them again.
 */
object ExperimentalTaps {
    const val UNLOCK_TAPS = 8
    const val FIRST_COUNTDOWN_TAP = 5
    const val TAP_RESET_MS = 3_000L

    /** What a tap shows. */
    sealed interface Feedback {
        data object None : Feedback

        data class TapsAway(val taps: Int) : Feedback

        data object Unlocked : Feedback

        data object AlreadyUnlocked : Feedback
    }

    /** The streak after a tap on top of [streak] taps, and what to show. [unlocked]: the features are unlocked. */
    fun tap(streak: Int, unlocked: Boolean): Pair<Int, Feedback> {
        if (unlocked) return 0 to Feedback.AlreadyUnlocked
        val next = streak + 1
        return when {
            next >= UNLOCK_TAPS -> 0 to Feedback.Unlocked
            next >= FIRST_COUNTDOWN_TAP -> next to Feedback.TapsAway(UNLOCK_TAPS - next)
            else -> next to Feedback.None
        }
    }
}
