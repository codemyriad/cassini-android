package io.codemyriad.cassini.nav

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

    @Test fun parsesAViewedFileOfAContentUriOnly() {
        fun view(uri: String?) = DeepLinks.parse(DeepLinks.ACTION_VIEW, uri, null, null, ownPackage = OWN)
        assertEquals(DeepLink.Import("content://x/1"), view("content://x/1"))
        assertNull("a path: any app could name Cassini's private files", view("file:///sdcard/a.opus"))
        assertNull(view("https://example.org/a.opus"))
        assertNull(view(null))
    }

    /** Review of #45: only another app's content uri passes the exported activity. */
    @Test fun refusesPathsCassinisOwnProvidersAndTraversal() {
        fun external(uri: String) = DeepLinks.external(uri, ownPackage = OWN)
        val refused = listOf(
            "file:///data/data/io.codemyriad.cassini.talk/databases/cassini.db",
            "FILE:///sdcard/a.opus",
            "CONTENT://media/external/audio/1",
            "content:///no-authority",
            "content://io.codemyriad.cassini.talk/notes/1/document.opus",
            "content://io.codemyriad.cassini.talk.share/share/x/a.opus",
            "content://media/external/../../data/x",
            "content://media/./x",
            "content://media/%2e%2e/%2e%2e/data/x",
            "content://media/%2E%2E/x",
            "content://media/a%2F..%2Fb",
            "content://media/a%5c..%5cb",
            "content://media/%252e%252e/x",
            "content://media/%zz",
            "content://user@media/x",
        )
        for (uri in refused) assertNull(uri, external(uri))
        val accepted = listOf(
            "content://com.whatsapp.provider.media/item/42",
            "content://media/external/audio/media/1000",
            "content://com.android.providers.downloads.documents/document/msf%3A24",
            "content://chat/voice/a..b.ogg",
            // The Files app's document ids carry an encoded slash (review of #45): a separator, not a traversal.
            "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fvoice.wav",
            "content://com.android.externalstorage.documents/document/primary%3AMusic%2FInterviews%2Fday%201.m4a",
            "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fa.ogg",
        )
        for (uri in accepted) assertEquals(uri, uri, external(uri))
    }

    /** Review of #53: the device tests' provider names the app after a prefix, outside Cassini's own authorities. */
    @Test fun acceptsTheTestFilesProviderOfThisApp() {
        val uri = "content://testfiles.$OWN/files/a.opus"
        assertEquals(uri, DeepLinks.external(uri, ownPackage = OWN))
    }

    /** Review of #168: side-by-side release and debug builds accept shares from each other while refusing their own. */
    @Test fun acceptsSharesBetweenReleaseAndDebugVariantsWhileRefusingOwnProviders() {
        fun send(stream: String, pkg: String) = DeepLinks.parse(
            DeepLinks.ACTION_SEND,
            null,
            null,
            null,
            stream = stream,
            ownPackage = pkg,
        )
        fun view(uri: String, pkg: String) = DeepLinks.parse(DeepLinks.ACTION_VIEW, uri, null, null, ownPackage = pkg)

        val releaseShare = "content://$OWN.share/share/x/a.opus"
        val debugShare = "content://$DEBUG_OWN.share/share/x/a.opus"
        val releaseDocs = "content://$OWN.documents/document/note%3A1"
        val debugDocs = "content://$DEBUG_OWN.documents/document/note%3A1"

        // Release accepts debug's providers and refuses its own.
        assertEquals(DeepLink.Import(debugShare), send(debugShare, OWN))
        assertEquals(DeepLink.Import(debugDocs), view(debugDocs, OWN))
        for (own in listOf("content://$OWN/x", releaseShare, releaseDocs, "content://$OWN.androidx-startup/x")) {
            assertNull(own, send(own, OWN))
            assertNull(own, view(own, OWN))
        }

        // Debug accepts release's providers and refuses its own.
        assertEquals(DeepLink.Import(releaseShare), send(releaseShare, DEBUG_OWN))
        assertEquals(DeepLink.Import(releaseDocs), view(releaseDocs, DEBUG_OWN))
        val debugOwn = listOf(
            "content://$DEBUG_OWN/x",
            debugShare,
            debugDocs,
            "content://$DEBUG_OWN.debugstatus/x",
            "content://$DEBUG_OWN.androidx-startup/x",
        )
        for (own in debugOwn) {
            assertNull(own, send(own, DEBUG_OWN))
            assertNull(own, view(own, DEBUG_OWN))
        }
    }

    @Test fun parsesASharedAudioFile() {
        fun send(stream: String?) =
            DeepLinks.parse(DeepLinks.ACTION_SEND, null, null, null, stream = stream, ownPackage = OWN)
        assertEquals(DeepLink.Import("content://chat/voice/7"), send("content://chat/voice/7"))
        assertNull("a share without a file", send(null))
        assertNull(send("https://example.org/a.mp3"))
        assertNull("a shared path", send("file:///data/data/io.codemyriad.cassini/files/x"))
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
        assertEquals(
            "no second entry with the same key",
            listOf(LibraryKey, NoteKey("c")),
            DeepLinks.imported(lower, "c"),
        )
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
        const val OWN = "io.codemyriad.cassini.talk"
        const val DEBUG_OWN = "io.codemyriad.cassini.talk.debug"
    }
}
