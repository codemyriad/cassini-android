package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.cassini.ui.BoardTest
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.banner.BannerSpec
import org.cassini.ui.banner.boardArgs
import org.cassini.ui.format.StageUi
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.SpeakerTone
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The batch-2 components in their states, one 390 dp strip per group and theme (docs/design/ui.md section 10.2):
 * components/{transcript,processing,player,recording,forms}-{light,dark}.png. The Recording widgets use the dark
 * Recording palette in both themes. The window is 3200 dp high; the capture is the content's own height.
 */
@Config(qualifiers = "+h3200dp")
class ContentScreenshotTest : BoardTest() {
    @Test fun transcriptLight() = board("components", "transcript-light") { TranscriptStrip() }

    @Test
    @Config(qualifiers = "+night")
    fun transcriptDark() = board("components", "transcript-dark", dark = true) { TranscriptStrip() }

    @Test fun processingLight() = board("components", "processing-light") { ProcessingStrip() }

    @Test
    @Config(qualifiers = "+night")
    fun processingDark() = board("components", "processing-dark", dark = true) { ProcessingStrip() }

    @Test fun playerLight() = board("components", "player-light") { PlayerStrip() }

    @Test
    @Config(qualifiers = "+night")
    fun playerDark() = board("components", "player-dark", dark = true) { PlayerStrip() }

    @Test fun recordingLight() = board("components", "recording-light") { RecordingStrip() }

    @Test
    @Config(qualifiers = "+night")
    fun recordingDark() = board("components", "recording-dark", dark = true) { RecordingStrip() }

    @Test fun formsLight() = board("components", "forms-light") { FormsStrip() }

    @Test
    @Config(qualifiers = "+night")
    fun formsDark() = board("components", "forms-dark", dark = true) { FormsStrip() }

