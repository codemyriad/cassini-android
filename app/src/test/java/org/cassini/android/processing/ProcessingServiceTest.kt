package org.cassini.android.processing

import android.Manifest
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.cassini.android.AppGraph
import org.cassini.android.CassiniApplication
import org.cassini.android.GraphFactory
import org.cassini.core.library.JobState
import org.cassini.core.library.PauseReason
import org.cassini.data.AppPaths
import org.cassini.data.CassiniDispatchers
import org.cassini.data.models.ModelFetcher
import org.cassini.data.settings.QueueHold
import org.cassini.engine.job.JobOutcome
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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** The foreground lifetime of architecture 5.7 on Robolectric (SDK 35), over a graph with fake jobs. */
@RunWith(RobolectricTestRunner::class)
class ProcessingServiceTest {
    @get:Rule val folder = TemporaryFolder()

    private val app: CassiniApplication = ApplicationProvider.getApplicationContext()
    private val created = CopyOnWriteArrayList<FakeJob>()
    private val notices = RecordingNotices()
    private val fixture = File(checkNotNull(System.getProperty("cassini.fixtures")), "audio/fleurs-it-short.opus")
    private lateinit var paths: AppPaths

    private val graph: AppGraph get() = app.graph

    @Before fun setUp() {
        paths = AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder())
        CassiniApplication.graphFactory = GraphFactory { context, _ ->
            val hooks = AppGraph.ProcessingHooks(
                jobs = { g -> ProcessingJobFactory { id -> FakeJob(id, g).also { created += it } } },
                // The test starts the service itself.
                starter = { ServiceStarter { true } },
                notices = notices,
                visibility = FakeVisibility(),
            )
            AppGraph(
                context,
                AppGraph.Config(
                    paths = paths,
                    dispatchers = CassiniDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Unconfined),
                    fetcher = ModelFetcher { _, _, _ -> fail("No model is fetched in this test") },
                    network = { true },
                    processing = hooks,
                ),
            )
        }
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun tearDown() {
        graph.close()
        CassiniApplication.graphFactory = null
    }

    @Test fun theServiceRunsInTheForegroundUntilTheDrainJoins() {
        val id = queuedNote()
        val service = Robolectric.buildService(ProcessingService::class.java).create().get()

        val result = service.onStartCommand(ProcessingService.drainIntent(app), 0, 1)
        assertEquals(Service.START_REDELIVER_INTENT, result)
        val shadow = shadowOf(service)
        assertEquals(ProcessingNotifications.PROCESSING_ID, shadow.lastForegroundNotificationId)
        assertTrue(graph.processing.state.value.serviceForeground)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, ProcessingService.typeOf(ForegroundNeed.DATA_SYNC))
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            ProcessingService.typeOf(ForegroundNeed.MEDIA_PROCESSING),
        )

        val job = awaitJob()
        assertEquals(id, job.noteId)
        assertFalse("the service waits for the job", shadow.isStoppedBySelf)
        job.finish(JobOutcome.Done)
        until { shadow.isStoppedBySelf }
        assertTrue(shadow.isForegroundStopped)
        assertFalse(graph.processing.state.value.serviceForeground)
    }

    @Test fun aRefusedFirstStartLeavesTheQueueAndPostsReadyToTranscribe() {
        val id = queuedNote()
        val service = Robolectric.buildService(ProcessingService::class.java).create().get()
        shadowOf(service).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("background start"))

        val result = service.onStartCommand(ProcessingService.drainIntent(app), 0, 1)
        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(shadowOf(service).isStoppedBySelf)
        until { notices.ready.isNotEmpty() }
        assertEquals(listOf(id), notices.ready.toList())
        assertTrue(created.isEmpty())
        assertEquals(JobState.QUEUED, row(id)?.state)
    }

    @Test fun onTimeoutPausesBySystemAndStopsAtOnce() {
        val id = queuedNote()
        val other = queuedNote()
        val service = Robolectric.buildService(ProcessingService::class.java).create().get()
        service.onStartCommand(ProcessingService.drainIntent(app), 0, 1)
        val job = awaitJob()
        job.pauseOnStop = false
        until { graph.processing.state.value.running != null }

        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        val shadow = shadowOf(service)
        assertTrue("the service stops without waiting for the job", shadow.isStoppedBySelf)
        assertTrue(shadow.isForegroundStopped)
        val posted = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        assertTrue(
            posted.any { it.extras.getCharSequence(Notification.EXTRA_TITLE) == "Transcription paused by Android" },
        )
        until { job.stopReason.get() == PauseReason.SYSTEM && row(id)?.state == JobState.PAUSED }
        assertEquals(PauseReason.SYSTEM, row(id)?.pauseReason)
        assertEquals(QueueHold.SYSTEM, runBlocking { graph.settings.snapshot().queueHold })

        job.finish(JobOutcome.Paused(PauseReason.SYSTEM))
        until { graph.processing.state.value.let { !it.draining && it.running == null } }
        assertEquals(JobState.QUEUED, row(other)?.state)
        assertEquals(1, created.size)
    }

    @Test fun aRedeliveredResumeOrStopOnlyDrains() {
        val id = queuedNote()
        runBlocking {
            val jobs = graph.awaitCatalogue().jobs
            assertTrue(jobs.markPaused(id, PauseReason.INTERRUPTED, doneMs = 0, totalMs = 4_000, words = 0))
        }
        val attempts = row(id)?.attempts
        val service = Robolectric.buildService(ProcessingService::class.java).create().get()

        // After process death Android sends both commands again, with START_FLAG_REDELIVERY.
        service.onStartCommand(ProcessingService.resumeIntent(app, id), Service.START_FLAG_REDELIVERY, 1)
        val stopAll = processingIntent(app, ProcessingService.ACTION_STOP_ALL)
        service.onStartCommand(stopAll, Service.START_FLAG_REDELIVERY, 2)
        until { shadowOf(service).isStoppedBySelf }

        assertEquals("PAUSED jobs never resume on their own", JobState.PAUSED, row(id)?.state)
        assertEquals(PauseReason.INTERRUPTED, row(id)?.pauseReason)
        assertEquals(attempts, row(id)?.attempts)
        assertEquals(null, runBlocking { graph.settings.snapshot().queueHold })
        assertTrue(created.isEmpty())
    }

    private fun queuedNote(): String = runBlocking {
        graph.ready.await()
        graph.models.recordConsent()
        val catalogue = graph.awaitCatalogue()
        val id = catalogue.notes.createRecording("Riunione", 1_000, "opus")
        withContext(Dispatchers.IO) { fixture.copyTo(File(paths.note(id), "capture.opus")) }
        catalogue.notes.finishRecording(id, 4_000)
        catalogue.jobs.enqueue(id, org.cassini.core.library.JobKind.TRANSCRIBE)
        id
    }

    private fun row(id: String) = runBlocking { graph.awaitCatalogue().jobs.job(id) }

    private fun awaitJob(): FakeJob {
        until { created.isNotEmpty() && created[0].started.isCompleted }
        return created[0]
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
