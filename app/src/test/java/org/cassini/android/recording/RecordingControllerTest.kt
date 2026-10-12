package org.cassini.android.recording

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cassini.android.AppGraph
import org.cassini.android.testing.NoUncaughtCoroutines
import org.cassini.core.audio.Limits
import org.cassini.core.audio.RecordingPolicy
import org.cassini.core.library.AudioKind
import org.cassini.data.AppPaths
import org.cassini.data.CassiniDispatchers
import org.cassini.data.db.NoteEntity
import org.cassini.data.models.ModelFetcher
import org.cassini.engine.capture.MicrophoneCapture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * RecordingController (architecture 5.8, features.md section 2) over the real AppGraph and Room on Robolectric, with
 * a fake capture, a fake platform (service, wake lock, free space, silence) and a fake hand-off. The controller runs
 * on its real single thread; the policy loop ticks every 10 ms, and the fake capture's clock is set by each test.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingControllerTest {
    @get:Rule(order = Int.MIN_VALUE)
    val uncaught = NoUncaughtCoroutines()

    @get:Rule val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val capture = FakeCaptureSource()
    private val platform = FakePlatform()
    private val consent = FakeConsent()
    private val handoffs = CopyOnWriteArrayList<String>()
    private val renames = AtomicInteger()
    private lateinit var paths: AppPaths
    private lateinit var graph: AppGraph

    private val controller: RecordingController get() = graph.recording

    @Before fun setUp() {
        paths = AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder())
        val hooks = AppGraph.RecordingHooks(
            capture = capture,
            platform = platform,
            notes = { catalogue -> CountingNotes(RepositoryRecordingNotes(catalogue.notes, paths::note)) },
            handoff = {
                TranscriptionHandoff { noteId ->
                    handoffs += noteId
                    true
                }
            },
            consent = consent,
            timing = RecordingController.Timing(tickMs = 10, serviceWaitMs = 2_000, wakeLockRenewMs = 50),
        )
        val config = AppGraph.Config(
            paths = paths,
            dispatchers = CassiniDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Unconfined),
            fetcher = ModelFetcher { _, _, _ -> fail("No model is fetched in this test") },
            network = { true },
            recording = hooks,
        )
        graph = AppGraph(context, config)
        platform.controller = { graph.recording }
        runBlocking { withTimeout(TIMEOUT_MS) { graph.ready.await() } }
    }

    @After fun tearDown() = graph.close()

    // Finish and the title. ---------------------------------------------------------------------------------------

    @Test fun finishNeverRunsOnTheMainThread() = runBlocking {
        val main = Looper.getMainLooper().thread
        assertEquals("Robolectric runs the test on the main thread", main, Thread.currentThread())
        val active = startRecording("Riunione")
        assertEquals(CaptureFormat.OPUS, active.format)
        assertEquals(48_000, active.sampleRateHz)
        assertTrue("the wake lock is held", platform.wakeLocks.get() >= 1)
        val mic = capture.latest
        mic.gate = CountDownLatch(1)
        mic.elapsedMs = 61_000

        val before = System.nanoTime()
        controller.finish("Riunione di lunedì")
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)
        assertTrue("finish returned after $tookMs ms", tookMs < 100)
        waitFor { mic.stopThread != null }
        assertTrue("the capture is still stopping", controller.state.value is RecordingState.Finishing)
        mic.gate?.countDown()

        val ended = awaitEnded()
        assertNotEquals(main, mic.stopThread)
        // Coroutines debug mode (on in CI) appends " @coroutine#N" to thread names.
        assertEquals("cassini-recorder", mic.stopThread?.name?.substringBefore(" @coroutine#"))
        assertEquals(RecordingState.Ended(active.noteId, RecordingOutcome.Saved(AfterFinish.TRANSCRIBE)), ended)
        val row = row(active.noteId)
        assertEquals(AudioKind.PLAIN, row?.audioKind)
        assertEquals(61_000L, row?.durationMs)
        assertEquals("Riunione di lunedì", row?.title)
        assertEquals(listOf(active.noteId), handoffs)
        assertNull(controller.activeNoteId.value)
        assertFalse(platform.wakeLockHeld)
        assertTrue(platform.watch.closed)
    }

    @Test fun titleEditsAreDebouncedAndKept() = runBlocking {
        val active = startRecording("Nota")
        assertEquals("Nota", row(active.noteId)?.title)

        controller.setTitle("Prova")
        controller.setTitle("Prova del")
        controller.setTitle("Prova del coro ")
        waitFor { row(active.noteId)?.title == "Prova del coro" }
        delay(DEBOUNCE_MS)
        assertEquals("one write after the last change", 1, renames.get())
        assertEquals("the state keeps the text as typed", "Prova del coro ", activeState().title)

        controller.finish()
        awaitEnded()
        assertEquals("Prova del coro", row(active.noteId)?.title)
    }

    @Test fun aBlankTitleBecomesTheDefault() = runBlocking {
        val active = startRecording("")
        assertTrue(row(active.noteId)?.title.orEmpty().startsWith("Voice note "))
        assertEquals(row(active.noteId)?.title, active.defaultTitle)
        assertEquals(
            "the notification shows the row title",
            active.defaultTitle,
            RecordingNotifications(context).content(active).text,
        )

        controller.finish("   ")
        awaitEnded()
        assertTrue(row(active.noteId)?.title.orEmpty().startsWith("Voice note "))
    }

    // The policy loop. ----------------------------------------------------------------------------------------------

    @Test fun storageLowAtFifteenMinutesThenStopAtTheFloor() = runBlocking {
        val bytesPerSecond = RecordingPolicy.OPUS_BYTES_PER_SECOND
        platform.usable = Limits.MIN_RECORDING_FREE_BYTES + 16 * 60 * bytesPerSecond
        val active = startRecording("Spazio")
        capture.latest.elapsedMs = 5_000
        waitFor { activeState().elapsedMs == 5_000L }
        assertNull(activeState().warning)

        platform.usable = Limits.MIN_RECORDING_FREE_BYTES + 15 * 60 * bytesPerSecond
        waitFor { activeState().warning != null }
        assertEquals(RecordingWarning.StorageLow(15), activeState().warning)
        assertEquals(15 * 60_000L, activeState().space.spaceMs)

        platform.usable = Limits.MIN_RECORDING_FREE_BYTES - 1
        val ended = awaitEnded()
        assertEquals(RecordingState.Ended(active.noteId, RecordingOutcome.StoppedForSpace), ended)
        assertEquals(AudioKind.PLAIN, row(active.noteId)?.audioKind)
        assertTrue("a storage stop never enqueues", handoffs.isEmpty())
    }

    @Test fun aRecordingHasNoTimeLimitAndRenewsItsWakeLock() = runBlocking {
        val active = startRecording("Lunga")
        assertTrue("the wake lock is held", platform.wakeLocks.get() >= 1)
        // Past the old 2 h cap, and far past it: the recording goes on, with no warning.
        for (hours in listOf(2L, 3L, 100L)) {
            capture.latest.elapsedMs = hours * HOUR_MS
            waitFor { activeState().elapsedMs == hours * HOUR_MS }
            assertNull(activeState().warning)
        }
        // The policy loop renews the wake lock (each 50 ms in this test, each minute in the app).
        waitFor { platform.wakeLocks.get() >= 3 }
        assertTrue(platform.wakeLockHeld)
        // A paused recording does not advance its clock, and the lock is still renewed.
        controller.pause()
        waitFor { activeState().pause != null }
        val paused = platform.wakeLocks.get()
        waitFor { platform.wakeLocks.get() >= paused + 2 }
        controller.resume()
        waitFor { activeState().pause == null }
        // The hint is the space only: 64 GiB at 6000 B/s.
        val spare = (64L shl 30) - Limits.MIN_RECORDING_FREE_BYTES
        assertEquals(spare * 1000 / RecordingPolicy.OPUS_BYTES_PER_SECOND, activeState().space.spaceMs)

        controller.finish(null)
        val ended = awaitEnded()
        // More than one transcription takes: saved as Audio only and never queued.
        assertEquals(RecordingState.Ended(active.noteId, RecordingOutcome.Saved(AfterFinish.AUDIO_ONLY)), ended)
        assertEquals(100 * HOUR_MS, row(active.noteId)?.durationMs)
        assertTrue("too long to transcribe: no hand-off", handoffs.isEmpty())
        assertFalse(platform.wakeLockHeld)
    }

    @Test fun anAacFileStopsAtItsSizeLimitAndSavesTheAudio() = runBlocking {
        capture.format = CaptureFormat.M4A
        val active = startRecording("Vecchio telefono")
        assertEquals(CaptureFormat.M4A, active.format)
        val file = capture.latest.file
        // 10 minutes of room left in the file: the hint counts the file, not the free space.
        RandomAccessFile(file, "rw").use { it.setLength(RecordingPolicy.AAC_MAX_FILE_BYTES - 600 * 4000L) }
        capture.latest.elapsedMs = 70 * HOUR_MS
        waitFor { activeState().elapsedMs == 70 * HOUR_MS }
        assertTrue(activeState().space.spaceMs <= 10 * 60_000L)
        waitFor { activeState().warning != null }
        assertTrue("the file limit warns first", activeState().warning is RecordingWarning.FileLimitNear)

        RandomAccessFile(file, "rw").use { it.setLength(RecordingPolicy.AAC_MAX_FILE_BYTES) }
        val ended = awaitEnded()
        assertEquals(RecordingState.Ended(active.noteId, RecordingOutcome.StoppedAtFileLimit), ended)
        assertEquals(AudioKind.PLAIN, row(active.noteId)?.audioKind)
        assertTrue("a file-limit stop never enqueues", handoffs.isEmpty())
        assertTrue("the audio is kept", file.length() > 0)
    }

    @Test fun microphoneFailureSavesTheAudioAndNeverEnqueues() = runBlocking {
        val active = startRecording("Guasto")
        capture.latest.fail()
        val ended = awaitEnded()
        assertEquals(RecordingState.Ended(active.noteId, RecordingOutcome.MicrophoneFailed), ended)
        assertEquals(AudioKind.PLAIN, row(active.noteId)?.audioKind)
        assertTrue(handoffs.isEmpty())
    }

    @Test fun withoutConsentTheDownloadIsAskedOrTheNoteStaysAudioOnly() = runBlocking {
        consent.consented = false
        val asked = startRecording("Prima")
        controller.finish()
        assertEquals(RecordingOutcome.Saved(AfterFinish.ASK_CONSENT), awaitEnded().outcome)

        consent.declined = true
        controller.dismissEnded()
        startRecording("Seconda")
        controller.finish()
        assertEquals(RecordingOutcome.Saved(AfterFinish.AUDIO_ONLY), awaitEnded().outcome)
        assertTrue(handoffs.isEmpty())
        assertEquals(AudioKind.PLAIN, row(asked.noteId)?.audioKind)
    }

    // Pauses. -----------------------------------------------------------------------------------------------------

    @Test fun aSilencedCaptureStaysPausedUntilTheUserResumes() = runBlocking {
        startRecording("Telefonata")
        val mic = capture.latest

        platform.watch.silence(true)
        waitFor { activeState().pause == RecordingPause.OTHER_APP }
        assertTrue(mic.paused)

        platform.watch.silence(false)
        controller.awaitPosted()
        assertEquals(RecordingPause.OTHER_APP, activeState().pause)
        assertTrue("the end of the silence does not resume", mic.paused)

        controller.resume()
        waitFor { activeState().pause == null }
        assertFalse(mic.paused)
        assertEquals(1, platform.watch.resumed.get())

        controller.pause()
        waitFor { activeState().pause == RecordingPause.USER }
        platform.watch.silence(true)
        controller.awaitPosted()
        assertEquals("a user pause stays a user pause", RecordingPause.USER, activeState().pause)
    }

    // Failed starts. ------------------------------------------------------------------------------------------------

    @Test fun couldNotStartRemovesTheRow() = runBlocking {
        capture.refuse = true
        assertTrue(controller.start("Microfono occupato"))
        assertEquals(RecordingState.Ended(null, RecordingOutcome.CouldNotStart), awaitEnded())
        assertTrue(rows().isEmpty())
        assertTrue(paths.notes.listFiles().orEmpty().isEmpty())
        assertFalse(platform.wakeLockHeld)
        assertNull(controller.activeNoteId.value)

        capture.refuse = false
        platform.refuse = true
        controller.dismissEnded()
        assertTrue(controller.start("Servizio rifiutato"))
        assertEquals(RecordingOutcome.CouldNotStart, awaitEnded().outcome)
        assertTrue(rows().isEmpty())
        assertTrue(paths.notes.listFiles().orEmpty().isEmpty())
    }

    @Test fun oneRecordingAtATime() = runBlocking {
        startRecording("Uno")
        assertFalse(controller.start("Due"))
        controller.finish()
        awaitEnded()
        assertTrue(controller.start("Tre"))
        awaitActive()
        controller.finish()
        awaitEnded()
        assertEquals(2, rows().size)
    }

    // Levels. -----------------------------------------------------------------------------------------------------

    @Test fun levelsArePooledIntoBarsThatFollowTheCapturedTime() = runBlocking {
        startRecording("Livelli")
        val mic = capture.latest
        mic.elapsedMs = 1_000
        mic.amplitude = 16_384
        val loud = withTimeout(TIMEOUT_MS) { controller.levels.first { it.last() > 0.49f } }
        assertEquals(RecordingController.BARS, loud.size)

        mic.amplitude = 0
        mic.elapsedMs = 1_000 + 3 * RecordingController.BAR_MS
        val shifted = withTimeout(TIMEOUT_MS) { controller.levels.first { it[RecordingController.BARS - 4] > 0.49f } }
        assertEquals(0f, shifted.last())
    }

    // Helpers. ----------------------------------------------------------------------------------------------------

    private suspend fun startRecording(title: String): RecordingState.Active {
        assertTrue(controller.start(title))
        return awaitActive()
    }

    private suspend fun awaitActive(): RecordingState.Active = withTimeout(TIMEOUT_MS) {
        controller.state.first { it is RecordingState.Active || it is RecordingState.Ended } as? RecordingState.Active
            ?: error("The recording did not start: ${controller.state.value}")
    }

    private suspend fun awaitEnded(): RecordingState.Ended =
        withTimeout(TIMEOUT_MS) { controller.state.first { it is RecordingState.Ended } as RecordingState.Ended }

    private fun activeState(): RecordingState.Active =
        controller.state.value as? RecordingState.Active ?: error("Not recording: ${controller.state.value}")

    private suspend fun row(noteId: String): NoteEntity? = graph.awaitCatalogue().database.notes().note(noteId)

    private suspend fun rows(): List<NoteEntity> = graph.awaitCatalogue().database.notes().all()

    private suspend fun waitFor(condition: suspend () -> Boolean) = withTimeout(TIMEOUT_MS) {
        while (!condition()) delay(POLL_MS)
    }

    private inner class CountingNotes(private val inner: RecordingNotes) : RecordingNotes by inner {
        override suspend fun rename(noteId: String, title: String) {
            renames.incrementAndGet()
            inner.rename(noteId, title)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val HOUR_MS = 3_600_000L
        const val POLL_MS = 10L
        const val DEBOUNCE_MS = 600L
    }
}

