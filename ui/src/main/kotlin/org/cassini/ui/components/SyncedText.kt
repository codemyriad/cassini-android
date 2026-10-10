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
 * or reset the IME composition. Any other text is an external change (Clear, a prefilled query, a length limit):
 * the field takes it and puts the caret at its end.
 */
@Stable
internal class SyncedText(initial: String) {
    var value: TextFieldValue by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
        private set

    /** The texts sent to the ViewModel that its state has not shown yet, oldest first. */
    private val pending = ArrayDeque<String>()

    /** A change from the field: keep it, and send the text to [send] when the text (not only the caret) changed. */
    fun edit(next: TextFieldValue, send: (String) -> Unit) {
        val changed = next.text != value.text
        value = next
        if (changed) {
            expect(next.text)
            send(next.text)
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
        if (external == value.text) {
            pending.clear()
            return
        }
        val at = pending.indexOf(external)
        if (at >= 0) {
            // An older text of this field: the state has not caught up yet.
            repeat(at + 1) { pending.removeFirst() }
            return
        }
        pending.clear()
        value = TextFieldValue(external, TextRange(external.length))
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
