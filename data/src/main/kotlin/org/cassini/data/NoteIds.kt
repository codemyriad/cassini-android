package org.cassini.data

import java.util.UUID

/** Note ids are UUID strings in their canonical lower-case form; they are also directory and file names. */
object NoteIds {
    /** Whether [id] is a note id, so a name read from disk can never leave its directory. */
    fun isValid(id: String): Boolean = try {
        UUID.fromString(id).toString() == id
    } catch (_: IllegalArgumentException) {
        false
    }
}