/** A capture whose clock, amplitude and failures the test sets. Writes a few bytes, so the file is not empty. */
class FakeCapture(override val file: File, private val onError: () -> Unit) : MicrophoneCapture {
    @Volatile override var elapsedMs = 0L

    @Volatile override var amplitude = 0

    @Volatile override var paused = false
        private set
    override val opus = true

    /** When set, [stop] waits for it. */
    @Volatile var gate: CountDownLatch? = null

    @Volatile var stopThread: Thread? = null

    init {
        file.writeBytes(ByteArray(CAPTURED_BYTES))
    }

    override fun togglePause() {
        paused = !paused
    }

    override suspend fun stop(): File {
        stopThread = Thread.currentThread()
        gate?.await(GATE_SECONDS, TimeUnit.SECONDS)
        return file
    }

    override fun release() = Unit

    /** The capture thread failed. */
    fun fail() = onError()

    private companion object {
        const val CAPTURED_BYTES = 100
        const val GATE_SECONDS = 10L
    }
}

class FakeCaptureSource : CaptureSource {
    @Volatile var refuse = false

    @Volatile var format = CaptureFormat.OPUS

    @Volatile lateinit var latest: FakeCapture

    override suspend fun format(): CaptureFormat = format

    override fun start(file: File, onError: () -> Unit): MicrophoneCapture {
        check(!refuse) { "Microphone unavailable" }
        return FakeCapture(file, onError).also { latest = it }
    }
}

