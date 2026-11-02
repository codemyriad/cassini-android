package org.cassini.android.nav

import androidx.navigation3.runtime.NavKey
import org.cassini.android.BuildConfig
import org.cassini.android.processing.ProcessingNotifications
import org.cassini.android.recording.RecordingNotifications

/** What an intent asks [org.cassini.android.MainActivity] to show (docs/design/ui.md section 9). */
sealed interface DeepLink {
    /** The recording notification: back to the active recording. */
    data object Recording : DeepLink

    /** A processing or results notification. [resume] is the Resume action of a paused note. */
    data class Note(val noteId: String, val resume: Boolean = false) : DeepLink

    /** ACTION_VIEW or ACTION_SEND of a document or an audio file: import [uri], then open its note. */
    data class Import(val uri: String) : DeepLink
}

/**
 * The deep links of the app as pure functions: [parse] reads the parts of an intent, and the other functions give the
 * back stack that a link leaves (docs/design/ui.md section 9). The Library is always at the bottom.
 */
object DeepLinks {
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"

    /** Set on an intent once it is handled, so a recreated activity does not open it again. */
    const val EXTRA_HANDLED = "org.cassini.android.extra.HANDLED"

    /**
     * The link of an intent with [action], the [data] uri, the shared [stream] (`EXTRA_STREAM` of ACTION_SEND: an
     * audio file shared from another app) and the extras the notifications set ([open], [noteId], [resume]); null
     * when the intent asks for nothing beyond the app (the launcher).
     */
    @Suppress("LongParameterList") // the parts of an intent that a link reads
    fun parse(
        action: String?,
        data: String?,
        open: String?,
        noteId: String?,
        resume: Boolean = false,
        stream: String? = null,
        ownPackage: String = BuildConfig.APPLICATION_ID,
    ): DeepLink? =
        when {
            action == ACTION_VIEW -> external(data, ownPackage)?.let(DeepLink::Import)
            action == ACTION_SEND -> external(stream, ownPackage)?.let(DeepLink::Import)
            open == RecordingNotifications.OPEN_RECORDING -> DeepLink.Recording
            open == ProcessingNotifications.OPEN_NOTE && !noteId.isNullOrBlank() -> DeepLink.Note(noteId, resume)
            else -> null
        }

    /**
     * [uri] when another app may hand it to Cassini (review of #45): the activity is exported, and Cassini reads the
     * file with its own permissions, so only a `content://` uri of another app's provider passes. A `file://` path
     * (any app could name Cassini's private files), Cassini's own providers, and a path with a traversal segment
     * (`..`, `.`, or an encoded slash, also percent-encoded once more) are refused.
     */
    fun external(uri: String?, ownPackage: String = BuildConfig.APPLICATION_ID): String? {
        val rest = uri?.takeIf { it.startsWith(CONTENT) }?.removePrefix(CONTENT) ?: return null
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val own = authority == ownPackage || authority.startsWith("$ownPackage.")
        val path = rest.substring(authority.length).substringBefore('?').substringBefore('#')
        val foreign = authority.isNotEmpty() && !own && '@' !in authority
        return uri.takeIf { foreign && path.split('/').none(::traversal) }
    }

    /** A path segment that walks up or sideways, as written or after one or two rounds of percent-decoding. */
    private fun traversal(segment: String): Boolean =
        generateSequence(segment) { it.takeIf { '%' in it }?.let(::percentDecoded) }.take(DECODE_ROUNDS).any { form ->
            form == ".." || form == "." || '/' in form || '\\' in form
        }

    private fun percentDecoded(text: String): String = try {
        java.net.URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        // A malformed escape cannot be decoded the way a provider would; treat it as a traversal attempt.
        ".."
    }

    private const val CONTENT = "content://"
    private const val DECODE_ROUNDS = 3

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
