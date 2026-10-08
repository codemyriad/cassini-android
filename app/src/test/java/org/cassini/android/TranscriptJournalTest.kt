package org.cassini.android

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TranscriptJournalTest {
    private val header = TranscriptJournal.Header("opus-abc", "parakeet-tdt-0.6b-v3", "INT8", "r1", "silero", "SPEECH", "WHOLE_SPANS")
    private fun file(): File = File(Files.createTempDirectory("journal").toFile(), "note.journal.jsonl").also { it.deleteOnExit() }
    private fun w(start: Long, text: String) = TimedWord(Word("spk_1", start, start + 300, text), start + 350)

    @Test fun appendAndReplayIsExact() {
        val f = file()
        val journal = TranscriptJournal(f)
        assertNull(journal.load(header))
        journal.open(header)
        journal.append(listOf(w(0, "ciao"), w(400, "a\"tutti\\")), 16_000)
        journal.append(emptyList(), 20_000)
        journal.append(listOf(w(2000, "è")), 48_000)
        journal.close()
        val state = TranscriptJournal(f).load(header)!!
        assertEquals(listOf(w(0, "ciao"), w(400, "a\"tutti\\"), w(2000, "è")), state.words)
        assertEquals(48_000L, state.settledEnd)
        // Reopening continues the same file without a second header.
        TranscriptJournal(f).apply { open(header); append(listOf(w(4000, "poi")), 80_000); close() }
        val more = TranscriptJournal(f).load(header)!!
        assertEquals(4, more.words.size)
        assertEquals(80_000L, more.settledEnd)
    }

    @Test fun tornLastLineIsCutOff() {
        val f = file()
        TranscriptJournal(f).apply { open(header); append(listOf(w(0, "uno")), 16_000); append(listOf(w(500, "due")), 32_000); close() }
        val whole = f.length()
        f.appendText("{\"end\":48000,\"words\":[[\"spk_1\",9")
        val state = TranscriptJournal(f).load(header)!!
        assertEquals(listOf(w(0, "uno"), w(500, "due")), state.words)
        assertEquals(32_000L, state.settledEnd)
        assertEquals(whole, f.length())
        // A garbage record ends replay there too.
        f.appendText("not json\n{\"end\":64000,\"words\":[]}\n")
        assertEquals(32_000L, TranscriptJournal(f).load(header)!!.settledEnd)
        assertEquals(whole, f.length())
    }

    @Test fun mismatchedHeaderDiscardsTheJournal() {
        for (other in listOf(header.copy(digest = "opus-xyz"), header.copy(model = "parakeet-tdt-0.6b-v2"),
            header.copy(cutting = "FIXED"), header.copy(policy = "PIECES"), header.copy(precision = "FP32"))) {
            val f = file()
            TranscriptJournal(f).apply { open(header); append(listOf(w(0, "uno")), 16_000); close() }
            assertNull(TranscriptJournal(f).load(other))
            assertFalse(f.exists())
        }
        val f = file()
        f.writeText("{\"v\":1,\"dig")
        assertNull(TranscriptJournal(f).load(header))
        assertFalse(f.exists())
        f.writeText("{\"v\":99}\n")
        assertNull(TranscriptJournal(f).load(header))
        assertFalse(f.exists())
    }
}
