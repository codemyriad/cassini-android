package io.codemyriad.cassini.background

import android.os.Build
import android.util.Log
import io.codemyriad.cassini.background.Background.graph
import io.codemyriad.cassini.background.Background.waitFor
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.core.library.PauseReason
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.data.checkpoint.CheckpointStatus
import io.codemyriad.cassini.data.db.LibraryRow
import io.codemyriad.cassini.data.settings.QueueHold
import io.codemyriad.cassini.engine.job.JobPhase
import io.codemyriad.cassini.processing.ProcessingService
import io.codemyriad.cassini.processing.ProcessingState
import io.codemyriad.cassini.test.NeedsModels
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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

        awaitProcessing("The WAV note runs first") { it.running?.noteId == wav }
        assertEquals(JobState.QUEUED, Background.row(mp3)?.jobState)
        awaitProcessing("The service is in the foreground") { it.serviceForeground }
        val service = Background.processingService()
        Log.i(TAG, "ProcessingService in dumpsys activity services:\n$service")
        assertTrue("ProcessingService is in the foreground:\n$service", service.contains("isForeground=true"))
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // FOREGROUND_SERVICE_TYPE_DATA_SYNC is 1. The dump of API 29 shows no type: ask Android in the process.
            assertEquals("The service type is dataSync", DATA_SYNC, ProcessingService.currentType())
        }

        val document = awaitRow(wav, "The WAV note is transcribed") {
            it.jobState == null && it.audioKind == AudioKind.DOCUMENT
        }
        val seconds = (System.currentTimeMillis() - started) / 1_000
        Log.i(TAG, "WAV note sealed in $seconds s: ${document.wordCount} words")
        val checked = graph.awaitCatalogue().documents.read(wav)
        assertEquals("readChecked of the sealed document", DocumentState.OK.code, checked?.state)
        assertEquals(DocumentState.OK, document.documentState)
        assertTrue("The document has words", (document.wordCount ?: 0) > 0)

        awaitProcessing("The MP3 note starts next") { it.running?.noteId == mp3 }
        processing.stopAll()
        awaitRow(mp3, "Stop all pauses the MP3 note") {
            it.jobState == JobState.PAUSED && it.pauseReason == PauseReason.USER
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

        val paused = awaitRow(long, "Stop pauses the long note") { it.jobState == JobState.PAUSED }
        assertEquals(PauseReason.USER, paused.pauseReason)
        assertTrue("The paused note keeps its progress", (paused.doneMs ?: 0) >= saved.doneMs)
        awaitProcessing("The next note starts") { it.running?.noteId == short }
        assertTrue(
            "The long note keeps its checkpoint",
            graph.awaitCatalogue().checkpoints.status(long) is CheckpointStatus.Saved,
        )
        assertNull("Stop holds nothing", processing.state.value.hold)
    }

    private suspend fun awaitProcessing(message: String, matches: (ProcessingState) -> Boolean) {
        val state = graph.processing.state
        withTimeoutOrNull(Background.WAIT_MS) { state.first(matches) }
            ?: throw AssertionError("$message. Current state: ${state.value}")
    }

    private suspend fun awaitRow(noteId: String, message: String, matches: (LibraryRow) -> Boolean): LibraryRow =
        withTimeoutOrNull(Background.WAIT_MS) {
            graph.awaitCatalogue().notes.library()
                .mapNotNull { rows -> rows.firstOrNull { it.id == noteId } }
                .first(matches)
        } ?: throw AssertionError("$message. Current row: ${Background.row(noteId)}")

    private companion object {
        const val TAG = "ProcessingServiceTest"
        const val DATA_SYNC = 0x1
        const val MEDIA_PROCESSING = 0x2000
        const val TYPE_WAIT_MS = 30_000L
    }
}
