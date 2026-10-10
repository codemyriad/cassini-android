package org.cassini.android.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cassini.android.AppGraph
import org.cassini.android.AppRoots
import org.cassini.android.CassiniApplication
import org.cassini.android.processing.RunningJob
import org.cassini.data.checkpoint.CheckpointStatus
import org.cassini.engine.job.JobPhase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The status file of a debug-root run (docs/architecture.md section 9): once a second, `<root>/debug-status.json`
 * gets the queue state of this process and every note with its job and checkpoint. `scripts/device-kill-resume.sh`
 * reads it with `run-as cat` to see when a job has a checkpoint and when its document is sealed. Debug builds only;
 * it runs only while `files/debug-root.txt` names a scratch root, and never under instrumentation.
 */
object DebugStatus {
    const val STATUS_FILE = "debug-status.json"
    private const val TAG = "DebugStatus"
    private const val INTERVAL_MS = 1_000L

    private val started = AtomicBoolean(false)

    /** Starts the writer when this process runs on a debug root. Reads `debug-root.txt`, so call it off main. */
    fun startIfDebugRoot(app: CassiniApplication) {
        if (CassiniApplication.graphFactory != null || AppRoots.debugRoot(app.filesDir) == null) return
        start(app.graph)
    }

    /**
     * Starts the writer once per process. It also creates the coordinator, so the queue starts at ON_START or on a
     * redelivered service intent as in the app.
     */
    fun start(graph: AppGraph) {
        if (!started.compareAndSet(false, true)) return
        graph.processing
        graph.appScope.launch(graph.dispatchers.io) {
            while (isActive) {
                write(graph)
                delay(INTERVAL_MS)
            }
        }
    }

    /** Writes [json] to `<root>/[name]` through a temporary file and a rename. */
    fun writeJson(graph: AppGraph, name: String, json: JSONObject) {
        val target = File(graph.paths.root, name)
        val temporary = File(graph.paths.root, "$name.tmp")
        try {
            graph.paths.root.mkdirs()
            temporary.writeText(json.toString(2))
            if (!temporary.renameTo(target)) Log.w(TAG, "Cannot rename $temporary")
        } catch (error: IOException) {
            Log.w(TAG, "Cannot write $target", error)
        }
    }

    @Suppress("TooGenericExceptionCaught") // a diagnostic: any failure is logged, and the next second tries again
    private suspend fun write(graph: AppGraph) {
        try {
            writeJson(graph, STATUS_FILE, snapshot(graph))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Cannot build the status", error)
        }
    }

    private suspend fun snapshot(graph: AppGraph): JSONObject {
        val state = graph.processing.state.value
        val json = JSONObject()
            .put("pid", Process.myPid())
            .put("root", graph.paths.root.absolutePath)
            .put("updatedAt", System.currentTimeMillis())
            .put("ready", graph.ready.isCompleted)
            .put("loading", state.loading)
            .put("serviceForeground", state.serviceForeground)
            .put("draining", state.draining)
            .put("hold", state.hold?.name ?: JSONObject.NULL)
            .put("running", state.running?.let(::running) ?: JSONObject.NULL)
        if (graph.catalogue.isCompleted && !graph.catalogue.isCancelled) json.put("notes", notes(graph))
        return json
    }

    private fun running(job: RunningJob): JSONObject {
        val json = JSONObject().put("noteId", job.noteId).put("phase", job.phase::class.simpleName)
        val phase = job.phase
        if (phase is JobPhase.Transcribing) {
            json.put("doneMs", phase.progress.doneMs)
                .put("totalMs", phase.progress.totalMs)
                .put("percent", phase.progress.percent ?: JSONObject.NULL)
                .put("step", phase.progress.step.name)
        }
        return json
    }

    private suspend fun notes(graph: AppGraph): JSONArray {
        val catalogue = graph.awaitCatalogue()
        val notes = JSONArray()
        for (row in catalogue.notes.library().first()) {
            val note = JSONObject()
                .put("id", row.id)
                .put("title", row.title)
                .put("durationMs", row.durationMs)
                .put("audioKind", row.audioKind.name)
                .put("documentState", row.documentState?.name ?: JSONObject.NULL)
                .put("jobState", row.jobState?.name ?: JSONObject.NULL)
                .put("pauseReason", row.pauseReason?.name ?: JSONObject.NULL)
                .put("failure", row.failure ?: JSONObject.NULL)
                .put("wordCount", row.wordCount ?: JSONObject.NULL)
            catalogue.jobs.job(row.id)?.let { job -> note.put("attempts", job.attempts) }
            if (row.jobState != null) {
                val checkpoint = catalogue.checkpoints.status(row.id)
                note.put("checkpoint", checkpoint::class.simpleName)
                if (checkpoint is CheckpointStatus.Saved) note.put("checkpointMs", checkpoint.doneMs)
            }
            notes.put(note)
        }
        return notes
    }
}

/**
 * Starts [DebugStatus] in each new process: a provider is created before `Application.onCreate`, also when Android
 * starts the process only for a service. The start is posted, so it runs after `onCreate` resolved the paths, and
 * it reads `debug-root.txt` on a thread of its own.
 */
class DebugStatusProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? CassiniApplication ?: return true
        Handler(Looper.getMainLooper()).post {
            Thread({ DebugStatus.startIfDebugRoot(app) }, "cassini-debug-status").start()
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
        sort: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
}
