package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.CwxTranscript
import io.codemyriad.cassini.core.recorder.FakeRecorder
import io.codemyriad.cassini.core.recorder.XrecAuth
import io.codemyriad.cassini.data.recorder.RecorderStore
import io.codemyriad.cassini.testing.NoUncaughtCoroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    @After fun tearDown() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        radio.device.close()
    }

    /**
     * A bound recorder with REC_0001.wav imported as note-1 (by a sync now), "Auto sync" off, then the push, once its
     * first check found nothing to send. With [sentBefore], note-1 has its transcript on the recorder already.
     */
    private fun start(sentBefore: Boolean = false) {
        val (sync, store) = recorderSync(folder.root, radio, library)
        this.store = store
        radio.device.files["REC_0001.wav"] = recording(2_000)
        runBlocking {
            store.bound(key)
            assertTrue(sync.sync() is SyncOutcome.Done)
            if (sentBefore) {
                library.ready["note-1"] = TRANSCRIPT
                assertEquals(1, (sync.sync() as SyncOutcome.Done).uploaded)
            }
            assertFalse(store.read().autoSync)
        }
        val runner = RecorderRunner(scope, sync) { true }
        val pending: suspend () -> Set<String> = { sync.pendingUploads().also { checks.incrementAndGet() } }
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

    private suspend fun uploaded(): Boolean =
        store.read().current?.recordings.orEmpty().single().recording.transcriptUploadedVersion != null

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

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 300L
        val TRANSCRIPT = RecorderTranscript("v1", CwxTranscript("T", 1_000, emptyList(), emptyList()))
    }
}
