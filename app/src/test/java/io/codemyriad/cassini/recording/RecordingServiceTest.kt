package io.codemyriad.cassini.recording

import android.Manifest
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.CassiniApplication
import io.codemyriad.cassini.GraphFactory
import io.codemyriad.cassini.core.audio.Limits
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.models.ModelFetcher
import io.codemyriad.cassini.processing.FakeVisibility
import io.codemyriad.cassini.processing.ProcessingNotifications
import io.codemyriad.cassini.testing.NoUncaughtCoroutines
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList

/** The microphone foreground lifetime of architecture 5.8 on Robolectric (SDK 35), over fakes for the capture. */
@RunWith(RobolectricTestRunner::class)
class RecordingServiceTest {
    @get:Rule(order = Int.MIN_VALUE)
    val uncaught = NoUncaughtCoroutines()

    @get:Rule val folder = TemporaryFolder()

    private val app: CassiniApplication = ApplicationProvider.getApplicationContext()
    private val capture = FakeCaptureSource()
    private val platform = FakePlatform().apply { reportForeground = false }
    private val serviceRequested = CompletableDeferred<Unit>()
    private val visibility = FakeVisibility()

    /** Each hand-off, with whether this service had left the foreground by then. */
    private val handoffs = CopyOnWriteArrayList<Pair<String, Boolean>>()
    private var service: RecordingService? = null

    private val graph: AppGraph get() = app.graph
    private val controller: RecordingController get() = graph.recording

    @Before fun setUp() {
        val paths = AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder())
        CassiniApplication.graphFactory = GraphFactory { context, _ ->
            val recording = AppGraph.RecordingHooks(
                capture = capture,
                platform = object : RecordingPlatform by platform {
                    override fun startService(): Boolean {
                        val accepted = this@RecordingServiceTest.platform.startService()
                        if (accepted) serviceRequested.complete(Unit)
                        return accepted
                    }
                },
                handoff = {
                    TranscriptionHandoff { noteId ->
                        handoffs += noteId to (service?.let { shadowOf(it).isForegroundStopped } ?: true)
                        true
                    }
                },
                consent = FakeConsent(),
                timing = RecordingController.Timing(tickMs = 10, serviceWaitMs = 5_000),
            )
            AppGraph(
                context,
                AppGraph.Config(
                    paths = paths,
                    dispatchers = CassiniDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Unconfined),
                    fetcher = ModelFetcher { _, _, _ -> fail("No model is fetched in this test") },
                    network = { true },
                    processing = AppGraph.ProcessingHooks(visibility = visibility),
                    recording = recording,
                ),
            )
        }
        platform.controller = { graph.recording }
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { graph.ready.await() }
    }

    @After fun tearDown() {
        graph.close()
        CassiniApplication.graphFactory = null
    }

    @Test fun theNotificationPausesAndFinishesThenTheServiceLeavesAfterTheHandOff() {
        val started = startService()
        assertEquals(RecordingNotifications.RECORDING_ID, shadowOf(started).lastForegroundNotificationId)
        awaitRecordingState { it is RecordingState.Active }
        val noteId = (controller.state.value as RecordingState.Active).noteId
        // The first notification comes from startForeground; the one with the actions is built off the main thread.
        until { ongoing()?.title() == "Recording" && ongoing()?.actionTitles() == listOf("Pause", "Finish") }

        started.onStartCommand(RecordingService.intent(app, RecordingService.ACTION_PAUSE), 0, 2)
        until { ongoing()?.title() == "Recording paused" }
        assertEquals(listOf("Resume", "Finish"), ongoing()?.actionTitles())
        assertTrue(capture.latest.paused)

        started.onStartCommand(RecordingService.intent(app, RecordingService.ACTION_FINISH), 0, 3)
        until { shadowOf(started).isStoppedBySelf }
        assertTrue(shadowOf(started).isForegroundStopped)
        assertEquals("the hand-off ran while the service was in the foreground", listOf(noteId to false), handoffs)
        val row = runBlocking { graph.awaitCatalogue().database.notes().note(noteId) }
        assertEquals(AudioKind.PLAIN, row?.audioKind)
    }

    @Test fun aRefusedForegroundEndsTheStart() {
        assertTrue(controller.start("Nota"))
        awaitServiceRequest()
        val refused = Robolectric.buildService(RecordingService::class.java).create().get()
        shadowOf(refused).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("background start"))

        val result = refused.onStartCommand(RecordingService.startIntent(app), 0, 1)
        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(shadowOf(refused).isStoppedBySelf)
        awaitRecordingState { it is RecordingState.Ended }
        assertEquals(RecordingOutcome.CouldNotStart, (controller.state.value as RecordingState.Ended).outcome)
        assertTrue(runBlocking { graph.awaitCatalogue().database.notes().all() }.isEmpty())
    }

    @Test fun aStorageStopInTheBackgroundIsNotified() {
        val started = startService()
        awaitRecordingState { it is RecordingState.Active }
        val noteId = (controller.state.value as RecordingState.Active).noteId

        platform.usable = Limits.MIN_RECORDING_FREE_BYTES - 1
        until { shadowOf(started).isStoppedBySelf }
        val result = shadowOf(app.getSystemService(NotificationManager::class.java))
            .getNotification(noteId, ProcessingNotifications.RESULT_ID)
        assertEquals("Recording stopped", result?.title())
        assertTrue(handoffs.isEmpty())
    }

    @Test fun aStaleNotificationActionDoesNotStartTheForeground() {
        val stale = Robolectric.buildService(RecordingService::class.java).create().get()
        val result = stale.onStartCommand(RecordingService.intent(app, RecordingService.ACTION_FINISH), 0, 1)
        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(shadowOf(stale).isStoppedBySelf)
        assertFalse(shadowOf(stale).lastForegroundNotificationId == RecordingNotifications.RECORDING_ID)
    }

    /** start() as the Recording screen calls it, then the service start that the platform asked for. */
    private fun startService(): RecordingService {
        assertTrue(controller.start("Meeting"))
        awaitServiceRequest()
        val created = Robolectric.buildService(RecordingService::class.java).create().get()
        service = created
        assertEquals(Service.START_NOT_STICKY, created.onStartCommand(RecordingService.startIntent(app), 0, 1))
        return created
    }

    private fun ongoing(): Notification? = shadowOf(app.getSystemService(NotificationManager::class.java))
        .allNotifications
        .firstOrNull { it.channelId == "recording" }

    private fun Notification.title(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun Notification.actionTitles(): List<String> = actions.orEmpty().map { it.title.toString() }

    private fun awaitServiceRequest() {
        runBlocking { withTimeout(TIMEOUT_MS) { serviceRequested.await() } }
        assertEquals("the platform requested one service start", 1, platform.starts.get())
    }

    private fun awaitRecordingState(condition: (RecordingState) -> Boolean) {
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                controller.state.first { condition(it) }
                controller.awaitPosted()
            }
        }
    }

    /** Runs the main looper (the service's lifecycleScope) until [condition] holds. */
    private fun until(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 20L
    }
}
