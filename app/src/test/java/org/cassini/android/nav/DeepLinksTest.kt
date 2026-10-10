package org.cassini.android.nav

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The intent parsing and the back stacks of docs/design/ui.md section 9. */
class DeepLinksTest {
    @Test fun parsesTheNotificationExtras() {
        assertEquals(DeepLink.Recording, DeepLinks.parse(MAIN, null, "recording", null))
        assertEquals(DeepLink.Note("n1"), DeepLinks.parse(MAIN, null, "note", "n1"))
        assertEquals(DeepLink.Note("n1", resume = true), DeepLinks.parse(MAIN, null, "note", "n1", resume = true))
    }

    @Test fun parsesAViewedFileOfAContentOrFileUri() {
        fun view(uri: String?) = DeepLinks.parse(DeepLinks.ACTION_VIEW, uri, null, null)
        assertEquals(DeepLink.Import("content://x/1"), view("content://x/1"))
        assertEquals(DeepLink.Import("file:///sdcard/a.opus"), view("file:///sdcard/a.opus"))
        assertNull(view("https://example.org/a.opus"))
        assertNull(view(null))
    }

    @Test fun theLauncherAndUnknownExtrasAskForNothing() {
        assertNull(DeepLinks.parse(MAIN, null, null, null))
        assertNull(DeepLinks.parse(MAIN, null, "note", null))
        assertNull(DeepLinks.parse(MAIN, null, "note", " "))
        assertNull(DeepLinks.parse(MAIN, null, "settings", "n1"))
    }

    @Test fun theRecordingNotificationPopsToRecordingOrPushesIt() {
        assertEquals(listOf(LibraryKey, RecordingKey), DeepLinks.recording(listOf(LibraryKey)))
        val above = listOf(LibraryKey, RecordingKey, SettingsKey)
        assertEquals(listOf(LibraryKey, RecordingKey), DeepLinks.recording(above))
        assertEquals(listOf(LibraryKey, RecordingKey), DeepLinks.recording(emptyList()))
    }

    @Test fun aNoteNotificationKeepsPopsToOrPushesTheNote() {
        val onTop = listOf(LibraryKey, NoteKey("a", query = "word"))
        assertEquals(onTop, DeepLinks.note(onTop, "a"))
        val lower = listOf(LibraryKey, NoteKey("a"), SettingsKey)
        assertEquals(listOf(LibraryKey, NoteKey("a")), DeepLinks.note(lower, "a"))
        val absent = listOf(LibraryKey, SettingsKey)
        assertEquals(listOf(LibraryKey, SettingsKey, NoteKey("b")), DeepLinks.note(absent, "b"))
        assertEquals(listOf(LibraryKey, NoteKey("b")), DeepLinks.note(emptyList(), "b"))
    }

    @Test fun anImportedNoteGoesOnTopWithTheLibraryUnderIt() {
        assertEquals(listOf(LibraryKey, NoteKey("c")), DeepLinks.imported(emptyList(), "c"))
        val recording = listOf(LibraryKey, RecordingKey)
        assertEquals(listOf(LibraryKey, RecordingKey, NoteKey("c")), DeepLinks.imported(recording, "c"))
        assertEquals(listOf(LibraryKey, NoteKey("c")), DeepLinks.imported(listOf(LibraryKey, NoteKey("c")), "c"))
        val lower = listOf(LibraryKey, NoteKey("c"), ModelDownloadKey("c"))
        assertEquals("no second entry with the same key", listOf(LibraryKey, NoteKey("c")), DeepLinks.imported(lower, "c"))
    }

    @Test fun theLibraryIsAlwaysAtTheBottom() {
        assertEquals(listOf(LibraryKey, TrashKey), DeepLinks.rooted(listOf(TrashKey)))
        assertEquals(listOf(LibraryKey, TrashKey), DeepLinks.rooted(listOf(TrashKey, LibraryKey)))
        assertEquals(listOf<NavKey>(LibraryKey), DeepLinks.rooted(emptyList()))
    }

    @Test fun replacingAStackKeepsTheSharedBottom() {
        val stack = mutableListOf<NavKey>(LibraryKey, SettingsKey, LicensesKey)
        stack.replaceWith(listOf(LibraryKey, SettingsKey, SavedVoicesKey))
        assertEquals(listOf(LibraryKey, SettingsKey, SavedVoicesKey), stack)
        stack.replaceWith(listOf(LibraryKey))
        assertEquals(listOf<NavKey>(LibraryKey), stack)
    }

    @Test fun theKeyBelowAScreenPicksItsBackLabel() {
        assertEquals(SettingsKey, listOf(LibraryKey, SettingsKey, TrashKey).below(TrashKey))
        assertEquals(LibraryKey, listOf<NavKey>(LibraryKey, TrashKey).below(TrashKey))
        val consent = listOf(LibraryKey, NoteKey("n"), ModelDownloadKey("n"), LicensesKey)
        assertEquals(ModelDownloadKey("n"), consent.below(LicensesKey))
        assertNull(listOf<NavKey>(LicensesKey).below(LicensesKey))
        assertNull(listOf<NavKey>(LibraryKey).below(TrashKey))
    }

    private companion object {
        const val MAIN = "android.intent.action.MAIN"
    }
}