class FakeSilenceWatch(private val onSilenced: (Boolean) -> Unit) : SilenceWatch {
    val resumed = AtomicInteger()

    @Volatile var closed = false

    fun silence(silenced: Boolean) = onSilenced(silenced)

    override fun resumed() {
        resumed.incrementAndGet()
    }

    override fun close() {
        closed = true
    }
}

/** By default the service start reports the foreground at once, as RecordingService does after startForeground. */
class FakePlatform : RecordingPlatform {
    lateinit var controller: () -> RecordingController

    @Volatile var refuse = false

    @Volatile var usable = 64L shl 30

    @Volatile var wakeLockHeld = false
    val wakeLocks = AtomicInteger()

    @Volatile lateinit var watch: FakeSilenceWatch

    /** False: the test runs RecordingService itself, which reports the foreground. */
    @Volatile var reportForeground = true
    val starts = AtomicInteger()

    override fun startService(): Boolean {
        if (refuse) return false
        starts.incrementAndGet()
        if (reportForeground) controller().onServiceForeground()
        return true
    }

    override fun acquireWakeLock() {
        wakeLocks.incrementAndGet()
        wakeLockHeld = true
    }

    override fun releaseWakeLock() {
        wakeLockHeld = false
    }

    override fun usableBytes(directory: File): Long = usable

    override fun watchSilence(capture: MicrophoneCapture, onSilenced: (Boolean) -> Unit): SilenceWatch =
        FakeSilenceWatch(onSilenced).also { watch = it }
}

class FakeConsent : RecordingConsent {
    @Volatile var consented = true

    @Volatile var declined = false

    override suspend fun consented(): Boolean = consented

    override suspend fun declined(): Boolean = declined
}
