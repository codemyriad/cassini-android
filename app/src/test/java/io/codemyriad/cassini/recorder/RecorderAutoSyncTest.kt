package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.CwxTranscript
import io.codemyriad.cassini.core.recorder.FakeRecorder
import io.codemyriad.cassini.core.recorder.XrecAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

/** "Auto sync": quiet growing waits while the recorder does not answer, and an attempt for a new transcript. */
@RunWith(RobolectricTestRunner::class)
class RecorderAutoSyncTest {
    @get:Rule val folder = TemporaryFolder()

    private val key = ByteArray(XrecAuth.KEY_BYTES) { 3 }
    private val radio = FakeRadio(FakeRecorder(key = key.copyOf()))
    private val library = FakeLibrary()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val waits: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val proceed = Channel<Unit>(Channel.UNLIMITED)
    private val services = Collections.synchronizedList(mutableListOf<Unit>())

    @After fun tearDown() {
        scope.cancel()
        radio.device.close()
    }

    private fun start(transcribed: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())): RecorderSync {
        val (sync, store) = recorderSync(folder.root, radio, library)
        val runner = RecorderRunner(scope, sync) { services.add(Unit) }
        RecorderAutoSync(scope, store.settings, runner, transcribed) { delay ->
            waits += delay
            proceed.receive()
        }.start()
        runBlocking {
            store.bound(key)
            store.update { it.copy(autoSync = true) }
        }
        return sync
    }

    @Test fun failedAttemptsWaitLongerAndShowNoServiceUntilTheLinkIsUp() = runBlocking {
        radio.reachable = false
        start()
        withTimeout(TIMEOUT_MS) { while (waits.size < 1) delay(10) }
        proceed.send(Unit)
        withTimeout(TIMEOUT_MS) { while (waits.size < 2) delay(10) }
        assertEquals(listOf(AutoSyncBackoff.delayMs(1), AutoSyncBackoff.delayMs(2)), waits.toList())
        assertTrue(services.isEmpty())

        // The recorder is in Bluetooth phone mode now: the attempt connects, starts the service, and the wait resets.
        radio.reachable = true
        proceed.send(Unit)
        withTimeout(TIMEOUT_MS) { while (waits.size < 3) delay(10) }
        assertEquals(AutoSyncBackoff.INTERVAL_MS, waits[2])
        assertEquals(1, services.size)
    }

    @Test fun aNewTranscriptOfAnImportedRecordingStartsAnAttempt() = runBlocking {
        radio.device.files["REC_0001.wav"] = recording(2_000)
        val transcribed = MutableStateFlow<Set<String>>(emptySet())
        start(transcribed)
        // The first attempt imports the recording; the loop then waits.
        withTimeout(TIMEOUT_MS) { while (waits.isEmpty()) delay(10) }
        assertEquals(listOf("note-1"), library.transcribed)
        val opens = radio.opens

        library.ready["note-1"] = RecorderTranscript("v1", CwxTranscript("T", 1_000, emptyList(), emptyList()))
        transcribed.value = setOf("note-1")
        withTimeout(TIMEOUT_MS) { while (!radio.device.files.containsKey("REC_0001.cwx")) delay(10) }
        assertEquals(opens + 1, radio.opens)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
