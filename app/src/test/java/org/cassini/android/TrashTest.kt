package org.cassini.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TrashTest {
    private val day = 24L * 60 * 60 * 1000

    private fun note(id: String, document: String? = null, uri: String? = null, trashedAt: Long? = null) =
        LibraryNote(id, 0, Session(uri = uri, name = "$id.opus", document = document, libraryId = id), "", trashedAt)

    @Test fun notesWaitThirtyDays() {
        val trashed = note("a", trashedAt = 1000)
        assertFalse(Trash.expired(trashed, 1000 + 30 * day - 1))
        assertTrue(Trash.expired(trashed, 1000 + 30 * day))
        assertFalse(Trash.expired(note("b"), Long.MAX_VALUE))
        assertEquals(30, Trash.daysLeft(trashed, 1000))
        assertEquals(1, Trash.daysLeft(trashed, 1000 + 29 * day + 1))
        assertEquals(0, Trash.daysLeft(trashed, 1000 + 31 * day))
    }

    @Test fun onlyOwnedUnsharedFilesAreDeleted() {
        val files = Files.createTempDirectory("cassini").toFile()
        val documents = File(files, "documents").apply { mkdirs() }
        val audio = File(documents, "one.rec.opus")
        val document = File(documents, "one.opus")
        val shared = File(documents, "shared.opus")
        val outside = File(files, "elsewhere.opus")
        val gone = note("a", document.path, audio.toURI().toString())
        assertEquals(setOf(audio.canonicalFile, document.canonicalFile), Trash.ownedFiles(gone, emptyList(), files).toSet())
        // Another note still opening the same document keeps it.
        val keeper = note("b", document.path)
        assertEquals(listOf(audio.canonicalFile), Trash.ownedFiles(gone, listOf(keeper), files))
        // Files outside the app's documents folder, such as an import's source, are never touched.
        assertEquals(emptyList<File>(), Trash.ownedFiles(note("c", outside.path, "content://provider/x"), emptyList(), files))
        assertEquals(listOf(shared.canonicalFile), Trash.ownedFiles(note("d", shared.path, shared.toURI().toString()), emptyList(), files))
        files.deleteRecursively()
    }

    @Test fun savingANoteKeepsItInTheTrash() {
        val trashed = note("a", trashedAt = 5)
        val saved = LibraryNote.update(trashed, trashed.session.copy(positionMs = 10), "a", 99)
        assertEquals(5L, saved.trashedAt)
        val titled = LibraryNote.update(saved, saved.session.copy(title = "Weekly sync"), "a", 99)
        assertEquals("Weekly sync", titled.title)
        assertEquals("Weekly sync", LibraryNote.update(titled, titled.session.copy(title = null), "a", 99).title)
        assertEquals(titled, LibraryNote.fromJson(org.json.JSONObject(titled.json().toString())))
    }

    @Test fun titlesDropAudioExtensionsOnly() {
        assertEquals("Call with supplier", LibraryNote.displayName("Call with supplier.opus"))
        assertEquals("Italian sample", LibraryNote.displayName("Italian sample.wav"))
        assertEquals("v1.2 notes", LibraryNote.displayName("v1.2 notes"))
        assertEquals(".opus", LibraryNote.displayName(".opus"))
    }
}
