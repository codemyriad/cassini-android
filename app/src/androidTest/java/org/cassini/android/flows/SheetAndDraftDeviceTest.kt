package org.cassini.android.flows

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
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
import kotlin.math.abs

/**
 * The device follow-ups 3 and 4 of docs/ui-fidelity.md, on an edge-to-edge window as in MainActivity:
 *
 * - With the keyboard open, the Name a speaker sheet stays below the status bar and above the keyboard, and at font
 *   scale 1.3 its Cancel and Save name buttons stay fully above the keyboard.
 * - New draft text on Transcribing scrolls only the draft, so the progress card stays in view.
 *
 * Each test saves a screenshot to `files/device-checks/` for a reviewer. The sheet tests set
 * `show_ime_with_hard_keyboard` for their run, so that the soft keyboard opens also on an emulator with a hardware
 * keyboard, and they fail when the keyboard does not open. The font scale test sets the system `font_scale` for its
 * run.
 */
@CiSmoke
class SheetAndDraftDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun edgeToEdge() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
    }

    @Test fun theNameSheetStaysBelowTheStatusBarWithTheKeyboardOpen() = withNameSheetAndKeyboard(
        shotName = "name-sheet-keyboard",
    ) { sheet ->
        assertTrue("The sheet top ${sheet.sheet.top} is above the status bar", sheet.sheet.top >= sheet.statusBar)
        // The field stays above the keyboard: its bottom is not below the top of the keyboard.
        val field = rule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInWindow
        assertTrue(
            "The field bottom ${field.bottom} is below the keyboard top (${sheet.keyboardTop} px)",
            field.bottom <= sheet.keyboardTop,
        )
    }

    /**
     * At font scale 1.3 the content is taller than the space above the keyboard (a Pixel 8 cut Cancel and Save name
     * at the keyboard edge): the footer stays below the scrolling part, so both buttons are fully on screen.
     */
    @Test fun theNameSheetButtonsStayAboveTheKeyboardAtFontScale13() = withNameSheetAndKeyboard(
        shotName = "name-sheet-keyboard-font-1.3",
        fontScale = LARGE_FONT_SCALE,
    ) { sheet ->
        assertTrue("The sheet top ${sheet.sheet.top} is above the status bar", sheet.sheet.top >= sheet.statusBar)
        val labels = listOf(org.cassini.ui.R.string.ui_action_cancel, org.cassini.ui.R.string.ui_name_save)
        for (label in labels.map(rule.activity::getString)) {
            val node = rule.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode()
            val shown = node.boundsInWindow
            Log.i(TAG, "$label: ${shown.top}..${shown.bottom} px of ${node.size.height} px")
            // boundsInWindow is clipped by the parents: a button cut by the sheet edge is shorter than its size.
            assertTrue("$label is cut: ${shown.height} of ${node.size.height} px", shown.height >= node.size.height - 1)
            assertTrue(
                "$label bottom ${shown.bottom} is below the keyboard top (${sheet.keyboardTop} px)",
                shown.bottom <= sheet.keyboardTop,
            )
            assertTrue("$label is above the sheet top", shown.top >= sheet.sheet.top)
        }
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

    /** Where the open sheet is, in px of its window. */
    private data class SheetWithKeyboard(val statusBar: Int, val sheet: Rect, val keyboardTop: Int)

    /**
     * Shows the Name a speaker sheet, opens the soft keyboard on its Name field, then runs [check]. With [fontScale]
     * the system font scale is that value during the test, and the activity is created again with it. The settings
     * get their old values again at the end. A screenshot goes to `files/device-checks/<shotName>.png`.
     */
    private fun withNameSheetAndKeyboard(
        shotName: String,
        fontScale: String? = null,
        check: (SheetWithKeyboard) -> Unit,
    ) {
        val before = shell("settings get secure $SHOW_IME").trim()
        val scaleBefore = shell("settings get system $FONT_SCALE").trim()
        shell("settings put secure $SHOW_IME 1")
        try {
            if (fontScale != null) useFontScale(fontScale.toFloat(), fontScale)
            rule.setContent { CassiniTheme(darkTheme = false) { NoteScreen(naming, onEvent = {}) } }
            val field = rule.onNode(hasSetTextAction())
            // The field is in the sheet's own dialog window: wait for its focus, read its insets and its height.
            val sheetWindow = (field.fetchSemanticsNode().root as ViewRootForTest).view.rootView
            waitFor(KEYBOARD_MS, { "The sheet window got no input focus; ${Flows.focusReport()}" }) {
                rule.runOnUiThread { sheetWindow.hasWindowFocus() }
            }
            field.performClick()
            waitFor(KEYBOARD_MS, { "The soft keyboard did not open (${imeReport()}; ${Flows.focusReport()})" }) {
                keyboardInset(sheetWindow) > 0
            }
            // The window has the final inset from the start of the keyboard animation; ModalBottomSheet pads its window
            // by the animated inset, so the sheet stands on the keyboard when the animation has ended.
            var placed = placement(sheetWindow)
            waitFor(KEYBOARD_MS, { "The sheet does not stand on the keyboard: $placed" }) {
                placed = placement(sheetWindow)
                abs(placed.sheet.bottom - placed.keyboardTop) <= 1f
            }
            shot(shotName)
            val scale = rule.activity.resources.configuration.fontScale
            Log.i(TAG, "Font scale $scale, $placed")
            check(placed)
        } finally {
            val unset = before == "null" || before.isEmpty()
            shell(if (unset) "settings delete secure $SHOW_IME" else "settings put secure $SHOW_IME $before")
            if (fontScale != null) {
                val noScale = scaleBefore == "null" || scaleBefore.isEmpty()
                shell(
                    if (noScale) "settings delete system $FONT_SCALE" else "settings put system $FONT_SCALE $scaleBefore",
                )
            }
        }
    }

    /** Sets the system font scale and waits until the activity has it (Android creates it again), then edge to edge. */
    private fun useFontScale(scale: Float, setting: String) {
        shell("settings put system $FONT_SCALE $setting")
        waitFor(FONT_SCALE_MS, { "The activity did not get font scale $scale" }) {
            abs(rule.activity.resources.configuration.fontScale - scale) <= FONT_SCALE_DELTA
        }
        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
    }

    /**
     * The IME inset of [window] in px, 0 while the keyboard is closed. Before API 30 the sheet's dialog window gets
     * the keyboard as a system window inset, and WindowInsetsCompat finds the IME inset there too.
     */
    private fun keyboardInset(window: View): Int = rule.runOnUiThread {
        ViewCompat.getRootWindowInsets(window)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
    }

    /** Where the sheet is now in [window], with the status bar and the keyboard of that window. */
    private fun placement(window: View): SheetWithKeyboard {
        val (statusBar, height) = rule.runOnUiThread {
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(window))
            insets.getInsets(WindowInsetsCompat.Type.statusBars()).top to window.height
        }
        val sheet = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
            .fetchSemanticsNode().boundsInWindow
        return SheetWithKeyboard(statusBar, sheet, height - keyboardInset(window))
    }

    /** What the input method service reports, for a failure message: whether it shows the keyboard. */
    private fun imeReport(): String {
        val report = shell("dumpsys input_method")
        val shown = "mInputShown=true" in report || "isInputViewShown=true" in report
        return "API ${Build.VERSION.SDK_INT}, input method ${if (shown) "shows" else "does not show"} the keyboard"
    }

    /** Waits until [condition] holds; at [timeoutMs] fails with [message], built then. */
    private fun waitFor(timeoutMs: Long, message: () -> String, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs, condition)
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("${message()} (waited ${timeoutMs / 1_000} s)", timeout)
        }
    }

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return automation.executeShellCommand(command).use { pipe ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().readText()
        }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(rule.activity.filesDir, "device-checks").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        Log.i(TAG, "Saved ${File(dir, "$name.png")}")
    }

    private companion object {
        const val TAG = "SheetAndDraftDeviceTest"
        const val SHOW_IME = "show_ime_with_hard_keyboard"
        const val FONT_SCALE = "font_scale"
        const val LARGE_FONT_SCALE = "1.3"
        const val FONT_SCALE_MS = 10_000L
        const val FONT_SCALE_DELTA = 0.01f
        const val KEYBOARD_MS = 5_000L
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
