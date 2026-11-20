package io.codemyriad.cassini

import android.content.Context
import android.os.Bundle
import io.codemyriad.cassini.adapters.NetworkCheck
import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.UserFacingException
import io.codemyriad.cassini.core.models.ModelDescriptor
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.data.models.ModelFetcher
import io.codemyriad.cassini.engine.capture.CaptureFactory
import io.codemyriad.cassini.engine.capture.MicrophoneCapture
import io.codemyriad.cassini.engine.job.JobEngines
import io.codemyriad.cassini.engine.job.JobEnvironment
import io.codemyriad.cassini.engine.job.PrecomputeStopReason
import io.codemyriad.cassini.processing.AndroidThermalMonitor
import io.codemyriad.cassini.processing.ProcessingJobFactory
import io.codemyriad.cassini.processing.ThermalMonitor
import io.codemyriad.cassini.processing.TranscriptionJobFactory
import io.codemyriad.cassini.recording.AndroidRecordingPlatform
import io.codemyriad.cassini.recording.CaptureFormat
import io.codemyriad.cassini.recording.CaptureSource
import io.codemyriad.cassini.recording.FactoryCaptureSource
import io.codemyriad.cassini.recording.RecordingPlatform
import io.codemyriad.cassini.recording.SilenceWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    @Volatile var captureSource: CaptureSource? = null

    @Volatile var opusReadHook: CaptureFactory.OpusReadHook? = null

    @Volatile var lastStartedCaptureClass: String? = null

    @Volatile var lowMemoryOverride: ((Long?) -> Boolean)? = null

    @Volatile var speakerPrefixOverride: (() -> String)? = null

    @Volatile var enginesOverride: ((SpeechModel, JobEngines, Boolean) -> JobEngines)? = null

    @Volatile var onWhistleFinish: ((Bundle) -> Unit)? = null

    @Volatile var onWhistleSegment: ((Bundle, Boolean) -> Unit)? = null

    @Volatile var onPrecomputeCheckpointed: ((String, Long) -> Unit)? = null

    @Volatile var onPrecomputeStopped: ((String, PrecomputeStopReason) -> Unit)? = null

    val injectedThermalThrottled = MutableStateFlow(false)

    private val ticks = MutableStateFlow(0L)
    val policyTicks: StateFlow<Long> = ticks.asStateFlow()

    @Volatile private var activeSilenceListener: ((Boolean) -> Unit)? = null

    fun emitSilence(silenced: Boolean) {
        activeSilenceListener?.invoke(silenced)
    }

    fun network(context: Context): NetworkCheck {
        val real = NetworkCheck.of(context)
        return NetworkCheck { validatedNetwork ?: real.validated() }
    }

    fun capture(context: Context): CaptureSource {
        val app = context.applicationContext
        val real = FactoryCaptureSource(app)
        return object : CaptureSource {
            override suspend fun format(): CaptureFormat = (captureSource ?: real).format()
            override fun start(file: File, onError: () -> Unit): MicrophoneCapture {
                val custom = captureSource
                val started = if (custom != null) {
                    custom.start(file, onError)
                } else {
                    CaptureFactory.create(app, file, onError, opusReadHook)
                }
                lastStartedCaptureClass = started.javaClass.simpleName
                return started
            }
        }
    }

    fun recordingPlatform(context: Context): RecordingPlatform {
        val real = AndroidRecordingPlatform(context)
        return object : RecordingPlatform by real {
            override fun usableBytes(directory: File): Long {
                ticks.update { it + 1L }
                return recordingFreeBytes?.invoke(directory) ?: real.usableBytes(directory)
            }

            override fun watchSilence(capture: MicrophoneCapture, onSilenced: (Boolean) -> Unit): SilenceWatch {
                activeSilenceListener = onSilenced
                val delegate = real.watchSilence(capture, onSilenced)
                return object : SilenceWatch {
                    override fun resumed() = delegate.resumed()
                    override fun close() {
                        if (activeSilenceListener === onSilenced) activeSilenceListener = null
                        delegate.close()
                    }
                }
            }
        }
    }

    fun thermal(context: Context): ThermalMonitor {
        val real = AndroidThermalMonitor(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val combined = combine(real.throttled, injectedThermalThrottled) { hw, injected -> hw || injected }
            .stateIn(scope, SharingStarted.Eagerly, real.throttled.value || injectedThermalThrottled.value)
        return object : ThermalMonitor {
            override val throttled: StateFlow<Boolean> = combined
            override fun close() {
                real.close()
                scope.cancel()
            }
        }
    }

    fun environment(base: JobEnvironment): JobEnvironment = JobEnvironment(
        placeholderLabel = base.placeholderLabel,
        inference = base.inference,
        io = base.io,
        nanoTime = base.nanoTime,
        speakerPrefix = { speakerPrefixOverride?.invoke() ?: base.speakerPrefix() },
        lowMemory = { durationMs -> lowMemoryOverride?.invoke(durationMs) ?: base.lowMemory(durationMs) },
        warn = base.warn,
        syncFile = base.syncFile,
        maxDurationMs = base.maxDurationMs,
        maxBodyBytes = base.maxBodyBytes,
        runAtBackgroundPriority = base.runAtBackgroundPriority,
    )

    fun engines(model: SpeechModel, raw: JobEngines, precompute: Boolean): JobEngines =
        enginesOverride?.invoke(model, raw, precompute) ?: raw

    /** The app's [TranscriptionJobFactory], or [jobFactory] while a test sets one. */
    fun jobs(graph: AppGraph): ProcessingJobFactory {
        val real = TranscriptionJobFactory(graph)
        return ProcessingJobFactory { noteId -> (jobFactory ?: real).create(noteId) }
    }

    fun reset() {
        validatedNetwork = null
        recordingFreeBytes = null
        jobFactory = null
        captureSource = null
        opusReadHook = null
        lastStartedCaptureClass = null
        lowMemoryOverride = null
        speakerPrefixOverride = null
        enginesOverride = null
        onWhistleFinish = null
        onWhistleSegment = null
        onPrecomputeCheckpointed = null
        onPrecomputeStopped = null
        injectedThermalThrottled.value = false
        activeSilenceListener = null
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
                    // The model's directory is <modelRoot>/models/<id>/<source sha256>.
                    val modelRoot = File(directory.path.removeSuffix(File.separator + model.directory))
                    progress(bytesFor(model, modelRoot, step.percent), step.verifying)
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
