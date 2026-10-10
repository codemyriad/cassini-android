package org.cassini.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import org.cassini.ui.components.NAME_MAX_LENGTH
import org.cassini.ui.components.TITLE_MAX_LENGTH
import org.cassini.ui.fixtures.BoardFixtures
import org.cassini.ui.format.FIXED_ZONE
import org.cassini.ui.format.ProvideFixedFormat
import org.cassini.ui.library.LibraryDialog
import org.cassini.ui.library.LibraryEvent
import org.cassini.ui.library.LibraryScreen
import org.cassini.ui.note.NameSheetEvent
import org.cassini.ui.note.NameSpeakerSheetContent
import org.cassini.ui.recording.RecordingEvent
import org.cassini.ui.recording.RecordingScreen
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.voices.SavedVoicesDialog
import org.cassini.ui.voices.SavedVoicesScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The length limits of the text fields hold while the user types one character at a time (features.md sections 2,
 * 4, 6 and 10). The field fills to its limit, then one more character goes in: the field still shows the limit and
 * sends nothing longer. The Name sheet and the Recording title echo each change back through their state, as a
 * ViewModel does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class TextLimitBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private fun setThemed(content: @Composable () -> Unit) {
        rule.setContent {
            CassiniTheme(darkTheme = false) {
                ProvideFixedFormat(locale = Locale.ENGLISH, is24Hour = true, zone = FIXED_ZONE, content = content)
            }
        }
    }

    /**
     * Fills the text field with the Done key (the Library search has Search) past [max], types one more character,
     * and checks that the field shows [max] characters.
     */
    private fun fillPastTheLimit(max: Int) {
        val field = rule.onNode(hasSetTextAction() and hasImeAction(ImeAction.Done))
        field.performTextReplacement("a".repeat(max + 1))
        field.assert(shows("a".repeat(max)))
        field.performTextInput("x")
        field.assert(shows("a".repeat(max)))
    }

    private fun shows(text: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))

    @Test fun theNoteRenameStopsAtTheTitleLimit() {
        val events = mutableListOf<LibraryEvent>()
        val supplier = BoardFixtures.supplier
        setThemed {
            LibraryScreen(
                BoardFixtures.library.copy(dialog = LibraryDialog.Rename(supplier.id, supplier.title)),
                onEvent = { events += it },
            )
        }
        fillPastTheLimit(TITLE_MAX_LENGTH)
        rule.onNode(hasText("Save") and hasClickAction()).performClick()
        rule.runOnIdle {
            assertEquals(listOf(LibraryEvent.RenameConfirmed(supplier.id, "a".repeat(TITLE_MAX_LENGTH))), events)
        }
    }

    @Test fun theVoiceRenameStopsAtTheNameLimit() {
        val voices = BoardFixtures.savedVoices
        val anna = voices.people.first()
        setThemed {
            SavedVoicesScreen(voices.copy(dialog = SavedVoicesDialog.Rename(anna.id, anna.name)), onEvent = {})
        }
        fillPastTheLimit(NAME_MAX_LENGTH)
    }

    @Test fun theNameFieldStopsAtTheNameLimit() {
        val names = mutableListOf<String>()
        var ui by mutableStateOf(BoardFixtures.nameSpeaker)
        setThemed {
            NameSpeakerSheetContent(ui, onEvent = { event ->
                if (event is NameSheetEvent.NameChanged) {
                    names += event.text
                    ui = ui.copy(name = event.text)
                }
            })
        }
        fillPastTheLimit(NAME_MAX_LENGTH)
        rule.runOnIdle { assertEquals(listOf("a".repeat(NAME_MAX_LENGTH)), names) }
    }

    @Test fun theRecordingTitleStopsAtTheTitleLimit() {
        val titles = mutableListOf<String>()
        var state by mutableStateOf(BoardFixtures.recording)
        setThemed {
            RecordingScreen(state, onEvent = { event ->
                if (event is RecordingEvent.Title) {
                    titles += event.text
                    state = state.copy(title = event.text)
                }
            })
        }
        fillPastTheLimit(TITLE_MAX_LENGTH)
        rule.runOnIdle { assertEquals(listOf("a".repeat(TITLE_MAX_LENGTH)), titles) }
    }
}
