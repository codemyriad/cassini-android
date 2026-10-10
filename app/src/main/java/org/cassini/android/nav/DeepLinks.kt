package org.cassini.android.nav

import androidx.navigation3.runtime.NavKey
import org.cassini.android.processing.ProcessingNotifications
import org.cassini.android.recording.RecordingNotifications

/** What an intent asks [org.cassini.android.MainActivity] to show (docs/design/ui.md section 9). */
sealed interface DeepLink {
    /** The recording notification: back to the active recording. */
    data object Recording : DeepLink

    /** A processing or results notification. [resume] is the Resume action of a paused note. */
    data class Note(val noteId: String, val resume: Boolean = false) : DeepLink

    /** ACTION_VIEW of a document or an audio file: import [uri], then open its note. */
    data class Import(val uri: String) : DeepLink
}

/**
 * The deep links of the app as pure functions: [parse] reads the parts of an intent, and the other functions give the
 * back stack that a link leaves (docs/design/ui.md section 9). The Library is always at the bottom.
 */
object DeepLinks {
    const val ACTION_VIEW = "android.intent.action.VIEW"

    /** Set on an intent once it is handled, so a recreated activity does not open it again. */
    const val EXTRA_HANDLED = "org.cassini.android.extra.HANDLED"

    private val schemes = setOf("content", "file")

    /**
     * The link of an intent with [action], the [data] uri and the extras the notifications set ([open], [noteId],
     * [resume]); null when the intent asks for nothing beyond the app (the launcher).
     */
    fun parse(action: String?, data: String?, open: String?, noteId: String?, resume: Boolean = false): DeepLink? =
        when {
            action == ACTION_VIEW -> data?.takeIf { it.substringBefore(':', "") in schemes }?.let(DeepLink::Import)
            open == RecordingNotifications.OPEN_RECORDING -> DeepLink.Recording
            open == ProcessingNotifications.OPEN_NOTE && !noteId.isNullOrBlank() -> DeepLink.Note(noteId, resume)
            else -> null
        }

    /** The recording notification: pop to [RecordingKey] when it is in [stack], else push it. */
    fun recording(stack: List<NavKey>): List<NavKey> {
        val rooted = rooted(stack)
        val index = rooted.lastIndexOf(RecordingKey)
        return if (index >= 0) rooted.take(index + 1) else rooted + RecordingKey
    }

    /**
     * A note notification: nothing changes when [noteId]'s note is on top; when it is lower in [stack], pop to it;
     * else push it.
     */
    fun note(stack: List<NavKey>, noteId: String): List<NavKey> {
        val rooted = rooted(stack)
        val index = rooted.indexOfLast { it is NoteKey && it.noteId == noteId }
        return if (index >= 0) rooted.take(index + 1) else rooted + NoteKey(noteId)
    }

    /**
     * An imported file: its note goes on top of [stack], with the Library under it when the stack was empty. A note
     * that is in [stack] already is popped to, as [note] does, so that no two entries have the same key.
     */
    fun imported(stack: List<NavKey>, noteId: String): List<NavKey> = note(stack, noteId)

    /** [stack] with [LibraryKey] at the bottom. */
    fun rooted(stack: List<NavKey>): List<NavKey> =
        if (stack.firstOrNull() == LibraryKey) stack else listOf(LibraryKey) + stack.filter { it != LibraryKey }
}
