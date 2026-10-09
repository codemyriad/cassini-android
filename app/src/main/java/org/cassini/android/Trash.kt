package org.cassini.android

import java.io.File
import java.net.URI

/**
 * Notes in the trash keep every file for [KEEP_MS], so a mistake can be undone. Deleting for good removes the audio,
 * documents, saved progress and this note's voiceprints, but only those no other note (trashed or not) still uses.
 * Saved people are not touched: they belong to the phone, not to one note. Worker thread: it reads documents.
 */
internal class Trash(private val filesDir: File, private val store: LibraryStore) {
    fun purge(now: Long) = store.load().filter { expired(it, now) }.forEach { delete(it.id) }

    fun empty() = store.load().filter { it.trashedAt != null }.forEach { delete(it.id) }

    fun delete(id: String) {
        val notes = store.load()
        val note = notes.firstOrNull { it.id == id } ?: return
        val others = notes.filter { it.id != id }
        val meeting = note.session.document?.let(::meeting)
        // The entry goes first: a note must never point at a file that is gone.
        store.remove(id)
        ownedFiles(note, others, filesDir).forEach { if (!it.delete() && it.exists()) android.util.Log.w("Cassini", "Could not delete $it") }
        note.session.uri?.takeIf { uri -> others.none { it.session.uri == uri } }?.let { ProcessingCheckpoint(filesDir, it).discard() }
        if (meeting != null && others.mapNotNull { it.session.document }.none { meeting(it) == meeting }) NoteVoiceStore(filesDir).delete(meeting)
    }

    private fun meeting(path: String) = try {
        CassiniDocument.read(File(path), verify = false).manifest?.optJSONObject("meeting")?.optString("id")?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    companion object {
        const val KEEP_MS = 30L * 24 * 60 * 60 * 1000

        fun expired(note: LibraryNote, now: Long) = note.trashedAt != null && now - note.trashedAt >= KEEP_MS

        /** Whole days before [note] is deleted for good; at least 0. */
        fun daysLeft(note: LibraryNote, now: Long): Int =
            note.trashedAt?.let { (((it + KEEP_MS - now).coerceAtLeast(0) + DAY_MS - 1) / DAY_MS).toInt() } ?: 0

        /** Files in the app's own documents folder that [note] points at and no other note does. Imports' originals are never touched. */
        fun ownedFiles(note: LibraryNote, others: List<LibraryNote>, filesDir: File): List<File> {
            val documents = File(filesDir, "documents").canonicalFile
            fun paths(session: Session) = listOfNotNull(session.document?.let(::File), session.uri?.let(::fileOf)).map { it.canonicalFile }
            val used = others.flatMap { paths(it.session) }.toSet()
            return paths(note.session).distinct().filter { it.parentFile == documents && it !in used }
        }

        private fun fileOf(uri: String): File? = try { URI(uri).takeIf { it.scheme == "file" }?.path?.let(::File) } catch (_: Exception) { null }
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
