package org.cassini.android.processing

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cassini.android.AppGraph
import org.cassini.core.library.PauseReason
import org.cassini.engine.job.JobEvent
import org.cassini.engine.job.JobOutcome
import org.cassini.engine.job.TranscriptionJob

// The seams of ProcessingCoordinator: the job it runs, how it starts the service, the notifications it posts and
// whether the app is visible. The app uses the implementations below; JVM tests pass fakes.

/** One run of the pipeline for one note: [TranscriptionJob] in the app. */
interface ProcessingJob {
    val noteId: String

    /** The job's events for its `ProgressTracker`; closed when [run] ends, on every path. */
    val events: ReceiveChannel<JobEvent>

    /** "Stop and keep progress" (architecture 5.2 rule 5). Any thread; only the first call counts. */
    fun stop(reason: PauseReason)

    /** Runs the job to its end. Cancelling the caller is the internal Cancel of rule 8. */
    suspend fun run(): JobOutcome
}

/** Makes the job of a claimed note. */
fun interface ProcessingJobFactory {
    suspend fun create(noteId: String): ProcessingJob
}

/** [TranscriptionJob] over the graph's ports and seams (architecture 5.4). */
class TranscriptionJobFactory(private val graph: AppGraph) : ProcessingJobFactory {
    override suspend fun create(noteId: String): ProcessingJob {
        val catalogue = graph.awaitCatalogue()
        val job = TranscriptionJob(
            noteId,
            catalogue.jobStorage,
            catalogue.jobVoices,
            graph.modelAccess,
            graph.engines,
            graph.jobEnvironment,
        )
        return object : ProcessingJob {
            override val noteId: String = noteId
            override val events: ReceiveChannel<JobEvent> get() = job.events

            override fun stop(reason: PauseReason) = job.stop(reason)

            override suspend fun run(): JobOutcome = job.run()
        }
    }
}

/**
 * Starts `ProcessingService` from a start path Android allows (architecture 5.7). Returns false when Android refused
 * the start; the notes then stay QUEUED and the caller has posted "Ready to transcribe".
 */
fun interface ServiceStarter {
    fun start(): Boolean
}

/** `ContextCompat.startForegroundService` with [ProcessingService.ACTION_DRAIN], inside the catch of section 5.7. */
class ForegroundServiceStarter(context: Context, private val onRefused: () -> Unit) : ServiceStarter {
    private val app = context.applicationContext

    override fun start(): Boolean = try {
        ContextCompat.startForegroundService(app, ProcessingService.drainIntent(app))
        true
    } catch (error: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (API 31+) and the background start limit are IllegalStateException.
        Log.w(TAG, "Android refused to start the processing service", error)
        onRefused()
        false
    } catch (error: SecurityException) {
        Log.w(TAG, "Android refused to start the processing service", error)
        onRefused()
        false
    }

    private companion object {
        const val TAG = "ProcessingService"
    }
}

/**
 * The results notifications of features.md section 12. The coordinator calls them only while the app is not visible.
 * [durationMs] and the times are on the recording clock.
 */
interface ProcessingNotices {
    fun transcribed(noteId: String, title: String, speakers: Int, durationMs: Long)

    /** [failure] is the stored `jobs.failure` code. */
    fun failed(noteId: String, title: String, failure: String)

    fun readyToTranscribe(noteId: String, title: String)

    fun pausedByAndroid(noteId: String, title: String, doneMs: Long, totalMs: Long)

    /**
     * The job that `onTimeout` stopped has ended with its last checkpoint at [doneMs]: a shown [pausedByAndroid]
     * notification of [noteId] gets these numbers, the ones the Note screen shows. Nothing when it is not shown.
     */
    fun pausedByAndroidSaved(noteId: String, title: String, doneMs: Long, totalMs: Long)

    /** "Cassini closed while transcribing <title>": the job passed its automatic attempts (architecture 5.3). */
    fun interrupted(noteId: String, title: String)

    companion object {
        val NONE = object : ProcessingNotices {
            override fun transcribed(noteId: String, title: String, speakers: Int, durationMs: Long) = Unit

            override fun failed(noteId: String, title: String, failure: String) = Unit

            override fun readyToTranscribe(noteId: String, title: String) = Unit

            override fun pausedByAndroid(noteId: String, title: String, doneMs: Long, totalMs: Long) = Unit

            override fun pausedByAndroidSaved(noteId: String, title: String, doneMs: Long, totalMs: Long) = Unit

            override fun interrupted(noteId: String, title: String) = Unit
        }
    }
}

/** Whether a screen of the app is visible (ProcessLifecycleOwner STARTED). */
interface AppVisibility {
    val visible: StateFlow<Boolean>
}

/**
 * [AppVisibility] over `ProcessLifecycleOwner`. The observer is added on the main thread when [attach] is called; a
 * lifecycle that is STARTED already sends ON_START at once.
 */
class ProcessVisibility : AppVisibility, DefaultLifecycleObserver {
    private val state = MutableStateFlow(false)

    override val visible: StateFlow<Boolean> = state.asStateFlow()

    fun attach() {
        Handler(Looper.getMainLooper()).post { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
    }

    override fun onStart(owner: LifecycleOwner) {
        state.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        state.value = false
    }
}

/** Builds the intent that starts [ProcessingService]; kept here so tests and callers share it. */
internal fun processingIntent(context: Context, action: String): Intent =
    Intent(context, ProcessingService::class.java).setAction(action)
