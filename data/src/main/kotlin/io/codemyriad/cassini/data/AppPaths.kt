package io.codemyriad.cassini.data

import java.io.File

/**
 * Every file location of the app (docs/architecture.md, sections 3.2 and 4). [root] is `filesDir` in the app and a
 * scratch directory in tests; [modelRoot] stays at `filesDir` in device tests so they reuse the installed models.
 */
class AppPaths(
    val root: File,
    val cache: File,
    val modelRoot: File = root,
    // Restore startup reads immutable received documents while repairing a separate database copy.
    val database: File = File(root, "cassini.db"),
) {
    val settings = File(root, "settings.preferences_pb")
    val notes = File(root, "notes")
    fun note(id: String) = File(notes, id)
    fun work(id: String) = File(note(id), "work")
    val voices = File(root, "voices")
    val people = File(voices, "people.json")
    val generation = File(voices, "generation")
    fun notePrints(id: String) = File(File(voices, "notes"), "$id.json")
    fun pendingPrints(id: String) = File(File(voices, "pending"), "$id.json")
    val exports = File(cache, "exports")
}