    /** Read turns (named, auto with the active word, placeholder), a wrapped active word, search, the draft. */
    @Composable
    private fun TranscriptStrip() = Strip {
        Label("Read: TurnHeader + TurnParagraph (named; auto, active “quindi”; placeholder, not renamable)")
        Pane {
            TurnHeader("Giulia", SpeakerTone.Blue, 692_000, auto = false, onClick = {})
            TurnParagraph(TranscriptFixtures.giulia, activeIndex = null, onWordTap = {})
            TurnHeader("Paolo Ferri", SpeakerTone.Teal, 701_000, auto = true, onClick = {})
            TurnParagraph(TranscriptFixtures.paolo, activeIndex = TranscriptFixtures.PAOLO_ACTIVE, onWordTap = {})
            TurnHeader("Speaker 3", SpeakerTone.Amber, 740_000, auto = false, onClick = null)
            TurnParagraph(TranscriptFixtures.speaker3, activeIndex = null, onWordTap = {}, placeholder = true)
        }
        Label("Active word that wraps (160 dp column); search matches, the current one in 600")
        Pane {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.width(160.dp)) {
                    TurnParagraph(TranscriptFixtures.wrapped, TranscriptFixtures.WRAPPED_ACTIVE, onWordTap = {})
                }
                Box(Modifier.weight(1f)) {
                    TurnParagraph(
                        words = TranscriptFixtures.paolo,
                        activeIndex = null,
                        onWordTap = {},
                        matches = TurnMatches(
                            ranges = TranscriptFixtures.matchesOf(TranscriptFixtures.paolo, "consegna"),
                            current = 1,
                        ),
                    )
                }
            }
        }
        DraftSection()
    }

    @Composable
    private fun DraftSection() {
        Label("Draft: header, settled text3, SuggestionCard, unsettled text4 from word 9")
        Column(
            Modifier
                .background(CassiniTheme.colors.bg)
                .padding(horizontal = 20.dp),
        ) {
            TurnHeader("Giulia", SpeakerTone.Blue, 1_711_000, auto = false, onClick = {})
            TurnParagraph(TranscriptFixtures.draftGiulia, null, {}, mode = TurnMode.Draft)
            Box(Modifier.padding(top = 14.dp)) {
                SuggestionCard("Speaker 2", SpeakerTone.Amber, 1_742_000, "Luca", onYes = {}, onNo = {}, onSpeaker = {})
            }
            TurnParagraph(
                words = TranscriptFixtures.draftAfterCard,
                activeIndex = null,
                onWordTap = {},
                modifier = Modifier.padding(top = 6.dp),
                mode = TurnMode.Draft,
                unsettledFrom = TranscriptFixtures.DRAFT_UNSETTLED_FROM,
            )
        }
    }

    /** The Transcribing card: board 02, starting, saving with Matching skipped, downloading; the stepper; queued. */
    @Composable
    private fun ProcessingStrip() = Strip {
        Label("ProcessingCard: board 02; starting (pending stats, low memory, notifications off)")
        Padded(gap = 12) {
            ProcessingCard(TranscriptFixtures.boardCard, onStop = {})
            ProcessingCard(TranscriptFixtures.startingCard, onStop = {}, notificationsOff = true)
        }
        Label("Saving with Matching skipped (busy stop); downloading replaces the stats")
        Padded(gap = 12) {
            ProcessingCard(TranscriptFixtures.savingCard, onStop = {}, stopBusy = true)
            ProcessingCard(TranscriptFixtures.startingCard.copy(lowMemory = false), onStop = {}) {
                Banner(BannerSpec(BannerKind.DL_DOWNLOADING, boardArgs(BannerKind.DL_DOWNLOADING)), onAction = {})
            }
        }
        StepperSection()
    }

    @Composable
    private fun StepperSection() {
        Label("StageTracker: each stage current; Matching skipped while Reading and while Saving")
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .background(CassiniTheme.colors.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StageUi.entries.forEach { StageTracker(it, skippedMatching = false) }
            StageTracker(StageUi.READING, skippedMatching = true)
            StageTracker(StageUi.SAVING, skippedMatching = true)
        }
        Label("QueuedCard: 2 ahead; after the current transcription")
        Padded {
            QueuedCard(ahead = 2, onRemove = {})
            QueuedCard(ahead = 0, onRemove = {})
        }
    }

    /** The player playing and paused, and the slider at three positions. */
    @Composable
    private fun PlayerStrip() = Strip {
        Label("PlaybackBar: playing at 11:44 of 23:50; paused at 0:00")
        Player(positionMs = 704_000, playing = true)
        Box(Modifier.padding(top = 8.dp)) { Player(positionMs = 0, playing = false) }
        Label("CassiniSlider at 0, half and the end; FollowChip")
        Padded(gap = 0) {
            CassiniSlider(0, 1_430_000, onSeek = {})
            CassiniSlider(715_000, 1_430_000, onSeek = {})
            CassiniSlider(1_430_000, 1_430_000, onSeek = {})
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            FollowChip("Follow playback", onClick = {})
        }
    }

    @Composable
    private fun Player(positionMs: Long, playing: Boolean) {
        PlaybackBar(
            positionMs = positionMs,
            durationMs = 1_430_000,
            playing = playing,
            onSeek = {},
            onSkipBack = {},
            onSkipForward = {},
            onPlayPause = {},
        )
    }

    /** The Recording widgets, recording and paused, on the Recording background. */
    @Composable
    private fun RecordingStrip() = Strip {
        Label("RecordingStatus, FormatBadge, RecordingTimer, Waveform, RecordingControls: recording, then paused")
        RecordingPane(paused = false, elapsedMs = 1_447_000)
        RecordingPane(paused = true, elapsedMs = 3_723_000)
    }

    @Composable
    private fun RecordingPane(paused: Boolean, elapsedMs: Long) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(CassiniTheme.recording.bg)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RecordingStatus(paused = paused)
                FormatBadge("Opus 48 kHz")
            }
            RecordingTimer(elapsedMs)
            Waveform(boardWaveformLevels())
            RecordingControls(paused = paused, onLibrary = {}, onPauseResume = {}, onFinish = {})
        }
    }

    /** Text fields, checkboxes and snackbars. */
    @Composable
    private fun FormsStrip() = Strip {
        Label("CassiniTextField: unfocused, focused look (showFocus), error, empty")
        Pane(gap = 16) {
            CassiniTextField("Anna", onValueChange = {}, label = "Name")
            CassiniTextField("Anna", onValueChange = {}, label = "Name", showFocus = true)
            CassiniTextField("", onValueChange = {}, label = "Name", error = "Enter a name.")
        }
        Label("CassiniCheckboxRow: checked with subtitle, unchecked, disabled")
        Pane(gap = 12) {
            CassiniCheckboxRow(
                checked = true,
                onCheckedChange = {},
                title = "Remember this voice on this phone",
                subtitle = "Future notes will name Anna automatically. The voiceprint never leaves the phone.",
            )
            CassiniCheckboxRow(checked = false, onCheckedChange = {}, title = "Remember this voice on this phone")
            CassiniCheckboxRow(
                checked = false,
                onCheckedChange = {},
                title = "Remember this voice on this phone",
                subtitle = "Too little speech to remember this voice.",
                enabled = false,
            )
        }
        Label("CassiniSnackbar: with Undo; message only; a long message")
        Padded {
            CassiniSnackbar("“Call with supplier” moved to trash", actionLabel = "Undo")
            CassiniSnackbar("Saved “Call with supplier.opus”")
            CassiniSnackbar(
                "“Interview — Marta, second part with the supplier and the logistics team” moved to trash",
                actionLabel = "Undo",
            )
        }
    }

    /** A surface pane with the transcript's 20 dp side padding. */
    @Composable
    private fun Pane(gap: Int = 0, content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CassiniTheme.colors.surface)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(gap.dp),
            content = content,
        )
    }
}
