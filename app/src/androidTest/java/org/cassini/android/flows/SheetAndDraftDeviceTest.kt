package org.cassini.android.flows

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import org.cassini.test.CiSmoke
import org.cassini.ui.components.ProcessingCardUi
import org.cassini.ui.components.SpeakerNameUi
import org.cassini.ui.components.TurnUi
import org.cassini.ui.components.WordUi
import org.cassini.ui.components.turnText
import org.cassini.ui.format.BatteryUi
import org.cassini.ui.format.DayLabel
import org.cassini.ui.format.ProgressHeadline
import org.cassini.ui.format.StageUi
import org.cassini.ui.format.TimeLeftUi
import org.cassini.ui.note.NameSheetUi
import org.cassini.ui.note.NoteChipsUi
import org.cassini.ui.note.NoteMode
import org.cassini.ui.note.NoteScreen
import org.cassini.ui.note.NoteSheet
import org.cassini.ui.note.NoteSubtitleUi
import org.cassini.ui.note.NoteUiState
import org.cassini.ui.note.SaveState
import org.cassini.ui.note.TrustUi
import org.cassini.ui.note.VoiceOptionUi
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.SpeakerTone
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalTime

/**
 * The device follow-ups 3 and 4 of docs/ui-fidelity.md, on an edge-to-edge window as in MainActivity:
 *
 * - With the keyboard open, the Name a speaker sheet stays below the status bar and above the keyboard.
 * - New draft text on Transcribing scrolls only the draft, so the progress card stays in view.
 *
 * Each test saves a screenshot to `files/device-checks/` for a reviewer. When the device shows no soft keyboard (a
 * hardware keyboard), the sheet test still checks the status bar and logs that the keyboard did not open.
 */
@CiSmoke
class SheetAndDraftDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun edgeToEdge() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
    }

    @Test fun theNameSheetStaysBelowTheStatusBarWithTheKeyboardOpen() {
        rule.setContent { CassiniTheme(darkTheme = false) { NoteScreen(naming, onEvent = {}) } }
        rule.onNode(hasSetTextAction()).performClick()
        val ime = waitForKeyboard()
        rule.waitForIdle()
        val statusBar = insets().getInsets(WindowInsetsCompat.Type.statusBars()).top
        val sheet = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
            .fetchSemanticsNode().boundsInWindow
        shot("name-sheet-keyboard")
        Log.i(TAG, "Status bar $statusBar px, sheet ${sheet.top}..${sheet.bottom} px, keyboard shown: $ime")
        assertTrue("The sheet top ${sheet.top} is above the status bar ($statusBar px)", sheet.top >= statusBar)
        // The field keeps its place above the keyboard: it is still on the screen and not covered.
        rule.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    @Test fun newDraftTextKeepsTheProgressCardInView() {
        var state by mutableStateOf(processing(turns(TURNS)))
        rule.setContent { CassiniTheme(darkTheme = false) { NoteScreen(state, onEvent = {}) } }
        rule.waitForIdle()
        val newest = turn(TURNS, "Ultima frase della bozza, appena trascritta.")
        rule.runOnIdle { state = processing((turns(TURNS) + newest).toPersistentList()) }
        rule.waitForIdle()
        rule.onNode(hasText(turnText(newest.words))).assertIsDisplayed()
        rule.onNode(hasText(rule.activity.getString(org.cassini.ui.R.string.ui_action_stop_keep_progress)))
            .assertIsDisplayed()
        shot("transcribing-new-text")
    }

    private fun insets(): WindowInsetsCompat =
        requireNotNull(ViewCompat.getRootWindowInsets(rule.activity.window.decorView))

    /** Waits up to 5 s for the soft keyboard; true when it opened. */
    private fun waitForKeyboard(): Boolean {
        val end = SystemClock.uptimeMillis() + KEYBOARD_MS
        while (SystemClock.uptimeMillis() < end) {
            val report = shell("dumpsys input_method")
            if ("mInputShown=true" in report || "isInputViewShown=true" in report) return true
            SystemClock.sleep(POLL_MS)
        }
        return false
    }

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return automation.executeShellCommand(command).use { pipe ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().readText()
        }
    }

    private fun shot(name: String) {
        SystemClock.sleep(SETTLE_MS)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(rule.activity.filesDir, "device-checks").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        Log.i(TAG, "Saved ${File(dir, "$name.png")}")
    }

    private companion object {
        const val TAG = "SheetAndDraftDeviceTest"
        const val KEYBOARD_MS = 5_000L
        const val POLL_MS = 200L
        const val SETTLE_MS = 600L
        const val PNG_QUALITY = 100
        const val TURNS = 14
        const val MINUTE = 60_000L

        fun words(text: String, startMs: Long): ImmutableList<WordUi> =
            text.split(' ').mapIndexed { index, word -> WordUi(word, startMs + index * 400) }.toImmutableList()

        fun turn(index: Int, text: String) = TurnUi(
            id = "t$index",
            speakerId = "s${index % 2}",
            name = if (index % 2 == 0) SpeakerNameUi.Named("Giulia") else SpeakerNameUi.Placeholder(2),
            tone = if (index % 2 == 0) SpeakerTone.Blue else SpeakerTone.Amber,
            startMs = index * MINUTE,
            words = words(text, index * MINUTE),
        )

        fun turns(count: Int) = (0 until count).map { index ->
            turn(index, "Va bene, allora confermiamo le quantità e la consegna di giovedì per il fornitore.")
        }.toPersistentList()

        fun processing(turns: ImmutableList<TurnUi>) = NoteUiState(
            noteId = "weekly",
            title = "Weekly product sync",
            subtitle = NoteSubtitleUi(DayLabel.Today, LocalTime.of(10, 2), 48 * MINUTE),
            mode = NoteMode.Processing(
                ProcessingCardUi(
                    headline = ProgressHeadline.FINDING_WORDS_AND_SPEAKERS,
                    rangeStartMs = 29 * MINUTE,
                    rangeEndMs = 30 * MINUTE,
                    percent = 62,
                    stage = StageUi.WORDS_AND_VOICES,
                    skippedMatching = false,
                    timeLeft = TimeLeftUi.Range(115 * MINUTE, 115 * MINUTE),
                    doneMs = 29 * MINUTE,
                    totalMs = 48 * MINUTE,
                    battery = BatteryUi.Projected(now = 54, atFinish = 41),
                    words = 4_812,
                    lowMemory = false,
                ),
            ),
            turns = turns,
        )

        val naming = NoteUiState(
            noteId = "review",
            title = "Board review prep",
            mode = NoteMode.Reading,
            chips = NoteChipsUi(trust = TrustUi.VERIFIED, speakers = 2),
            turns = turns(2),
            sheet = NoteSheet.NameSpeaker(
                NameSheetUi(
                    speakerId = "s1",
                    label = SpeakerNameUi.Placeholder(1),
                    tone = SpeakerTone.Amber,
                    speakingMs = 26_000,
                    canSample = true,
                    name = "Anna",
                    voices = persistentListOf(
                        VoiceOptionUi(id = "anna", name = "Anna Ricci", likely = true),
                        VoiceOptionUi(id = "paolo", name = "Paolo Ferri"),
                        VoiceOptionUi(id = "marco", name = "Marco"),
                    ),
                    ranked = true,
                    samePerson = "Anna Ricci",
                    remember = true,
                    rememberEnabled = true,
                    canMerge = true,
                    save = SaveState.BLOCKED_BY_QUESTION,
                ),
            ),
        )
    }
}
