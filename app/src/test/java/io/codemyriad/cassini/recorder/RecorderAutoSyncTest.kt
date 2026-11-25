package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.FakeRecorder
import io.codemyriad.cassini.core.recorder.XrecAuth
import io.codemyriad.cassini.testing.NoUncaughtCoroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private fun start(): RecorderSync {
        val (sync, store) = recorderSync(folder.root, radio, library)
        val runner = RecorderRunner(scope, sync) { services.add(Unit) }
        RecorderAutoSync(scope, store.settings, runner) { delay ->
            waits += delay
            proceed.receive()
        }.start()
        runBlocking {
            store.bound(key)
            store.update { it.copy(autoSync = true) }
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

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
