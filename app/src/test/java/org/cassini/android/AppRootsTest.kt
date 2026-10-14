package org.cassini.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The `files/debug-root.txt` override of architecture 2.5: debug builds only, and only for a scratch root. */
class AppRootsTest {
    @get:Rule val folder = TemporaryFolder()

    private val files by lazy { folder.newFolder("files") }
    private val cache by lazy { folder.newFolder("cache") }

    private fun name(text: String) = File(files, AppRoots.DEBUG_ROOT_FILE).writeText(text)

    @Test fun releaseBuildsIgnoreTheDebugRoot() {
        name("killtest-1\n")
        val paths = AppRoots.resolve(files, cache, debug = false)
        assertEquals(files, paths.root)
        assertEquals(cache, paths.cache)
        assertEquals(files, paths.modelRoot)
    }

    @Test fun debugBuildsUseTheNamedScratchRootAndShareTheModels() {
        name("killtest-1\n")
        val paths = AppRoots.resolve(files, cache, debug = true)
        assertEquals(File(files, "killtest-1").canonicalFile, paths.root)
        assertEquals(File(cache, "killtest-1"), paths.cache)
        assertEquals(files, paths.modelRoot)
    }

    @Test fun anAbsolutePathInsideFilesIsAccepted() {
        name(File(files, "killtest-2").absolutePath)
        assertEquals(File(files, "killtest-2").canonicalFile, AppRoots.resolve(files, cache, debug = true).root)
    }

    @Test fun withoutAFileTheDefaultPathsStay() {
        assertEquals(files, AppRoots.resolve(files, cache, debug = true).root)
    }

    @Test fun unusableNamesAreRefused() {
        val names = listOf(
            "", "  ", ".", "..", "../elsewhere", "/tmp", "notes", "voices", "parakeet-ultra", "vad/x", "speaker-models",
            AppRoots.DEBUG_ROOT_FILE,
        )
        for (bad in names) {
            name(bad)
            assertNull("'$bad' must be refused", AppRoots.debugRoot(files))
            assertEquals(files, AppRoots.resolve(files, cache, debug = true).root)
        }
    }
}
