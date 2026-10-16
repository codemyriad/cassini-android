package org.cassini.android.flows

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.cassini.test.CiSmoke
import org.cassini.ui.components.NoteRowUi
import org.cassini.ui.components.NoteStatusUi
import org.cassini.ui.components.ProcessingCardUi
import org.cassini.ui.components.SpeakerNameUi
import org.cassini.ui.components.SuggestionUi
import org.cassini.ui.components.TurnUi
import org.cassini.ui.components.WaveformLevels
import org.cassini.ui.components.WordRef
import org.cassini.ui.components.WordUi
import org.cassini.ui.format.BatteryUi
import org.cassini.ui.format.DayLabel
import org.cassini.ui.format.ModelPartUi
import org.cassini.ui.format.ProgressHeadline
import org.cassini.ui.format.StageUi
import org.cassini.ui.format.TimeLeftUi
import org.cassini.ui.library.DaySection
import org.cassini.ui.library.LibraryScreen
import org.cassini.ui.library.LibraryUiState
import org.cassini.ui.library.ProgressBannerUi
import org.cassini.ui.library.ProgressLine
import org.cassini.ui.note.NoteChipsUi
import org.cassini.ui.note.NoteMode
import org.cassini.ui.note.NoteScreen
import org.cassini.ui.note.NoteSubtitleUi
import org.cassini.ui.note.NoteUiState
import org.cassini.ui.note.PlaybackUi
import org.cassini.ui.note.TrustUi
import org.cassini.ui.recording.CaptureFormat
import org.cassini.ui.recording.RecordingHint
import org.cassini.ui.recording.RecordingPhase
import org.cassini.ui.recording.RecordingScreen
import org.cassini.ui.recording.RecordingUiState
import org.cassini.ui.settings.LanguageChoice
import org.cassini.ui.settings.ModelRowUi
import org.cassini.ui.settings.ModelStatusUi
import org.cassini.ui.settings.SettingsScreen
import org.cassini.ui.settings.SettingsUiState
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.SpeakerTone
import org.junit.Rule
import org.junit.Test
import java.time.LocalTime
import org.cassini.ui.R as UiR

/**
 * The Accessibility Test Framework checks of features.md section 14 on the Library, the Note screen (reading and
 * processing), Recording and Settings, each in states close to its board. They run on a device because the checks
 * skip Compose content under Robolectric (P2). [accessibilityValidator] fails on every ERROR or WARNING except the
 * small touch targets of inline word links and of the Library row suffixes.
 */
@CiSmoke
class ScreenAccessibilityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun check(inline: Set<String>, idleBars: Set<String> = emptySet(), content: @Composable () -> Unit) {
        rule.enableAccessibilityChecks(accessibilityValidator(inline, idleBars))
        rule.setContent { CassiniTheme(darkTheme = false, content = content) }
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    private fun text(id: Int): String = rule.activity.getString(id)

    @Test fun library() {
        val suffixes = setOf(text(UiR.string.ui_action_resume), text(UiR.string.ui_action_try_again))
        check(suffixes) { LibraryScreen(library, onEvent = {}) }
    }

    @Test fun noteReading() = check(words(reading.turns)) { NoteScreen(reading, onEvent = {}) }

    @Test fun noteProcessing() = check(words(processing.turns)) { NoteScreen(processing, onEvent = {}) }

    @Test fun recording() {
        val waveform = setOf(text(UiR.string.ui_recording_waveform))
        check(emptySet(), waveform) { RecordingScreen(recording, onEvent = {}) }
    }

    @Test fun settings() = check(emptySet()) { SettingsScreen(settings, onEvent = {}) }

    private fun words(turns: List<TurnUi>): Set<String> = turns.flatMap { turn -> turn.words.map { it.text } }.toSet()

    private companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val MIB = 1_048_576L

        fun words(text: String, startMs: Long): ImmutableList<WordUi> =
            text.split(' ').mapIndexed { index, word -> WordUi(word, startMs + index * 400) }.toImmutableList()

        fun row(id: String, title: String, status: NoteStatusUi) =
            NoteRowUi(id = id, title = title, durationMs = 48 * MINUTE, time = LocalTime.of(10, 2), status = status)

