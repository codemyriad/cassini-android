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

        override suspend fun create(title: String, createdAt: Long, format: CaptureFormat): String = "note"

        override fun directory(noteId: String): File = root

        override suspend fun rename(noteId: String, title: String) {
            written += title
        }

        override suspend fun finish(noteId: String, durationMs: Long) = Unit

        override suspend fun discard(noteId: String) = Unit
    }
}
