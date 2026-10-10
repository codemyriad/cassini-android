package org.cassini.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The text of a field whose value comes from a screen's UI state (docs/architecture.md section 6: one `StateFlow`
 * per screen, collected with `collectAsStateWithLifecycle`). That state arrives a frame or more after each
 * keystroke. Thus the field shows this local [value], which changes at once, and each change goes to the
 * ViewModel as an event.
 *
 * [sync] receives the text of the UI state. When that text is one that this field sent and that the state has
 * not passed yet, the field keeps its own text, so a late state does not remove typed characters, move the caret
 * or reset the IME composition. Any other text is an external change (Clear, a prefilled query): the field takes it
 * and puts the caret at its end.
 *
 * A length limit applies in [edit], before the field keeps the change. A limit in the event handler cannot work:
 * when the field is full, the cut text is equal to the state text, so the state does not change and [sync] does
 * not run.
 */
@Stable
internal class SyncedText(initial: String) {
    var value: TextFieldValue by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
        private set

    /** The texts sent to the ViewModel that its state has not shown yet, oldest first. */
    private val pending = ArrayDeque<String>()

    /**
     * A change from the field: keep it, and send the text to [send] when the text (not only the caret) changed.
     * A text longer than [maxLength] loses the characters just before the caret (the ones the user typed or
     * pasted last), as Android's length filter does.
     */
    fun edit(next: TextFieldValue, maxLength: Int = Int.MAX_VALUE, send: (String) -> Unit) {
        val kept = next.limitTo(maxLength)
        val changed = kept.text != value.text
        value = kept
        if (changed) {
            expect(kept.text)
            send(kept.text)
        }
    }

    /** A change that the field makes itself, such as the Clear button: show [text] now and expect it back. */
    fun set(text: String) {
        if (text == value.text) return
        value = TextFieldValue(text, TextRange(text.length))
        expect(text)
    }

    /** The text of the latest UI state. */
    fun sync(external: String) {
        val at = pending.indexOf(external)
        if (at >= 0) {
            // A text this field sent: the state has caught up to it, and maybe not yet to the texts after it. This
            // check comes first, because a text the user typed again (after a backspace) is also equal to the field.
            repeat(at + 1) { pending.removeFirst() }
            return
        }
        pending.clear()
        if (external != value.text) value = TextFieldValue(external, TextRange(external.length))
    }

    private fun TextFieldValue.limitTo(max: Int): TextFieldValue {
        val excess = text.length - max
        if (excess <= 0) return this
        val caret = selection.end.coerceIn(excess, text.length)
        val cut = caret - excess
        return TextFieldValue(text.removeRange(cut, caret), TextRange(cut))
    }

    private fun expect(text: String) {
        pending.addLast(text)
        if (pending.size > MAX_PENDING) pending.removeFirst()
    }

    private companion object {
        /** More keystrokes than a state can fall behind by. */
        const val MAX_PENDING = 64
    }
}

/** A [SyncedText] that follows [external], the text of the screen's UI state. */
@Composable
internal fun rememberSyncedText(external: String): SyncedText {
    val text = remember { SyncedText(external) }
    LaunchedEffect(external) { text.sync(external) }
    return text
}
