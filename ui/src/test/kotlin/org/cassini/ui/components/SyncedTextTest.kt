package org.cassini.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

/** A field's own text against a UI state that arrives late (docs/ui-fidelity.md, contract change 4). */
class SyncedTextTest {
    private val sent = mutableListOf<String>()

    private fun SyncedText.type(text: String) = edit(TextFieldValue(text, TextRange(text.length))) { sent += it }

    @Test fun aLateStateDoesNotRemoveTypedText() {
        val field = SyncedText("")
        field.type("a")
        field.type("ab")
        field.type("abc")
        field.sync("a")
        assertEquals("abc", field.value.text)
        field.sync("ab")
        assertEquals("abc", field.value.text)
        field.sync("abc")
        assertEquals("abc", field.value.text)
        assertEquals(listOf("a", "ab", "abc"), sent)
    }

    @Test fun anExternalChangeReplacesTheTextWithTheCaretAtTheEnd() {
        val field = SyncedText("supplier")
        field.type("supplier c")
        field.sync("")
        assertEquals(TextFieldValue("", TextRange(0)), field.value)
        field.sync("Call")
        assertEquals(TextFieldValue("Call", TextRange(4)), field.value)
    }

    @Test fun aShorterTextFromTheStateReplacesTheText() {
        val field = SyncedText("")
        field.type("abcdef")
        field.sync("abcd")
        assertEquals("abcd", field.value.text)
    }

    @Test fun aTextTypedAgainAfterABackspaceStaysWhileTheStateIsLate() {
        val field = SyncedText("")
        field.type("a")
        field.type("ab")
        field.type("a")
        field.sync("a")
        assertEquals(TextFieldValue("a", TextRange(1)), field.value)
        field.sync("ab")
        assertEquals(TextFieldValue("a", TextRange(1)), field.value)
        field.sync("a")
        assertEquals(TextFieldValue("a", TextRange(1)), field.value)
    }

    @Test fun aCharacterTypedAtTheLimitDoesNotShowOrSend() {
        val field = SyncedText("abcd")
        field.edit(TextFieldValue("abcde", TextRange(5)), maxLength = 4) { sent += it }
        assertEquals(TextFieldValue("abcd", TextRange(4)), field.value)
        assertEquals(emptyList<String>(), sent)
        // The state does not change, so no sync comes; the field stays at the limit.
        field.edit(TextFieldValue("abcdf", TextRange(5)), maxLength = 4) { sent += it }
        assertEquals("abcd", field.value.text)
    }

    @Test fun aCharacterTypedInTheMiddleAtTheLimitIsDropped() {
        val field = SyncedText("abcd")
        field.edit(TextFieldValue("abXcd", TextRange(3)), maxLength = 4) { sent += it }
        assertEquals(TextFieldValue("abcd", TextRange(2)), field.value)
        assertEquals(emptyList<String>(), sent)
    }

    @Test fun aPasteKeepsWhatFitsUnderTheLimit() {
        val field = SyncedText("ab")
        field.edit(TextFieldValue("ab12345", TextRange(7)), maxLength = 4) { sent += it }
        assertEquals(TextFieldValue("ab12", TextRange(4)), field.value)
        assertEquals(listOf("ab12"), sent)
    }

    @Test fun aCaretMoveSendsNothing() {
        val field = SyncedText("abc")
        field.edit(TextFieldValue("abc", TextRange(1))) { sent += it }
        assertEquals(emptyList<String>(), sent)
        assertEquals(TextRange(1), field.value.selection)
    }

    @Test fun theClearButtonEmptiesTheFieldBeforeTheStateFollows() {
        val field = SyncedText("")
        field.type("a")
        field.type("ab")
        field.set("")
        assertEquals("", field.value.text)
        // The state passes the typed texts late, then the cleared one.
        field.sync("a")
        field.sync("ab")
        assertEquals("", field.value.text)
        field.sync("")
        assertEquals("", field.value.text)
    }
}
