package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.CwxTranscript
import io.codemyriad.cassini.core.recorder.FakeRecorder
import io.codemyriad.cassini.core.recorder.XrecAuth
import io.codemyriad.cassini.data.recorder.RecorderStore
import io.codemyriad.cassini.testing.NoUncaughtCoroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A new finished transcript goes to the recorder at once, with "Auto sync" off, and waits for an unreachable one. */
@RunWith(RobolectricTestRunner::class)
class RecorderTranscriptPushTest {
    @get:Rule(order = Int.MIN_VALUE)
    val uncaught = NoUncaughtCoroutines()

    @get:Rule val folder = TemporaryFolder()

    private val key = ByteArray(XrecAuth.KEY_BYTES) { 3 }
    private val radio = FakeRadio(FakeRecorder(key = key.copyOf()))
    private val library = FakeLibrary()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val waits: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val proceed = Channel<Unit>(Channel.UNLIMITED)
    private val transcribed = MutableStateFlow<Set<String>>(emptySet())
    private val checks = AtomicInteger()
    private lateinit var store: RecorderStore
    private lateinit var sync: RecorderSync

    /** When set, the push's check finds these notes waiting without asking the library (see the failing-sync test). */
    @Volatile private var pendingOverride: Set<String>? = null

    @After fun tearDown() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        radio.device.close()
    }

    /**
     * A bound recorder with [files] recordings (REC_0001.wav as note-1, and so on) imported by a sync now, "Auto sync"
     * off, then the push, once its first check ran. With [sentBefore], note-1 has its transcript on the recorder
     * already; with [readyBefore], note-1 has a transcript that the recorder does not have yet when the push starts.
     */
    private fun start(sentBefore: Boolean = false, readyBefore: Boolean = false, files: Int = 1) {
        val (sync, store) = recorderSync(folder.root, radio, library)
        this.store = store
        this.sync = sync
        for (index in 1..files) radio.device.files["REC_000$index.wav"] = recording(2_000, seed = index)
        runBlocking {
            store.bound(key)
            assertTrue(sync.sync() is SyncOutcome.Done)
            if (sentBefore) {
                library.ready["note-1"] = TRANSCRIPT
                assertEquals(1, (sync.sync() as SyncOutcome.Done).uploaded)
            }
            if (readyBefore) transcriptReady()
            assertFalse(store.read().autoSync)
        }
        val runner = RecorderRunner(scope, sync) { true }
        val pending: suspend () -> Set<String> = {
            (pendingOverride ?: sync.pendingUploads()).also { checks.incrementAndGet() }
        }
        RecorderTranscriptPush(scope, store.settings, runner, transcribed, pending) { delay ->
            waits += delay
            proceed.receive()
        }.start()
        runBlocking { withTimeout(TIMEOUT_MS) { while (checks.get() < 1) delay(10) } }
    }

    private fun transcriptReady() {
        library.ready["note-1"] = TRANSCRIPT
        transcribed.value = setOf("note-1")
    }

    private suspend fun uploaded(name: String = "REC_0001.wav"): Boolean = store.read().current?.recordings.orEmpty()
        .single { it.recording.name == name }.recording.transcriptUploadedVersion != null

    /** Lets the push's waits run until it has waited [count] times. */
    private suspend fun waitsUpTo(count: Int) {
        while (waits.size < count) {
            val seen = waits.size
            withTimeout(TIMEOUT_MS) { while (waits.size == seen) delay(10) }
            if (waits.size < count) proceed.send(Unit)
        }
    }

    @Test fun aReadyTranscriptGoesAtOnceWithAutoSyncOffAndWithoutADownload() = runBlocking {
        start()
        // A new recording on the recorder: the upload does not wait for its download, and does not download it.
        radio.device.files["REC_0002.wav"] = recording(50_000, seed = 2)
        val opens = radio.opens
        transcriptReady()
        withTimeout(TIMEOUT_MS) { while (!radio.device.files.containsKey("REC_0001.cwx")) delay(10) }
        withTimeout(TIMEOUT_MS) { while (!uploaded()) delay(10) }
        assertEquals(opens + 1, radio.opens)
        assertEquals(listOf("note-1"), library.imported.keys.toList())
        assertTrue(waits.isEmpty())
    }

    @Test fun anUnreachableRecorderIsTriedAgainWithGrowingWaitsUntilItTakesTheTranscript() = runBlocking {
        start()
        radio.reachable = false
        transcriptReady()
        withTimeout(TIMEOUT_MS) { while (waits.size < 1) delay(10) }
        proceed.send(Unit)
        withTimeout(TIMEOUT_MS) { while (waits.size < 2) delay(10) }
        proceed.send(Unit)
        withTimeout(TIMEOUT_MS) { while (waits.size < 3) delay(10) }
        assertEquals((1..3).map(TranscriptPushBackoff::delayMs), waits.toList())
        assertEquals(listOf(30_000L, 60_000L, 120_000L), waits.toList())

        // The user puts the recorder in Bluetooth phone mode: the next try sends the transcript, and the retries end.
        radio.reachable = true
        proceed.send(Unit)
        withTimeout(TIMEOUT_MS) { while (!uploaded()) delay(10) }
        assertTrue(radio.device.files.containsKey("REC_0001.cwx"))
        delay(SETTLE_MS)
        assertEquals(3, waits.size)
    }

    @Test fun nothingWaitingMeansNoAttemptAndNoRetry() = runBlocking {
        start(sentBefore = true)
        assertTrue(uploaded())
        val opens = radio.opens
        // The transcript is on the recorder already: its note in the set starts no link and no retry.
        transcribed.value = setOf("note-1")
        withTimeout(TIMEOUT_MS) { while (checks.get() < 2) delay(10) }
        // A transcript of a note that does not come from the recorder starts nothing either.
        transcribed.value = setOf("note-1", "note-elsewhere")
        delay(SETTLE_MS)
        assertEquals(opens, radio.opens)
        assertEquals(2, checks.get())
        assertTrue(waits.isEmpty())
    }

    @Test fun aTranscriptThatWaitsAtTheStartGoesWithoutANewTranscript() = runBlocking {
        start(readyBefore = true)
        // Nothing changes in the transcribed notes: the push's first check finds the transcript and sends it.
        withTimeout(TIMEOUT_MS) { while (!uploaded()) delay(10) }
        assertTrue(radio.device.files.containsKey("REC_0001.cwx"))
        assertEquals(setOf("note-1"), transcribed.value)
        delay(SETTLE_MS)
        assertTrue(waits.isEmpty())
    }

    @Test fun aTranscriptWhoseRecordingLeftTheRecorderEndsAfterOneConnectedSyncAndWaitsNoMore() = runBlocking {
        start()
        radio.device.files.remove("REC_0001.wav")
        val opens = radio.opens
        transcriptReady()
        // One upload-only sync connects, finds no recording to send to, and ends the push without a retry.
        withTimeout(TIMEOUT_MS) { while (radio.opens == opens) delay(10) }
        withTimeout(TIMEOUT_MS) { while (store.read().current!!.recordings.none { it.absent }) delay(10) }
        delay(SETTLE_MS)
        assertTrue(waits.isEmpty())
        assertEquals(opens + 1, radio.opens)
        assertFalse(uploaded())
        // Nothing waits any more, so a new process starts no attempt for it.
        assertEquals(emptySet<String>(), sync.pendingUploads())
    }

    @Test fun anUnreachableRecorderIsTriedAtMostMaxFailuresTimes() = runBlocking {
        start()
        radio.reachable = false
        transcriptReady()
        waitsUpTo(TranscriptPushBackoff.MAX_FAILURES - 1)
        val checked = checks.get()
        proceed.send(Unit)
        // The last attempt fails too: no wait after it, and no attempt.
        withTimeout(TIMEOUT_MS) { while (checks.get() == checked) delay(10) }
        delay(SETTLE_MS)
        assertEquals(TranscriptPushBackoff.MAX_FAILURES - 1, waits.size)
        assertEquals(checked + 1, checks.get())
        assertFalse(uploaded())
    }

    @Test fun aSyncThatFailsTheSameWayEachTimeIsNotTriedForever() = runBlocking {
        start()
        // The phone fails each sync after the link is up (here, the library), the same way each time.
        library.failTranscripts = true
        pendingOverride = setOf("note-1")
        transcribed.value = setOf("note-1")
        waitsUpTo(TranscriptPushBackoff.MAX_SYNC_FAILURES - 1)
        proceed.send(Unit)
        delay(SETTLE_MS)
        assertEquals(TranscriptPushBackoff.MAX_SYNC_FAILURES - 1, waits.size)
        assertEquals(1 + TranscriptPushBackoff.MAX_SYNC_FAILURES, checks.get())
        assertEquals(RecorderSync.OUTCOME_FAILED, store.read().current!!.lastSync!!.outcome)
    }

    @Test fun aTranscriptThatFinishesDuringAFullSyncGoesWithItAndThePushDoesNotWait() = runBlocking {
        start()
        radio.device.files["REC_0002.wav"] = recording(20_000, seed = 2)
        val downloading = CountDownLatch(1)
        val release = CountDownLatch(1)
        radio.device.beforeGets = { name ->
            if (name == "REC_0002.wav") {
                downloading.countDown()
                release.await(HOOK_MS, TimeUnit.MILLISECONDS)
            }
        }
        val full = scope.async { sync.sync() }
        assertTrue(downloading.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        val checked = checks.get()
        transcriptReady()
        // The push finds the recorder busy: it waits for the end of the sync, with no failure and no backoff.
        withTimeout(TIMEOUT_MS) { while (checks.get() == checked) delay(10) }
        delay(SETTLE_MS / 3)
        release.countDown()
        val outcome = full.await() as SyncOutcome.Done
        assertEquals(1, outcome.downloaded)
        assertEquals(1, outcome.uploaded)
        withTimeout(TIMEOUT_MS) { while (checks.get() < checked + 2) delay(10) }
        delay(SETTLE_MS)
        assertTrue(uploaded())
        assertTrue(waits.isEmpty())
    }

    @Test fun aNoteThatLosesItsTranscriptDoesNotStopTheAttemptsForTheOthers() = runBlocking {
        start(files = 2)
        radio.reachable = false
        library.ready["note-1"] = TRANSCRIPT
        library.ready["note-2"] = TRANSCRIPT
        transcribed.value = setOf("note-1", "note-2")
        withTimeout(TIMEOUT_MS) { while (waits.size < 1) delay(10) }
        // note-2 loses its transcript while the push waits for the recorder: the attempts for note-1 go on.
        library.ready.remove("note-2")
        transcribed.value = setOf("note-1")
        delay(SETTLE_MS)
        radio.reachable = true
        proceed.send(Unit)
        withTimeout(TIMEOUT_MS) { while (!uploaded()) delay(10) }
        assertFalse(uploaded("REC_0002.wav"))
        assertEquals(1, waits.size)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 300L

        /** Shorter than the reply timeout of [TEST_TIMEOUTS]: a hook that blocks the fake recorder longer fails the link. */
        const val HOOK_MS = 1_500L
        val TRANSCRIPT = RecorderTranscript("v1", CwxTranscript("T", 1_000, emptyList(), emptyList()))
    }
}
