package org.cassini.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocumentStateTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun everyReaderStateHasAType() {
        val codes = listOf(
            "plain-audio",
            "unknown-cassini-format",
            "invalid-cassini-metadata",
            "unverified",
            "stale-audio",
            "ok"
        )
        assertEquals(codes, codes.map { CassiniDocument(it).documentState.code })
        assertEquals(DocumentState.OK, CassiniDocument("ok").documentState)
    }

    @Test fun aFileThatCannotBeReadIsUnreadableNotPlainAudio() {
        val missing = File(folder.root, "gone.opus")
        assertEquals("plain-audio", CassiniDocument.read(missing).state)
        val checked = CassiniDocument.readChecked(missing)
        assertEquals(DocumentState.UNREADABLE, checked.documentState)
        assertTrue(checked.warnings.single().isNotEmpty())
        assertEquals(DocumentState.UNREADABLE, CassiniDocument.readChecked(folder.root).documentState)
    }

    @Test fun aReadableFileReadsAsBefore() {
        val wav = File(folder.root, "audio.wav").apply { writeBytes("RIFF0000WAVE".toByteArray()) }
        assertEquals(DocumentState.PLAIN_AUDIO, CassiniDocument.readChecked(wav).documentState)
        assertEquals(CassiniDocument.read(wav), CassiniDocument.readChecked(wav))
    }
}
