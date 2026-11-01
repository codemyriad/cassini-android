package org.cassini.android.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShareFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun eachShareGetsAFreshFolderWithTheExportName() {
        val files = ShareFiles(File(temp.root, "share"))
        val a = files.target("Weekly sync.txt")
        val b = files.target("Weekly sync.txt")
        assertEquals("Weekly sync.txt", a.name)
        assertNotEquals("two shares never collide", a.parentFile, b.parentFile)
        assertTrue(a.parentFile!!.isDirectory)
    }

    @Test fun foldersOlderThanADayGoAtTheNextShare() {
        val root = File(temp.root, "share")
        var now = 10L * ShareFiles.KEEP_MS
        val files = ShareFiles(root) { now }
        val old = files.target("old.opus").apply { writeText("x") }.parentFile!!
        old.setLastModified(now - ShareFiles.KEEP_MS - 1)
        val recent = files.target("recent.opus").apply { writeText("x") }.parentFile!!
        recent.setLastModified(now - 1_000)
        now += 1
        files.target("next.opus")
        assertFalse("a day-old share is gone", old.exists())
        assertTrue("a receiving app may still read a recent one", recent.exists())
    }
}