        val library = LibraryUiState(
            progress = ProgressBannerUi(
                noteId = "weekly",
                title = "Weekly product sync",
                percent = 62,
                progress = 0.62f,
                line = ProgressLine.TimeLeft(TimeLeftUi.Range(6 * MINUTE, 9 * MINUTE)),
                queued = 1,
            ),
            // Every row fits above the bottom bar: a row under the bar's fade would give a false contrast finding.
            sections = persistentListOf(
                DaySection(
                    DayLabel.Today,
                    persistentListOf(
                        row("weekly", "Weekly product sync", NoteStatusUi.Transcribing).copy(highlighted = true),
                    ),
                ),
                DaySection(
                    DayLabel.Yesterday,
                    persistentListOf(
                        row("review", "Board review prep", NoteStatusUi.Paused(atMs = 12 * MINUTE)),
                        row("failed", "Team call", NoteStatusUi.Failed),
                    ),
                ),
            ),
        )

        val giulia = TurnUi(
            id = "t1",
            speakerId = "s1",
            name = SpeakerNameUi.Named("Giulia"),
            tone = SpeakerTone.Blue,
            startMs = 11 * MINUTE,
            words = words("Ok, then let's confirm the quantities first.", 11 * MINUTE),
        )
        val paolo = TurnUi(
            id = "t2",
            speakerId = "s2",
            name = SpeakerNameUi.Named("Paolo Ferri"),
            tone = SpeakerTone.Teal,
            startMs = 11 * MINUTE + 41 * SECOND,
            auto = true,
            words = words("Sì, quattrocento. Il problema è la seconda consegna.", 11 * MINUTE + 41 * SECOND),
        )
        val speaker3 = TurnUi(
            id = "t3",
            speakerId = "s3",
            name = SpeakerNameUi.Placeholder(3),
            tone = SpeakerTone.Amber,
            startMs = 12 * MINUTE + 20 * SECOND,
            words = words("Can you send that in writing?", 12 * MINUTE + 20 * SECOND),
        )

        val reading = NoteUiState(
            noteId = "supplier",
            title = "Call with supplier",
            mode = NoteMode.Reading,
            chips = NoteChipsUi(trust = TrustUi.VERIFIED, speakers = 3),
            turns = persistentListOf(giulia, paolo, speaker3),
            activeWord = WordRef(paolo.id, 2),
            playback = PlaybackUi(positionMs = 11 * MINUTE + 44 * SECOND, durationMs = 23 * MINUTE, playing = true),
        )

        val processing = NoteUiState(
            noteId = "weekly",
            title = "Weekly product sync",
            subtitle = NoteSubtitleUi(DayLabel.Today, LocalTime.of(10, 2), 48 * MINUTE),
            mode = NoteMode.Processing(
                ProcessingCardUi(
                    headline = ProgressHeadline.FINDING_WORDS_AND_SPEAKERS,
                    rangeStartMs = 29 * MINUTE,
                    rangeEndMs = 29 * MINUTE + 45 * SECOND,
                    percent = 62,
                    stage = StageUi.WORDS_AND_VOICES,
                    skippedMatching = false,
                    timeLeft = TimeLeftUi.Range(6 * MINUTE, 9 * MINUTE),
                    doneMs = 29 * MINUTE + 52 * SECOND,
                    totalMs = 48 * MINUTE,
                    battery = BatteryUi.Projected(now = 54, atFinish = 41),
                    words = 4_812,
                    lowMemory = false,
                ),
            ),
            turns = persistentListOf(
                giulia,
                speaker3.copy(
                    name = SpeakerNameUi.Placeholder(2),
                    unsettledFrom = 0,
                    suggestion = SuggestionUi(voiceId = "luca", name = "Luca"),
                ),
            ),
        )

        val recording = RecordingUiState(
            phase = RecordingPhase.Recording,
            title = "Weekly product sync",
            elapsedMs = 24 * MINUTE + 7 * SECOND,
            levels = WaveformLevels(List(56) { index -> (index % 7) / 7f }.toImmutableList()),
            format = CaptureFormat.OPUS,
            sampleRateHz = 48_000,
            hint = RecordingHint.Space(31 * 60 * MINUTE),
        )

        val settings = SettingsUiState(
            language = LanguageChoice.PHONE,
            models = persistentListOf(
                ModelRowUi(ModelPartUi.SPEECH, 640 * MIB, ModelStatusUi.Ready),
                ModelRowUi(ModelPartUi.SPEAKERS, 62 * MIB, ModelStatusUi.Downloading(46)),
                ModelRowUi(ModelPartUi.VOICES, 28 * MIB, ModelStatusUi.NotDownloaded),
            ),
            voices = 4,
            trash = 2,
            version = "0.2.0",
            notificationsOff = true,
        )
    }
}
