package io.codemyriad.cassini.nav

import androidx.navigation3.runtime.NavKey
import io.codemyriad.cassini.BuildConfig
import io.codemyriad.cassini.processing.ProcessingNotifications
import io.codemyriad.cassini.recording.RecordingNotifications

/** What an intent asks [io.codemyriad.cassini.MainActivity] to show (docs/design/ui.md section 9). */
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
    const val EXTRA_HANDLED = "io.codemyriad.cassini.extra.HANDLED"

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
        ownAuthorities: Set<String> = ownAuthorities(ownPackage),
    ): DeepLink? =
        when {
            action == ACTION_VIEW -> external(data, ownPackage, ownAuthorities)?.let(DeepLink::Import)
            action == ACTION_SEND -> external(stream, ownPackage, ownAuthorities)?.let(DeepLink::Import)
            open == RecordingNotifications.OPEN_RECORDING -> DeepLink.Recording
            open == ProcessingNotifications.OPEN_NOTE && !noteId.isNullOrBlank() -> DeepLink.Note(noteId, resume)
            else -> null
        }

    /**
     * [uri] when another app may hand it to Cassini (review of #45): the activity is exported, and Cassini reads the
     * file with its own permissions, so only a `content://` uri of another app's provider passes. A `file://` path
     * (any app could name Cassini's private files), Cassini's own providers ([ownAuthorities], exact rather than a
     * package prefix so side-by-side debug and release builds can share notes with each other), and a path with a
     * traversal component are refused: `..` or `.` as a segment, or as a component of a segment once its
     * percent-encoding is decoded (once or twice), where an encoded slash or backslash separates components. An encoded
     * separator by itself is fine: the Files app's document ids have one (`primary%3AMusic%2Fvoice.wav`). A malformed
     * escape is refused.
     */
    fun external(
        uri: String?,
        ownPackage: String = BuildConfig.APPLICATION_ID,
        ownAuthorities: Set<String> = ownAuthorities(ownPackage),
    ): String? {
        val rest = uri?.takeIf { it.startsWith(CONTENT) }?.removePrefix(CONTENT) ?: return null
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val own = authority in ownAuthorities
        val path = rest.substring(authority.length).substringBefore('?').substringBefore('#')
        val foreign = authority.isNotEmpty() && !own && '@' !in authority
        return uri.takeIf { foreign && path.split('/').none(::traversal) }
    }

    /** The content authorities that belong to [ownPackage] itself rather than to another app or build variant. */
    fun ownAuthorities(ownPackage: String = BuildConfig.APPLICATION_ID): Set<String> =
        buildSet(OWN_PROVIDER_SUFFIXES.size + 1) {
            add(ownPackage)
            for (suffix in OWN_PROVIDER_SUFFIXES) add("$ownPackage.$suffix")
        }

    /** A path segment that walks up or stays put, as written or after one or two rounds of percent-decoding. */
    private fun traversal(segment: String): Boolean =
        generateSequence(segment) { it.takeIf { '%' in it }?.let(::percentDecoded) }.take(DECODE_ROUNDS).any { form ->
            form.split('/', '\\').any { component -> component == ".." || component == "." }
        }

    private fun percentDecoded(text: String): String = try {
        java.net.URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        // A malformed escape cannot be decoded the way a provider would; treat it as a traversal attempt.
        ".."
    }

    private const val CONTENT = "content://"
    private const val DECODE_ROUNDS = 3
    private val OWN_PROVIDER_SUFFIXES = listOf("share", "documents", "debugstatus", "androidx-startup")

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
