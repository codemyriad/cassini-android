package org.cassini.android.background

import android.os.Build
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.cassini.android.background.Background.graph
import org.cassini.android.background.Background.waitFor
import org.cassini.android.processing.ProcessingService
import org.cassini.core.format.DocumentState
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobState
import org.cassini.core.library.PauseReason
import org.cassini.core.models.ModelPart
import org.cassini.data.checkpoint.CheckpointStatus
import org.cassini.data.settings.QueueHold
import org.cassini.engine.job.JobPhase
import org.cassini.test.NeedsModels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The transcription queue with the real pipeline and `ProcessingService` (docs/architecture.md sections 5.2 and
 * 5.7), on the models installed in `filesDir`. The notes live in the runner's scratch root.
 */
@NeedsModels
class ProcessingServiceTest {
    @Before
    fun setUp() = runBlocking {
        Background.grant()
        graph.ready.await()
        // Every part: a missing one would be downloaded, which this test must never do.
        for (part in ModelPart.entries) {
            assertTrue("The $part models must be installed in filesDir", graph.models.ready(part))
        }
        Background.reset()
    }

    @After
    fun tearDown() = runBlocking { Background.reset() }

    @Test
    fun queuedImportsRunInOrderTheFirstIsSealedAndStopAllHoldsTheQueue() = runBlocking {
        val wav = Background.import(Background.assetCopy("fleurs-it-short.wav"))
        val mp3 = Background.import(Background.assetCopy("fleurs-it-short.mp3"))
        val processing = graph.processing
        val started = System.currentTimeMillis()
        processing.transcribe(wav)
        processing.transcribe(mp3)

        waitFor("The WAV note runs first") { processing.state.value.running?.takeIf { it.noteId == wav } }
        assertEquals(JobState.QUEUED, Background.row(mp3)?.jobState)
        waitFor("The service is in the foreground") { processing.state.value.serviceForeground.takeIf { it } }
        val service = Background.processingService()
        Log.i(TAG, "ProcessingService in dumpsys activity services:\n$service")
        assertTrue("ProcessingService is in the foreground:\n$service", service.contains("isForeground=true"))
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // FOREGROUND_SERVICE_TYPE_DATA_SYNC is 1. The dump of API 29 shows no type: ask Android in the process.
            assertEquals("The service type is dataSync", DATA_SYNC, ProcessingService.currentType())
        }

        val document = waitFor("The WAV note is transcribed") {
            Background.row(wav)?.takeIf { it.jobState == null && it.audioKind == AudioKind.DOCUMENT }
        }
        val seconds = (System.currentTimeMillis() - started) / 1_000
        Log.i(TAG, "WAV note sealed in $seconds s: ${document.wordCount} words")
        val checked = graph.awaitCatalogue().documents.read(wav)
        assertEquals("readChecked of the sealed document", DocumentState.OK.code, checked?.state)
        assertEquals(DocumentState.OK, document.documentState)
        assertTrue("The document has words", (document.wordCount ?: 0) > 0)

        waitFor("The MP3 note starts next") { processing.state.value.running?.takeIf { it.noteId == mp3 } }
        processing.stopAll()
        waitFor("Stop all pauses the MP3 note") {
            Background.row(mp3)?.takeIf { it.jobState == JobState.PAUSED && it.pauseReason == PauseReason.USER }
        }
        processing.awaitIdle()
        val held = processing.state.value
        assertEquals(QueueHold.USER, held.hold)
        assertNull("Nothing runs while the queue is held", held.running)
        // A held queue starts nothing, also when the drain is asked for again.
        processing.drain().join()
        assertNull(processing.state.value.running)
        assertEquals(JobState.PAUSED, Background.row(mp3)?.jobState)
    }

    @Test
    fun stopOnALongJobPausesItWithItsCheckpointAndTheNextNoteStarts() = runBlocking {
        val long = Background.import(Background.assetCopy("generated/fleurs-it-x20.wav"))
        val short = Background.import(Background.assetCopy("fleurs-it-short.m4a"))
        val processing = graph.processing
        processing.transcribe(long)
        processing.transcribe(short)

        val saved = waitFor("The long note saves a checkpoint") {
            val running = processing.state.value.running
                ?.takeIf { it.noteId == long && it.phase is JobPhase.Transcribing }
            running?.let { graph.awaitCatalogue().checkpoints.status(long) as? CheckpointStatus.Saved }
        }
        Log.i(TAG, "Long note checkpoint at ${saved.doneMs} of ${saved.durationMs} ms")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Inference runs as mediaProcessing (0x2000), as Android reports it; dataSync would time out after 6 h.
            val service = waitFor("ProcessingService runs as mediaProcessing during inference", TYPE_WAIT_MS) {
                Background.processingService().takeIf { ProcessingService.currentType() == MEDIA_PROCESSING }
            }
            Log.i(TAG, "ProcessingService during inference:\n$service")
        }
        assertTrue("Stop is accepted", processing.stop(long))

        val paused = waitFor("Stop pauses the long note") {
            Background.row(long)?.takeIf { it.jobState == JobState.PAUSED }
        }
        assertEquals(PauseReason.USER, paused.pauseReason)
        assertTrue("The paused note keeps its progress", (paused.doneMs ?: 0) >= saved.doneMs)
        waitFor("The next note starts") { processing.state.value.running?.takeIf { it.noteId == short } }
        assertTrue(
            "The long note keeps its checkpoint",
            graph.awaitCatalogue().checkpoints.status(long) is CheckpointStatus.Saved,
        )
        assertNull("Stop holds nothing", processing.state.value.hold)
    }

    private companion object {
        const val TAG = "ProcessingServiceTest"
        const val DATA_SYNC = 0x1
        const val MEDIA_PROCESSING = 0x2000
        const val TYPE_WAIT_MS = 30_000L
    }
}
