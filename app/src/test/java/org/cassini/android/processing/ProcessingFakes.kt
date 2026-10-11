package org.cassini.android.processing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.cassini.android.AppGraph
import org.cassini.core.library.PauseReason
import org.cassini.engine.job.JobEvent
import org.cassini.engine.job.JobOutcome
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// The fakes of the coordinator and service tests.

/**
 * A job the test ends with [finish]; [stop] pauses it at once unless [pauseOnStop] is false. With [holdEvents] its
 * events stay open after run() until [closeEvents], so the coordinator waits for the tracker meanwhile.
 */
internal class FakeJob(override val noteId: String, private val graph: AppGraph) : ProcessingJob {
    private val channel = Channel<JobEvent>(Channel.UNLIMITED)
    private val result = CompletableDeferred<JobOutcome>()
    val started = CompletableDeferred<Unit>()
    val stopReason = AtomicReference<PauseReason?>(null)
    val cancelled = AtomicBoolean(false)

    /** Completes when run() returned. */
    val ended = CompletableDeferred<Unit>()

    @Volatile var pauseOnStop = true

    @Volatile var holdEvents = false

    override val events: ReceiveChannel<JobEvent> get() = channel

    fun finish(outcome: JobOutcome) {
        result.complete(outcome)
    }

    /** Sends [event] to the job's tracker. */
    fun send(event: JobEvent) {
        channel.trySend(event)
    }

    fun closeEvents() {
        channel.close()
    }

    override fun stop(reason: PauseReason) {
        if (!stopReason.compareAndSet(null, reason)) return
        channel.trySend(JobEvent.Stopping)
        if (pauseOnStop) result.complete(JobOutcome.Paused(reason))
    }

    override suspend fun run(): JobOutcome {
        try {
            started.complete(Unit)
            val outcome = try {
                result.await()
            } catch (error: CancellationException) {
                cancelled.set(true)
                throw error
            }
            // As adopt does: the document is in place and the row is gone.
            if (outcome == JobOutcome.Done) graph.awaitCatalogue().jobs.remove(noteId)
            channel.trySend(JobEvent.Ended(outcome))
            return outcome
        } finally {
            if (!holdEvents) channel.close()
            ended.complete(Unit)
        }
    }
}

internal class FakeVisibility : AppVisibility {
    val state = MutableStateFlow(false)
    override val visible: StateFlow<Boolean> get() = state
}

internal class RecordingNotices : ProcessingNotices {
    val transcribed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val failed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val ready: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val paused: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The (note, doneMs) of each [pausedByAndroidSaved]. */
    val pausedSaved: MutableList<Pair<String, Long>> = Collections.synchronizedList(mutableListOf())
    val interrupted: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun transcribed(noteId: String, title: String, speakers: Int, durationMs: Long) {
        transcribed += noteId
    }

    override fun failed(noteId: String, title: String, failure: String) {
        failed += noteId
    }

    override fun readyToTranscribe(noteId: String, title: String) {
        ready += noteId
    }

    override fun pausedByAndroid(noteId: String, title: String, doneMs: Long, totalMs: Long) {
        paused += noteId
    }

    override fun pausedByAndroidSaved(noteId: String, title: String, doneMs: Long, totalMs: Long) {
        pausedSaved += noteId to doneMs
    }

    override fun interrupted(noteId: String, title: String) {
        interrupted += noteId
    }
}
