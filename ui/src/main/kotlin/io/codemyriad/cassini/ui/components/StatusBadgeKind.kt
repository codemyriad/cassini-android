package io.codemyriad.cassini.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.format.Counts
import io.codemyriad.cassini.ui.format.Durations

internal enum class StatusBadgeTone { Red, Neutral, Amber, Error, Outline }

internal enum class StatusBadgeGlyph { Dot, Spinner, Alert, Incoming, Clock, Pause, Wave, Check }

/** L-26 presentation only; the app remains responsible for deriving a meeting's state. */
enum class StatusBadgeKind(
    @StringRes val label: Int,
    internal val tone: StatusBadgeTone,
    internal val glyph: StatusBadgeGlyph,
) {
    Recording(R.string.ui_badge_recording, StatusBadgeTone.Red, StatusBadgeGlyph.Dot),
    Importing(R.string.ui_badge_importing, StatusBadgeTone.Neutral, StatusBadgeGlyph.Spinner),
    Saving(R.string.ui_badge_saving, StatusBadgeTone.Neutral, StatusBadgeGlyph.Spinner),
    Transcribing(R.string.ui_badge_transcribing, StatusBadgeTone.Neutral, StatusBadgeGlyph.Spinner),
    Attention(R.string.ui_badge_attention, StatusBadgeTone.Amber, StatusBadgeGlyph.Alert),
    Interrupted(R.string.ui_badge_interrupted, StatusBadgeTone.Amber, StatusBadgeGlyph.Alert),
    Received(R.string.ui_badge_received, StatusBadgeTone.Outline, StatusBadgeGlyph.Incoming),
    Queued(R.string.ui_badge_queued, StatusBadgeTone.Neutral, StatusBadgeGlyph.Clock),
    Paused(R.string.ui_badge_paused, StatusBadgeTone.Amber, StatusBadgeGlyph.Pause),
    Failed(R.string.ui_badge_failed, StatusBadgeTone.Error, StatusBadgeGlyph.Alert),
    AudioOnly(R.string.ui_badge_audio_only, StatusBadgeTone.Neutral, StatusBadgeGlyph.Wave),
    Unplayable(R.string.ui_badge_unplayable, StatusBadgeTone.Error, StatusBadgeGlyph.Alert),
    Unreadable(R.string.ui_badge_unreadable, StatusBadgeTone.Error, StatusBadgeGlyph.Alert),
    NewerFormat(R.string.ui_badge_newer, StatusBadgeTone.Outline, StatusBadgeGlyph.Alert),
    Transcribed(R.string.ui_status_transcribed, StatusBadgeTone.Neutral, StatusBadgeGlyph.Check),
}

/** Shared badge text only; detailed progress copy remains outside the badge. */
@Composable
internal fun statusBadgeText(
    kind: StatusBadgeKind,
    liveMs: Long = 0,
    minutes: Long? = null,
    positionMs: Long = 0,
): String = when (kind) {
    StatusBadgeKind.Recording -> stringResource(kind.label, Durations.clock(liveMs))
    StatusBadgeKind.Paused -> stringResource(kind.label, Durations.clock(positionMs))
    StatusBadgeKind.Transcribing -> when {
        minutes == null || minutes < 0 -> stringResource(kind.label)
        minutes == 0L -> stringResource(R.string.ui_badge_transcribing_under_minute)
        else -> pluralStringResource(
            R.plurals.ui_badge_transcribing_eta,
            minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            Counts.number(minutes),
        )
    }
    else -> stringResource(kind.label)
}
