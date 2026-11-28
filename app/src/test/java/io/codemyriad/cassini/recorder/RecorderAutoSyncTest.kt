package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.CwxSpeaker
import io.codemyriad.cassini.core.recorder.CwxTranscript
import io.codemyriad.cassini.core.recorder.CwxWord
import io.codemyriad.cassini.core.recorder.FakeRecorder
import io.codemyriad.cassini.core.recorder.XrecAuth
import io.codemyriad.cassini.testing.NoUncaughtCoroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** "Auto sync": quiet growing waits while the recorder does not answer. */
@RunWith(RobolectricTestRunner::class)
class RecorderAutoSyncTest {
    @get:Rule(order = Int.MIN_VALUE)
    val uncaught = NoUncaughtCoroutines()

    @get:Rule val folder = TemporaryFolder()

    private val key = ByteArray(XrecAuth.KEY_BYTES) { 3 }
    private val radio = FakeRadio(FakeRecorder(key = key.copyOf()))
    private val library = FakeLibrary()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val waits: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val proceed = Channel<Unit>(Channel.UNLIMITED)
    private val services = Collections.synchronizedList(mutableListOf<Unit>())

    /** The loop and its syncs write into the temporary folder: they end before JUnit deletes it. */
    @After fun tearDown() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        radio.device.close()
    }

    private fun start(syncWhileRecording: Boolean = false): RecorderSync {
        val (sync, store) = recorderSync(folder.root, radio, library)
        val runner = RecorderRunner(scope, sync) { services.add(Unit) }
        RecorderAutoSync(scope, store.settings, runner) { delay ->
            waits += delay
            proceed.receive()
        }.start()
        runBlocking {
            store.bound(key)
            store.update { it.copy(autoSync = true, syncWhileRecording = syncWhileRecording) }
        }
        return sync
    }

    @Test fun failedAttemptsWaitLongerAndShowNoServiceUntilFilesMove() = runBlocking {
        radio.reachable = false
        radio.device.files["REC_0001.wav"] = recording(2_000)
        start()
        withTimeout(TIMEOUT_MS) { while (waits.size < 1) delay(10) }
        proceed.send(Unit)
        withTimeout(TIMEOUT_MS) { while (waits.size < 2) delay(10) }
        assertEquals(listOf(AutoSyncBackoff.delayMs(1), AutoSyncBackoff.delayMs(2)), waits.toList())
        assertTrue(services.isEmpty())

        // The recorder is in Bluetooth phone mode now: the attempt downloads, starts the service, and the wait resets.
        radio.reachable = true
        proceed.send(Unit)
        withTimeout(TIMEOUT_MS) { while (waits.size < 3) delay(10) }
        assertEquals(AutoSyncBackoff.INTERVAL_MS, waits[2])
        assertEquals(1, services.size)

        // Nothing new: the sync only checks, shows no notification, and the waits grow.
        proceed.send(Unit)
        withTimeout(TIMEOUT_MS) { while (waits.size < 4) delay(10) }
        proceed.send(Unit)
        withTimeout(TIMEOUT_MS) { while (waits.size < 5) delay(10) }
        assertEquals(listOf(AutoSyncBackoff.delayMs(0, idle = 1), AutoSyncBackoff.delayMs(0, idle = 2)), waits.drop(3))
        assertEquals(1, services.size)
    }

    /** The firmware that syncs while it records, recording REC_0002.wav; REC_0001.wav is finished. */
    private fun recordingNow() {
        radio.device.syncsWhileRecording = true
        radio.device.recording = true
        radio.device.files["REC_0001.wav"] = recording(2_000, seed = 1)
        radio.device.files["REC_0002.wav"] = recording(2_000, seed = 2)
    }

    @Test fun whileTheRecorderRecordsAutoSyncPollsEveryTwoMinutesUntilTheRecordingEnds() = runBlocking {
        recordingNow()
        start(syncWhileRecording = true)
        // The finished recording goes; the active one waits, and the next check comes after 2 minutes.
        withTimeout(TIMEOUT_MS) { while (waits.size < 1) delay(10) }
        assertEquals(listOf("REC_0001.wav"), library.titles.values.toList())
        proceed.send(Unit)
        // Nothing new during the recording: still 2 minutes, no idle doubling.
        withTimeout(TIMEOUT_MS) { while (waits.size < 2) delay(10) }
        assertEquals(listOf(RecordingPoll.INTERVAL_MS, RecordingPoll.INTERVAL_MS), waits.toList())

        // The recording ends: the next check takes its file, and the usual waits come back.
        radio.device.recording = false
        proceed.send(Unit)
        withTimeout(TIMEOUT_MS) { while (waits.size < 3) delay(10) }
        assertEquals(listOf("REC_0001.wav", "REC_0002.wav"), library.titles.values.toList())
        assertEquals(AutoSyncBackoff.INTERVAL_MS, waits[2])
    }

    @Test fun withTheSettingOffARecordingRecorderIsCheckedWithTheUsualGrowingWaits() = runBlocking {
        recordingNow()
        start(syncWhileRecording = false)
        withTimeout(TIMEOUT_MS) { while (waits.size < 1) delay(10) }
        proceed.send(Unit)
        withTimeout(TIMEOUT_MS) { while (waits.size < 2) delay(10) }
        assertEquals(listOf(AutoSyncBackoff.delayMs(0, idle = 1), AutoSyncBackoff.delayMs(0, idle = 2)), waits.toList())
        assertTrue(library.imported.isEmpty())
    }

    @Test fun wakeAttemptRetriesAfterSdpPokeDisconnectAndHoldsServiceUntilRoundTripEnds() = runBlocking {
        radio.reachable = false
        radio.device.files["REC_0001.wav"] = recording(2_000)
        val (sync, store) = recorderSync(folder.root, radio, library)
        store.bound(key)
        library.activeCount = 1
        val runner = RecorderRunner(scope, sync) { services.add(Unit) }

        val wake = runner.wakeAttempt(retryDelayMs = 80L, roundTripHoldMs = 5_000L)
        delay(30L)
        // After the recorder's SDP poke finishes, its SPP server accepts the retry.
        radio.reachable = true
        withTimeout(TIMEOUT_MS) { while (library.imported.isEmpty()) delay(10) }
        assertTrue(runner.serviceActive.value)
        // Once transcription and sidecar upload finish, the foreground service hold releases promptly.
        library.activeCount = 0
        val outcome = withTimeout(TIMEOUT_MS) { wake.await() }
        assertTrue(outcome is SyncOutcome.Done)
        assertEquals(false, runner.serviceActive.value)
        assertEquals(1, services.size)
    }

    @Test fun wakeAttemptQueuesBehindRunningUploadsSyncInsteadOfDroppingAsBusy() = runBlocking {
        radio.device.files["REC_0001.wav"] = recording(2_000, seed = 1)
        val (sync, store) = recorderSync(folder.root, radio, library)
        store.bound(key)
        assertTrue(sync.sync(SyncMode.FULL) is SyncOutcome.Done)

        val transcript = CwxTranscript(
            "One",
            2_000,
            listOf(CwxSpeaker("Ada", 0x679CFF)),
            listOf(CwxWord(0, 400, 0, "Hello")),
        )
        library.ready["note-1"] = RecorderTranscript("v1", transcript)
        val putEntered = CountDownLatch(1)
        val releasePut = CountDownLatch(1)
        radio.device.beforePut = { offset ->
            if (offset == 0L) {
                putEntered.countDown()
                releasePut.await(5, TimeUnit.SECONDS)
            }
        }
        val push = scope.async { sync.sync(SyncMode.UPLOADS) }
        assertTrue(putEntered.await(5, TimeUnit.SECONDS))
        assertTrue(sync.isBusy())
        assertTrue(sync.canAcceptFullSync())

        radio.device.files["REC_0002.wav"] = recording(2_500, seed = 2)
        val runner = RecorderRunner(scope, sync) { services.add(Unit) }
        val wake = runner.wakeAttempt(retryDelayMs = 0L, roundTripHoldMs = 0L)
        withTimeout(TIMEOUT_MS) {
            while (sync.canAcceptFullSync()) delay(5L)
        }
        assertEquals(false, sync.canAcceptFullSync())

        radio.device.beforePut = null
        releasePut.countDown()
        assertTrue(withTimeout(TIMEOUT_MS) { push.await() } is SyncOutcome.Done)
        val outcome = withTimeout(TIMEOUT_MS) { wake.await() } as SyncOutcome.Done
        assertEquals(SyncOutcome.Done(1, 0, 0, false), outcome.copy(speed = null))
        assertEquals(2, library.imported.size)
        assertEquals(1, services.size)
    }

    @Test fun fullAttemptHoldsForegroundServiceUntilRoundTripTranscriptionEnds() = runBlocking {
        radio.device.files["REC_0001.wav"] = recording(2_000)
        val (sync, store) = recorderSync(folder.root, radio, library)
        store.bound(key)
        library.activeCount = 1
        val runner = RecorderRunner(scope, sync) { services.add(Unit) }

        val run = runner.attempt(SyncMode.FULL, roundTripHoldMs = 5_000L)
        withTimeout(TIMEOUT_MS) { while (library.imported.isEmpty()) delay(10) }
        assertTrue(runner.serviceActive.value)

        library.activeCount = 0
        val outcome = withTimeout(TIMEOUT_MS) { run.await() } as SyncOutcome.Done
        assertEquals(1, outcome.downloaded)
        assertEquals(false, runner.serviceActive.value)
        assertEquals(1, services.size)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
