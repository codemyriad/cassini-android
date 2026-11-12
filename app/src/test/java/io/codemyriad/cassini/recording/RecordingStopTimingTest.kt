package io.codemyriad.cassini.recording

import io.codemyriad.cassini.core.audio.RecordingPolicy
import io.codemyriad.cassini.engine.capture.MicrophoneCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingStopTimingTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun aQueuedMicrophoneFailureKeepsItsOutcomeAndFinalTitle() = runTest {
        val h = harness()
        withHeldEnds(h) {
            h.mic.fail()
            h.controller.finish("Final title")
            assertEquals(2, h.dispatcher.held.size)
            h.dispatcher.releaseAll()
            runCurrent()
            h.assertEnded(RecordingOutcome.MicrophoneFailed, handoffs = 0)
        }
    }

    @Test fun aQueuedStorageStopKeepsItsOutcomeAndFinalTitle() = runTest {
        val h = harness()
        withHeldEnds(h) {
            h.platform.usable = 0
            advanceTimeBy(TICK_MS)
            runCurrent()
            h.controller.finish("Final title")
            assertEquals(2, h.dispatcher.held.size)
            h.dispatcher.releaseAll()
            runCurrent()
            h.assertEnded(RecordingOutcome.StoppedForSpace, handoffs = 0)
        }
    }

    @Test fun aQueuedFileLimitStopKeepsItsOutcomeAndFinalTitle() = runTest {
        val h = harness(CaptureFormat.M4A)
        withHeldEnds(h) {
            RandomAccessFile(h.mic.file, "rw").use { it.setLength(RecordingPolicy.AAC_MAX_FILE_BYTES) }
            advanceTimeBy(TICK_MS)
            runCurrent()
            h.controller.finish("Final title")
            assertEquals(2, h.dispatcher.held.size)
            h.dispatcher.releaseAll()
            runCurrent()
            h.assertEnded(RecordingOutcome.StoppedAtFileLimit, handoffs = 0)
        }
    }

    @Test fun finishQueuedFirstKeepsItsOutcomeWhenALaterMicrophoneErrorArrives() = runTest {
        val h = harness()
        withHeldEnds(h) {
            h.controller.finish("Final title")
            h.mic.fail()
            h.dispatcher.releaseAll()
            runCurrent()
            h.assertEnded(RecordingOutcome.Saved(AfterFinish.TRANSCRIBE), handoffs = 1)
        }
    }

    @Test fun aStaleFinishAfterAnAutomaticStopCannotStopTheNextRecording() = runTest {
        val h = harness()
        withHeldEnds(h) {
            h.mic.fail()
            h.controller.finish("Final title")
            h.dispatcher.releaseNext()
            runCurrent()
            h.assertEnded(RecordingOutcome.MicrophoneFailed, handoffs = 0)
            val oldMic = h.mic

            h.dispatcher.holding = false
            assertTrue(h.controller.start("Next recording"))
            runCurrent()
            val next = h.controller.state.value as RecordingState.Active
            assertEquals("note-2", next.noteId)
            h.dispatcher.releaseAll()
            runCurrent()
            assertEquals(next, h.controller.state.value)
            assertEquals(0, h.mic.stopCalls)
            assertEquals(1, oldMic.stopCalls)
        }
    }

    @Test fun aLateMicrophoneCallbackCannotStopANewerRecording() = runTest {
        val h = harness()
        val oldMic = h.mic
        h.controller.finish("Final title")
        runCurrent()
        h.assertEnded(RecordingOutcome.Saved(AfterFinish.TRANSCRIBE), handoffs = 1)
        assertTrue(h.controller.start("Next recording"))
        runCurrent()
        val next = h.controller.state.value as RecordingState.Active
        oldMic.fail()
        runCurrent()
        assertEquals(next, h.controller.state.value)
        assertEquals(0, h.mic.stopCalls)
    }

    @Test fun anAcceptedFinishTitleIsNotOverwrittenByAConcurrentAutomaticClaim() = runTest {
        val h = harness()
        withHeldEnds(h) {
            h.mic.fail()
            h.mic.onElapsedRead = { h.controller.finish("Final title") }
            h.dispatcher.releaseAll()
            runCurrent()
            h.assertEnded(RecordingOutcome.MicrophoneFailed, handoffs = 0)
        }
    }

    @Test fun pauseAndSilenceCannotTouchACaptureWhileStopIsSuspended() = runTest {
        val h = harness()
        val release = CompletableDeferred<Unit>()
        h.mic.stopGate = release
        try {
            // One control was posted while Active; another arrives after end() claimed the capture.
            h.controller.pause()
            h.platform.watch.silence(true)
            h.controller.finish("Final title")
            runCurrent()
            assertTrue(h.mic.stopStarted.isCompleted)
            h.controller.pause()
            h.platform.watch.silence(true)
            advanceTimeBy(SILENCE_MS)
            runCurrent()
            assertEquals(0, h.mic.toggles)
            assertFalse(h.mic.paused)
        } finally {
            release.complete(Unit)
            runCurrent()
        }
        h.assertEnded(RecordingOutcome.Saved(AfterFinish.TRANSCRIBE), handoffs = 1)
    }

    @Test fun resumeCannotRestartAPausedCaptureWhileStopIsSuspended() = runTest {
        val h = harness()
        h.controller.pause()
        runCurrent()
        assertTrue(h.mic.paused)
        val before = h.mic.toggles
        val release = CompletableDeferred<Unit>()
        h.mic.stopGate = release
        try {
            h.controller.finish("Final title")
            runCurrent()
            h.controller.resume()
            runCurrent()
            assertEquals(before, h.mic.toggles)
        } finally {
            release.complete(Unit)
            runCurrent()
        }
        h.assertEnded(RecordingOutcome.Saved(AfterFinish.TRANSCRIBE), handoffs = 1)
    }

    @Test fun anAlreadyScheduledSilenceConfirmationCannotPauseWhileSaving() = runTest {
        val h = harness()
        h.platform.watch.silence(true)
        runCurrent()
        val release = CompletableDeferred<Unit>()
        h.mic.stopGate = release
        try {
            h.controller.finish("Final title")
            runCurrent()
            advanceTimeBy(SILENCE_MS)
            runCurrent()
            assertEquals(0, h.mic.toggles)
        } finally {
            release.complete(Unit)
            runCurrent()
        }
        h.assertEnded(RecordingOutcome.Saved(AfterFinish.TRANSCRIBE), handoffs = 1)
    }

    @Test fun anAutomaticStopPublishesFinalTitleBeforeWaitingForACancelledTitleWrite() = runTest {
        val h = harness()
        val release = CompletableDeferred<Unit>()
        h.notes.renameGate = release
        try {
            h.controller.setTitle("Debounced title")
            runCurrent()
            advanceTimeBy(TITLE_MS)
            runCurrent()
            assertTrue(h.notes.renameStarted.isCompleted)
            h.platform.watch.silence(true)
            runCurrent()
            h.holdEnds()
            h.mic.fail()
            h.controller.finish("Final title")
            h.dispatcher.releaseAll()
            runCurrent()
            val saving = h.controller.state.value as RecordingState.Finishing
            assertEquals("Final title", saving.title)
            assertEquals(0, h.mic.stopCalls)
            advanceTimeBy(SILENCE_MS)
            runCurrent()
            assertEquals("a pending silence confirmation stays out of Saving", 0, h.mic.toggles)
            h.controller.pause()
            runCurrent()
            assertEquals(0, h.mic.toggles)
        } finally {
            release.complete(Unit)
            h.dispatcher.releaseAll()
            runCurrent()
        }
        h.assertEnded(RecordingOutcome.MicrophoneFailed, handoffs = 0)
    }

    private fun TestScope.withHeldEnds(h: StopHarness, block: () -> Unit) {
        h.holdEnds()
        try {
            block()
        } finally {
            h.dispatcher.releaseAll()
            runCurrent()
        }
    }

    private fun TestScope.harness(format: CaptureFormat = CaptureFormat.OPUS): StopHarness =
        StopHarness(this, folder.newFolder(), format).also {
            assertTrue(it.controller.start("Initial title"))
            runCurrent()
            assertTrue(it.controller.state.value is RecordingState.Active)
        }

    private class StopHarness(scope: TestScope, root: File, format: CaptureFormat) {
        val notes = StopNotes(root)
        val platform = FakePlatform()
        val source = StopSource(format)
        val dispatcher = StopDispatcher(StandardTestDispatcher(scope.testScheduler))
        var handoffs = 0
        val controller = RecordingController(
            RecordingController.Parts(
                scope = scope.backgroundScope,
                dispatcher = dispatcher,
                ready = {},
                notes = { notes },
                capture = source,
                platform = platform,
                handoff = TranscriptionHandoff {
                    handoffs++
                    true
                },
                consent = FakeConsent(),
                defaultTitle = { "Voice note" },
                timing = RecordingController.Timing(
                    tickMs = TICK_MS,
                    titleDebounceMs = TITLE_MS,
                    silenceConfirmMs = SILENCE_MS,
                ),
            ),
        )
        val mic get() = source.captures.last()

        init { platform.controller = { controller } }

        fun holdEnds() {
            dispatcher.passJob = dispatcher.lastJob // The policy loop may tick; its newly posted end() must wait.
            dispatcher.holding = true
        }

        fun assertEnded(outcome: RecordingOutcome, handoffs: Int) {
            assertEquals(RecordingState.Ended("note-1", outcome), controller.state.value)
            assertEquals(handoffs, this.handoffs)
            assertEquals("Final title", notes.written.last())
            assertEquals(1, notes.finishes)
        }
    }

    private companion object {
        const val TICK_MS = 10L
        const val SILENCE_MS = 50L
        const val TITLE_MS = 500L
    }
}

