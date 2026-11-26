package io.codemyriad.cassini.recorder

import android.Manifest
import android.app.Application
import android.app.Notification
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.CassiniApplication
import io.codemyriad.cassini.GraphFactory
import io.codemyriad.cassini.core.recorder.FakeRecorder
import io.codemyriad.cassini.core.recorder.XrecAuth
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.models.ModelFetcher
import io.codemyriad.cassini.testing.MainDispatchEvents
import io.codemyriad.cassini.testing.NoUncaughtCoroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class RecorderSyncServiceTest {
    @get:Rule(order = Int.MIN_VALUE)
    val uncaught = NoUncaughtCoroutines()

    @get:Rule val folder = TemporaryFolder()

    @get:Rule val mainDispatches = MainDispatchEvents()

    private val app: CassiniApplication = ApplicationProvider.getApplicationContext()
    private val key = ByteArray(XrecAuth.KEY_BYTES) { 7 }
    private lateinit var fakeRecorder: FakeRecorder
    private lateinit var radio: FakeRadio
    private lateinit var paths: AppPaths

    @Before fun setUp() {
        fakeRecorder = FakeRecorder(key = key.copyOf())
        radio = FakeRadio(fakeRecorder)
        paths = AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder())
        CassiniApplication.graphFactory = GraphFactory { context, _ ->
            AppGraph(
                context,
                AppGraph.Config(
                    paths = paths,
                    dispatchers = CassiniDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Main),
                    fetcher = ModelFetcher { _, _, _ -> fail("No model is fetched in this test") },
                    network = { false },
                    recorder = { ctx, graph -> RecorderFeature(ctx, graph, radio = radio, vault = FakeVault()) },
                ),
            )
        }
        shadowOf(app as Application).grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }

    @After fun tearDown() {
        app.graph.close { shadowOf(Looper.getMainLooper()).idle() }
        radio.device.close()
        CassiniApplication.graphFactory = null
    }

    @Test fun everyStartCommandCallsStartForegroundAgainWhileSyncing() {
        fakeRecorder.files["REC_0001.wav"] = recording(10_000)
        val downloading = CountDownLatch(1)
        val release = CountDownLatch(1)
        fakeRecorder.beforeGets = {
            downloading.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        runBlocking { app.graph.recorder.store.bound(key) }
        val syncJob = app.graph.recorder.runner.syncNow()
        try {
            assertTrue(downloading.await(5, TimeUnit.SECONDS))
            val service = Robolectric.buildService(RecorderSyncService::class.java).create().get()
            service.onStartCommand(RecorderSyncService.intent(app), 0, 1)
            val first = shadowOf(service).lastForegroundNotification
            assertNotNull(first)
            assertFalse(shadowOf(service).isStoppedBySelf)

            // Android sets fgRequired on every startForegroundService call, even when already in the foreground.
            service.onStartCommand(RecorderSyncService.intent(app), 0, 2)
            val second = shadowOf(service).lastForegroundNotification
            assertNotNull(second)
            assertNotSame("Second onStartCommand must call startForeground again", first, second)

            release.countDown()
            runBlocking { withTimeout(5_000) { syncJob.await() } }
            mainDispatches.await(5_000) { shadowOf(service).isStoppedBySelf }
            assertTrue(shadowOf(service).isForegroundStopped)
        } finally {
            release.countDown()
        }
    }

    @Test fun aCompletedRunDoesNotLeaveTheServiceWaitingForStartGrace() {
        radio.reachable = false
        runBlocking {
            app.graph.recorder.store.bound(key)
            assertEquals(SyncOutcome.Unreachable, app.graph.recorder.runner.syncNow().await())
        }
        val service = Robolectric.buildService(RecorderSyncService::class.java).create().get()
        service.onStartCommand(RecorderSyncService.intent(app), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "Service should stop immediately when no sync is active or pending",
            shadowOf(service).isStoppedBySelf,
        )
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test fun aBusyAttemptWhileAnotherSyncMovesFilesDoesNotStartTheServiceAgain() = runBlocking {
        val localRecorder = FakeRecorder(key = key.copyOf())
        localRecorder.files["REC_0001.wav"] = recording(10_000)
        val localRadio = FakeRadio(localRecorder)
        val library = FakeLibrary()
        val (sync, store) = recorderSync(folder.newFolder(), localRadio, library)
        store.bound(key)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val starts = AtomicInteger()
        val runner = RecorderRunner(scope, sync) {
            starts.incrementAndGet()
            true
        }
        val downloading = CountDownLatch(1)
        val release = CountDownLatch(1)
        localRecorder.beforeGets = {
            downloading.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        try {
            val active = runner.attempt(SyncMode.FULL)
            assertTrue(downloading.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) {
                sync.activity.first { it is RecorderActivity.Syncing && it.step == SyncStep.DOWNLOADING }
            }
            withTimeout(5_000) { while (starts.get() < 1) delay(10) }

            assertEquals(SyncOutcome.Busy, runner.attempt(SyncMode.UPLOADS).await())
            delay(100)
            assertEquals("A Busy attempt must not fire startForegroundService", 1, starts.get())

            release.countDown()
            assertTrue(active.await() is SyncOutcome.Done)
        } finally {
            release.countDown()
            scope.coroutineContext.job.cancelAndJoin()
            localRadio.device.close()
        }
    }

    @Test fun waitingNotificationSummarizesTranscribingAndPendingUploads() {
        val tx = RecorderNotifications.buildWaiting(app, transcribing = 2, pendingUploads = 1)
        assertEquals(
            "Transcribing 2 recordings for the recorder…",
            tx.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )

        val queued = RecorderNotifications.buildWaiting(app, transcribing = 0, pendingUploads = 3)
        assertEquals(
            "Sending 3 queued transcripts to the recorder…",
            queued.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }
}
