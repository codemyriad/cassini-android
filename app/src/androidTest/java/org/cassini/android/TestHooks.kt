package org.cassini.android

import android.content.Context
import org.cassini.android.adapters.NetworkCheck
import org.cassini.android.processing.ProcessingJobFactory
import org.cassini.android.processing.TranscriptionJobFactory
import org.cassini.android.recording.AndroidRecordingPlatform
import org.cassini.android.recording.RecordingPlatform
import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.cassini.core.models.ModelDescriptor
import org.cassini.core.models.ModelPart
import org.cassini.data.models.ModelFetcher
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Test-only seams that `CassiniTestRunner` puts into the graph of every run (the acceptance walk, docs/development.md).
 * Each one does what the app does until a test sets it, and [reset] clears them all.
 *
 * - [validatedNetwork] replaces the answer of the validated-network check ("No connection" without a network).
 * - [recordingFreeBytes] replaces the free space the recording sees ("Storage almost full"); it gets the directory of
 *   the capture.
 * - [ScriptedModels] is the model fetcher of a run with an empty model root: it never downloads.
 * - [jobFactory] replaces the transcription job of the notes the queue claims (no models needed).
 */
object TestHooks {
    @Volatile var validatedNetwork: Boolean? = null

    @Volatile var jobFactory: ProcessingJobFactory? = null

    @Volatile var recordingFreeBytes: ((File) -> Long)? = null

    fun network(context: Context): NetworkCheck {
        val real = NetworkCheck.of(context)
        return NetworkCheck { validatedNetwork ?: real.validated() }
    }

    fun recordingPlatform(context: Context): RecordingPlatform {
        val real = AndroidRecordingPlatform(context)
        return object : RecordingPlatform by real {
            override fun usableBytes(directory: File): Long =
                recordingFreeBytes?.invoke(directory) ?: real.usableBytes(directory)
        }
    }

    /** The app's [TranscriptionJobFactory], or [jobFactory] while a test sets one. */
    fun jobs(graph: AppGraph): ProcessingJobFactory {
        val real = TranscriptionJobFactory(graph)
        return ProcessingJobFactory { noteId -> (jobFactory ?: real).create(noteId) }
    }

    fun reset() {
        validatedNetwork = null
        recordingFreeBytes = null
        jobFactory = null
        ScriptedModels.clear()
    }
}

/**
 * A model fetcher that follows a script and never opens a connection (the download screens of the acceptance walk).
 * Each fetch takes the next [Step]s in order: a [Step.Progress] reports a percent of the part and waits until the
 * test calls [release]; a [Step.Fail] fails the fetch, and sets the network answer only when the step gives one. With
 * no step left the fetch fails with DOWNLOAD and leaves the network check as it is, so a run with an empty model root
 * never installs a model.
 */
object ScriptedModels {
    sealed interface Step {
        /** Reports [percent] of the part's missing bytes (checked when [verifying]), then waits for [release]. */
        data class Progress(val percent: Int, val verifying: Boolean) : Step

        /** Fails the fetch with [failure]. A non-null [online] becomes the answer of the validated-network check. */
        data class Fail(val failure: Failure, val online: Boolean? = null) : Step
    }

    private val steps = LinkedBlockingQueue<Step>()
    private val gate = Semaphore(0)

    fun script(vararg next: Step) {
        steps.addAll(next)
    }

    /** Lets the fetch that waits after a [Step.Progress] go on to its next step. */
    fun release() = gate.release()

    fun clear() {
        steps.clear()
        gate.drainPermits()
    }

    val fetcher = ModelFetcher { model, directory, progress ->
        while (true) {
            when (val step = steps.poll() ?: throw UserFacingException(Failure.DOWNLOAD, "No scripted step")) {
                is Step.Progress -> {
                    progress(bytesFor(model, checkNotNull(directory.parentFile), step.percent), step.verifying)
                    // Interrupted when the job is cancelled: the installer reads that as a cancellation.
                    if (!gate.tryAcquire(WAIT_S, TimeUnit.SECONDS)) {
                        throw UserFacingException(Failure.DOWNLOAD, "The scripted fetch was not released")
                    }
                }
                is Step.Fail -> {
                    step.online?.let { TestHooks.validatedNetwork = it }
                    throw UserFacingException(step.failure, "Scripted ${step.failure}")
                }
            }
        }
    }

    /**
     * The bytes of [model] that make the installer show [percent] of its part: the part's missing models in order,
     * as `ModelInstaller` counts them, rounded up so that the whole percent is exactly [percent].
     */
    private fun bytesFor(model: ModelDescriptor, modelRoot: File, percent: Int): Long {
        val part = ModelPart.entries.first { model in it.models }
        val missing = part.models.filter { it == model || !it.ready(modelRoot) }
        val total = missing.sumOf { it.downloadBytes }
        val before = missing.takeWhile { it != model }.sumOf { it.downloadBytes }
        val target = (percent * total + PERCENT - 1) / PERCENT
        return (target - before).coerceIn(0, model.downloadBytes)
    }

    private const val WAIT_S = 300L
    private const val PERCENT = 100L
}
