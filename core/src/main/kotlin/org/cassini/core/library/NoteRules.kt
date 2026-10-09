package org.cassini.core.library

import org.cassini.core.format.Transcript

/** One note's state as the 0.1.0 library and viewer hold it (`library.json` notes and `session.json`). */
data class Session(
    val uri: String? = null, val name: String = "", val transcript: Transcript? = null,
    val durationMs: Long = 0, val inferenceMs: Long = 0, val positionMs: Int = 0,
    val resultPrecision: String = "INT8",
    val document: String? = null, val selectedVariant: String? = null,
    val processingMs: Long = 0,
    val processingPaused: Boolean = false,
    val libraryId: String? = null,
    /** The viewer that wrote session.json last. Not part of a note. */
    val screen: String? = null,
)

/** A local catalogue entry. The portable document remains authoritative when opened. */
data class LibraryNote(val id: String, val createdAt: Long, val session: Session, val text: String) {
    fun matches(query: String): Boolean = query.isBlank() || session.name.contains(query.trim(), true) || text.contains(query.trim(), true)

    fun snippet(query: String, limit: Int = 160): String {
        val start = if (query.isBlank()) 0 else (text.indexOf(query.trim(), ignoreCase = true) - 45).coerceAtLeast(0)
        return (if (start > 0) "…" else "") + text.drop(start).take(limit) + if (text.length > start + limit) "…" else ""
    }

    companion object {
        /**
         * The session a recreated viewer returns to. Saved state can predate work that finished
         * while the viewer was stopped, so session.json wins when that viewer wrote it last.
         * Otherwise a note is known by its ID, which it keeps when retranscription moves it to
         * another file. Without a catalogue entry, session.json still counts when it holds that
         * ID, or the same file under no other ID.
         */
        fun shown(screen: String?, id: String?, uri: String?, latest: Session, notes: List<LibraryNote>): Session? {
            if (screen != null && latest.screen == screen) return latest.takeIf { it.uri != null }
            if (uri == null) return null
            val note = if (id != null) notes.firstOrNull { it.id == id } else notes.firstOrNull { it.session.uri == uri }
            return note?.session ?: latest.takeIf { if (id != null && it.libraryId != null) it.libraryId == id else it.uri == uri }
        }

        fun update(previous: LibraryNote?, session: Session, id: String, now: Long): LibraryNote =
            LibraryNote(id, previous?.createdAt ?: now, session.copy(libraryId = id),
                session.transcript?.words?.joinToString(" ") { it.text }
                    ?: previous?.text?.takeIf { session.document != null && previous.session.document == session.document && previous.session.selectedVariant == session.selectedVariant }.orEmpty())
    }
}