/** New work waits explicitly, but virtual timer resumptions keep their real scheduler. */
@OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private class StopDispatcher(private val delegate: TestDispatcher) : CoroutineDispatcher(), Delay by delegate {
    data class Posted(val context: CoroutineContext, val block: Runnable)
    val held = ArrayDeque<Posted>()
    var holding = false
    var passJob: Job? = null
    var lastJob: Job? = null

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        lastJob = context[Job]
        if (holding && context[Job] !== passJob) {
            held.addLast(Posted(context, block))
        } else {
            delegate.dispatch(context, block)
        }
    }

    fun releaseNext() {
        val posted = held.removeFirst()
        delegate.dispatch(posted.context, posted.block)
    }

    fun releaseAll() {
        holding = false
        while (held.isNotEmpty()) releaseNext()
    }
}

private class StopNotes(private val root: File) : RecordingNotes {
    val written = mutableListOf<String>()
    var finishes = 0
    var created = 0
    var renameGate: CompletableDeferred<Unit>? = null
    val renameStarted = CompletableDeferred<Unit>()

    override suspend fun create(title: String, createdAt: Long, format: CaptureFormat): String = "note-${++created}"
    override fun directory(noteId: String): File = File(root, noteId).also { it.mkdirs() }
    override suspend fun rename(noteId: String, title: String) {
        renameStarted.complete(Unit)
        withContext(NonCancellable) { renameGate?.await() }
        written += title
    }
    override suspend fun finish(noteId: String, durationMs: Long) { finishes++ }
    override suspend fun discard(noteId: String) = Unit
}

private class StopSource(private val format: CaptureFormat) : CaptureSource {
    val captures = mutableListOf<StopCapture>()
    override suspend fun format(): CaptureFormat = format
    override fun start(file: File, onError: () -> Unit): MicrophoneCapture =
        StopCapture(file, onError).also { captures += it }
}

private class StopCapture(override val file: File, private val onError: () -> Unit) : MicrophoneCapture {
    override var elapsedMs = 31_000L
        get() {
            val hook = onElapsedRead
            onElapsedRead = null
            hook?.invoke()
            return field
        }
    var onElapsedRead: (() -> Unit)? = null
    override val amplitude = 0
    override val opus = true
    override var paused = false
    var toggles = 0
    var stopCalls = 0
    var stopGate: CompletableDeferred<Unit>? = null
    val stopStarted = CompletableDeferred<Unit>()

    init { file.writeBytes(ByteArray(100)) }
    override fun togglePause() {
        toggles++
        paused = !paused
    }
    override suspend fun stop(): File {
        stopCalls++
        stopStarted.complete(Unit)
        stopGate?.await()
        return file
    }
    override fun release() = Unit
    fun fail() = onError()
}
