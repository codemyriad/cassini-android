package org.cassini.data.notes

import kotlinx.coroutines.test.runTest
import org.cassini.core.format.OggOpus
import org.cassini.core.format.OggRepair
import org.cassini.core.library.AudioKind
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** RecoveryRepository.recover (architecture 4.7): captures left open by a crash become PLAIN or UNPLAYABLE. */
@RunWith(RobolectricTestRunner::class)
class RecoveryRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness
    private val probe = FakeProbe()
    private lateinit var recovery: RecoveryRepository
    private val fullMs = OggOpus.scan(fleurs("opus")).durationMs

    @Before fun setUp() {
        h = NotesHarness(folder)
        recovery = RecoveryRepository(h.db, h.paths, h.dispatchers, h.locks, probe) { h.now }
    }

    @After fun tearDown() = h.close()

    /** A LIVE_CAPTURE note in [format] whose capture file is written by [write] (nothing written when null). */
    private suspend fun capture(format: String, write: ((File) -> Unit)?): String {
        val id = h.notes.createRecording("Registrazione", h.now, format)
        write?.invoke(File(h.dir(id), "capture.$format"))
        return id
    }

    @Test fun aTruncatedOpusCaptureIsRepairedToPlainWithASaneDuration() = runTest {
        val id = capture("opus") { truncatedCopy(fleurs("opus"), it, 0.6) }
        val file = File(h.dir(id), "capture.opus")
        assertFalse(OggRepair.endsCleanly(file))

        assertEquals(listOf(id), recovery.recover(activeNoteId = null))

        val row = h.row(id)
        assertEquals(AudioKind.PLAIN, row.audioKind)
        assertTrue("duration ${row.durationMs} of $fullMs", row.durationMs in 1_000 until fullMs)
        assertTrue(OggRepair.endsCleanly(file))
        val scanned = OggOpus.scan(file)
        assertTrue(scanned.validFraming)
        assertEquals(scanned.durationMs, row.durationMs)
    }

    @Test fun aCleanCaptureKeepsItsBytesAndGetsItsDuration() = runTest {
        val id = capture("opus") { fleurs("opus").copyTo(it) }

        assertEquals(listOf(id), recovery.recover(null))

        assertEquals(AudioKind.PLAIN, h.row(id).audioKind)
        assertEquals(fullMs, h.row(id).durationMs)
        assertArrayEquals(fleurs("opus").readBytes(), File(h.dir(id), "capture.opus").readBytes())
    }

    @Test fun theActiveCaptureIsLeftAlone() = runTest {
        val active = capture("opus") { truncatedCopy(fleurs("opus"), it, 0.5) }
        val before = File(h.dir(active), "capture.opus").readBytes()

        assertEquals(emptyList<String>(), recovery.recover(activeNoteId = active))

        assertEquals(AudioKind.LIVE_CAPTURE, h.row(active).audioKind)
        assertArrayEquals(before, File(h.dir(active), "capture.opus").readBytes())
    }

    @Test fun anM4aCaptureIsPlainOnlyWhenItOpens() = runTest {
        val whole = capture("m4a") { fleurs("m4a").copyTo(it) }
        val cut = capture("m4a") { truncatedCopy(fleurs("m4a"), it, 0.5) } // No moov box: MediaExtractor fails.

        assertEquals(listOf(whole, cut).sorted(), recovery.recover(null).sorted())

        assertEquals(AudioKind.PLAIN, h.row(whole).audioKind)
        assertEquals(FLEURS_WAV_MS, h.row(whole).durationMs)
        assertEquals(AudioKind.UNPLAYABLE, h.row(cut).audioKind)
        assertTrue(File(h.dir(cut), "capture.m4a").isFile) // Audio is never deleted.
    }

    @Test fun aCaptureWithoutAudioIsUnplayable() = runTest {
        val missing = capture("opus", null)
        val garbage = capture("opus") { it.writeText("not ogg") }
        val headersOnly = capture("opus") { truncatedCopy(fleurs("opus"), it, 0.02) }

        assertEquals(3, recovery.recover(null).size)

        for (id in listOf(missing, garbage, headersOnly)) assertEquals(AudioKind.UNPLAYABLE, h.row(id).audioKind)
    }

    @Test fun aFinishedCaptureWithoutADurationIsRepairedButNotReported() = runTest {
        val id = capture("opus") { truncatedCopy(fleurs("opus"), it, 0.6) }
        h.db.notes().finishRecording(id, 0, h.now)

        assertEquals(emptyList<String>(), recovery.recover(null))

        assertEquals(AudioKind.PLAIN, h.row(id).audioKind)
        assertTrue(h.row(id).durationMs in 1_000 until fullMs)
        assertTrue(OggRepair.endsCleanly(File(h.dir(id), "capture.opus")))
    }

    @Test fun recoveryRunsOnce() = runTest {
        val id = capture("opus") { truncatedCopy(fleurs("opus"), it, 0.6) }
        assertEquals(listOf(id), recovery.recover(null))
        val row = h.row(id)
        val bytes = File(h.dir(id), "capture.opus").readBytes()

        assertEquals(emptyList<String>(), recovery.recover(null))

        assertEquals(row, h.row(id))
        assertArrayEquals(bytes, File(h.dir(id), "capture.opus").readBytes())
    }
}
