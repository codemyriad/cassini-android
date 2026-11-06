package io.codemyriad.cassini.core.export

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportNamesTest {
    @Test fun plainTitleKeepsAccentsAndGetsTheExtension() {
        assertEquals(
            "Thursday delivery — café price €.opus",
            ExportNames.file("Thursday delivery — café price €", "opus"),
        )
    }

    @Test fun forbiddenCharactersBecomeUnderscores() {
        assertEquals("a_b_c_d_e_f_g_h_i_j.srt", ExportNames.file("a/b\\c:d*e?f\"g<h>i|j", "srt"))
    }

    @Test fun controlCharactersBecomeUnderscores() {
        assertEquals("a_b_c_d_e.txt", ExportNames.file("a\tb\nc\u0000d\u007Fe", "txt"))
    }

    @Test fun whitespaceRunsCollapse() {
        assertEquals("Monday café meeting.vtt", ExportNames.file("Monday   café   meeting", "vtt"))
    }

    @Test fun dotsAndSpacesAreTrimmedAtBothEnds() {
        assertEquals("note.txt", ExportNames.file(" ..note. . ", "txt"))
        assertEquals("v1.2 draft.txt", ExportNames.file("v1.2 draft", "txt"))
    }

    @Test fun nothingLeftFallsBack() {
        assertEquals("Cassini note.opus", ExportNames.file("", "opus"))
        assertEquals("Cassini note.opus", ExportNames.file(" . . ", "opus"))
        assertEquals("Cassini note.m4a", ExportNames.file(" 　", "m4a"))
    }

    @Test fun longTitlesAreCutAtOneHundredUnits() {
        assertEquals("a".repeat(100) + ".txt", ExportNames.file("a".repeat(120), "txt"))
        assertEquals("a".repeat(99) + ".txt", ExportNames.file("a".repeat(99) + " bcd", "txt"))
    }

    @Test fun theCutNeverSplitsASurrogatePair() {
        val emoji = "😀"
        assertEquals("a".repeat(99) + ".txt", ExportNames.file("a".repeat(99) + emoji + "b", "txt"))
        assertEquals("a".repeat(98) + emoji + ".txt", ExportNames.file("a".repeat(98) + emoji + "b", "txt"))
    }
}
