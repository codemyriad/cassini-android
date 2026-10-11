package org.cassini.android.background

import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import org.cassini.android.TestHooks
import org.cassini.android.background.Background.graph
import org.cassini.android.background.Background.waitFor
import org.cassini.android.processing.ProcessingJob
import org.cassini.android.processing.ProcessingJobFactory
import org.cassini.android.processing.ProcessingService
import org.cassini.core.library.JobKind
import org.cassini.core.library.PauseReason
import org.cassini.engine.job.JobEvent
import org.cassini.engine.job.JobOutcome
import org.cassini.engine.job.JobPhase
import org.cassini.test.CiSmoke
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The type of `ProcessingService` during inference, as Android reports it (docs/architecture.md section 5.7):
 * mediaProcessing (0x2000) on API 35+, dataSync (0x1) on API 29-34. No models: a scripted job takes the place of the
 * transcription and only says that inference has started. A Pixel 8 (API 37) ran the inference as dataSync, because
 * `ServiceCompat.startForeground` turned 0x2000 into 0 and Android refused it; the CI emulator of API 35 runs this
 * test, so such a change is seen there.
 */
@CiSmoke
class ProcessingServiceTypeTest {
    @Before
    fun setUp() = runBlocking {
        Background.grant()
        graph.ready.await()
        Background.reset()
    }

    @After
    fun tearDown() = runBlocking {
        try {
            Background.reset()
        } finally {
            TestHooks.reset()
        }
    }

    @Test
    fun inferenceRunsWithTheServiceTypeOfTheApiLevel() = runBlocking {
        val job = ScriptedJob()
        TestHooks.jobFactory = ProcessingJobFactory { noteId -> job.also { it.noteId = noteId } }
        val note = Background.import(Background.assetCopy("fleurs-it-short.wav"))
        val processing = graph.processing
        // Queued as Transcribe does it, but without the model consent, which would stay for the later tests of the
        // run; the drain itself does not ask for it.
        graph.awaitCatalogue().jobs.enqueue(note, JobKind.TRANSCRIBE)
        ContextCompat.startForegroundService(Background.context, ProcessingService.drainIntent(Background.context))
        waitFor("The scripted job runs") { job.started.takeIf { it.isCompleted } }

        job.send(JobEvent.Started(15_000, restored = null, speakers = false, prints = false, lowMemory = false))
        waitFor("Inference runs in the foreground service") {
            processing.state.value.takeIf { it.serviceForeground && it.running?.phase is JobPhase.Transcribing }
        }
        val expected = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> DATA_SYNC
            else -> null
        }
        val wanted = expected?.let { "type 0x${Integer.toHexString(it)}" } ?: "no type check below API 29"
        // The type that Android has for the service (getForegroundServiceType), and the foreground flag of dumpsys.
        val services = waitFor("ProcessingService runs in the foreground ($wanted)", TYPE_WAIT_MS) {
            Background.processingService().takeIf { service ->
                service.contains("isForeground=true") && ProcessingService.currentType() == expected
            }
        }
        Log.i(TAG, "API ${Build.VERSION.SDK_INT}, expected type $expected:\n$services")
        if (expected != null && Background.showsType(services)) {
            // More evidence from API 30: the type in dumpsys is the same.
            assertTrue("dumpsys shows $wanted:\n$services", Background.hasType(services, expected))
        }
        job.finish(JobOutcome.Paused(PauseReason.USER))
        processing.awaitIdle()
    }

    /** A job that does nothing until the test sends its events; a stop pauses it at once. */
    private class ScriptedJob : ProcessingJob {
        override var noteId: String = ""
        private val channel = Channel<JobEvent>(Channel.UNLIMITED)
        private val result = CompletableDeferred<JobOutcome>()
        val started = CompletableDeferred<Unit>()

        override val events: ReceiveChannel<JobEvent> get() = channel

        fun send(event: JobEvent) {
            channel.trySend(event)
        }

        fun finish(outcome: JobOutcome) {
            result.complete(outcome)
        }

        override fun stop(reason: PauseReason) {
            channel.trySend(JobEvent.Stopping)
            result.complete(JobOutcome.Paused(reason))
        }

        override suspend fun run(): JobOutcome = try {
            started.complete(Unit)
            result.await().also { channel.trySend(JobEvent.Ended(it)) }
        } finally {
            channel.close()
        }
    }

    private companion object {
        const val TAG = "ProcessingServiceTypeTest"
        const val DATA_SYNC = 0x1
        const val MEDIA_PROCESSING = 0x2000
        const val TYPE_WAIT_MS = 30_000L
    }
}
