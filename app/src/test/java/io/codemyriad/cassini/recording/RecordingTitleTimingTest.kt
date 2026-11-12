package io.codemyriad.cassini.recording

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingTitleTimingTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun eachEditRestartsTheDebounceAndOnlyTheLastTitleIsWritten() = runTest {
        val notes = TitleNotes(folder.newFolder())
        val controller = recorder(notes)
        val debounceMs = RecordingController.Timing().titleDebounceMs
        assertTrue(controller.start("Weekly sync"))
        runCurrent()
        assertTrue(controller.state.value is RecordingState.Active)

        controller.setTitle("First")
        runCurrent()
        advanceTimeBy(debounceMs / 2)
        controller.setTitle("Second")
        runCurrent()
        advanceTimeBy(debounceMs / 2)
        runCurrent()
        assertTrue("the first edit's deadline cannot write a cancelled title", notes.written.isEmpty())

        controller.setTitle("Final title ")
        runCurrent()
        advanceTimeBy(debounceMs - 1)
        runCurrent()
        assertTrue("the latest edit still waits its whole debounce", notes.written.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("Final title"), notes.written)
        assertEquals("Final title ", (controller.state.value as RecordingState.Active).title)

        advanceTimeBy(debounceMs * 2)
        runCurrent()
        assertEquals("no superseded title task writes later", listOf("Final title"), notes.written)
    }

    @Test fun finishAcknowledgesSavingBeforeQueuedRecorderWorkCanRun() = runTest {
        val notes = TitleNotes(folder.newFolder())
        val controller = recorder(notes)
        assertTrue(controller.start("Weekly sync"))
        runCurrent()
        val active = controller.state.value as RecordingState.Active

        controller.finish("Final title")
        assertEquals(
            "Saving is synchronous even while the recorder dispatcher has not run",
            RecordingState.Finishing(active.noteId, "Final title", active.elapsedMs),
            controller.state.value,
        )
        controller.finish("Duplicate tap")
        controller.pause()
        controller.resume()
        runCurrent()

        assertTrue(controller.state.value is RecordingState.Ended)
        assertEquals("only the first Finish saves", listOf("Final title"), notes.written)
        assertEquals(1, notes.finishes)
    }

    @Test fun finishDuringStartupStillSavesAfterTheMicrophoneStarts() = runTest {
        val notes = TitleNotes(folder.newFolder())
        val controller = recorder(notes)
        assertTrue(controller.start("Weekly sync"))
        controller.finish("Final title")
        runCurrent()
        assertTrue(controller.state.value is RecordingState.Ended)
        assertEquals(listOf("Final title"), notes.written)
        assertEquals(1, notes.finishes)
    }

    private fun TestScope.recorder(notes: TitleNotes): RecordingController {
        val platform = FakePlatform()
        val controller = RecordingController(
            RecordingController.Parts(
                scope = backgroundScope,
                dispatcher = StandardTestDispatcher(testScheduler),
                ready = {},
                notes = { notes },
                capture = FakeCaptureSource(),
                platform = platform,
                handoff = TranscriptionHandoff { true },
                consent = FakeConsent(),
                defaultTitle = { "Voice note" },
                clock = { 0L },
            ),
        )
        platform.controller = { controller }
        return controller
    }

    private class TitleNotes(private val root: File) : RecordingNotes {
        val written = mutableListOf<String>()
        var finishes = 0

        override suspend fun create(title: String, createdAt: Long, format: CaptureFormat): String = "note"

        override fun directory(noteId: String): File = root

        override suspend fun rename(noteId: String, title: String) {
            written += title
        }

        override suspend fun finish(noteId: String, durationMs: Long) {
            finishes++
        }

        override suspend fun discard(noteId: String) = Unit
    }
}
