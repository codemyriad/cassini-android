package org.cassini.android.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.cassini.android.AppGraph
import org.cassini.android.AppRoots
import org.cassini.android.CassiniApplication
import org.cassini.android.processing.StartOrigin
import org.cassini.data.notes.ImportOutcome
import org.json.JSONObject
import java.io.File

/**
 * The debug entry point of `scripts/device-kill-resume.sh` (docs/architecture.md section 9). Debug builds only, and
 * only while `files/debug-root.txt` names a scratch root: on the default root it does nothing, so it never touches
 * the user's notes.
 *
 * - `adb shell am start -n org.cassini.android/.debug.DebugCommandActivity --es cmd import --es file NAME` imports
 *   `<cache>/NAME` (the scratch root's cache directory) and enqueues it as Transcribe from a visible screen, which
 *   starts the processing service.
 * - `--es cmd open` (the default) only makes the app visible, so ON_START starts a waiting queue.
 *
 * The activity is translucent and stays until the command has run and the service is in the foreground (at most
 * [FOREGROUND_WAIT_MS]); then it finishes. It writes what it did to `<root>/debug-command.json`.
 */
class DebugCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CassiniApplication
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: COMMAND_OPEN
        val file = intent.getStringExtra(EXTRA_FILE)
        val graph = app.graph
        if (graph.paths.root == filesDir) {
            Log.w(TAG, "No debug root: files/${AppRoots.DEBUG_ROOT_FILE} is missing; doing nothing")
            finish()
            return
        }
        DebugStatus.start(graph)
        graph.appScope.launch(graph.dispatchers.main) {
            val result = JSONObject().put("cmd", command).put("startedAt", System.currentTimeMillis())
            try {
                run(graph, command, file, result)
            } catch (error: CancellationException) {
                throw error
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Log.e(TAG, "The debug command $command failed", error)
                result.put("error", error.toString())
            }
            result.put("serviceForeground", awaitForeground(graph)).put("endedAt", System.currentTimeMillis())
            DebugStatus.writeJson(graph, COMMAND_FILE, result)
            finish()
        }
    }

    private suspend fun run(graph: AppGraph, command: String, file: String?, result: JSONObject) {
        graph.ready.await()
        when (command) {
            COMMAND_IMPORT -> {
                val name = requireNotNull(file) { "cmd import needs --es file NAME" }
                val source = File(graph.paths.cache, name)
                require(source.isFile && source.parentFile == graph.paths.cache) { "No file $source" }
                val noteId = when (val outcome = graph.awaitCatalogue().imports.import(Uri.fromFile(source))) {
                    is ImportOutcome.Created -> outcome.noteId
                    is ImportOutcome.Existing -> outcome.noteId
                    is ImportOutcome.Rejected -> error("Import rejected: ${outcome.failure} ${outcome.detail}")
                }
                result.put("noteId", noteId)
                result.put("transcribe", graph.processing.transcribe(noteId, origin = StartOrigin.SCREEN).toString())
            }
            COMMAND_OPEN -> Unit
            else -> error("Unknown command $command")
        }
    }

    /** Waits until the queue is idle or the service is in the foreground; the visible screen allows its start. */
    private suspend fun awaitForeground(graph: AppGraph): Boolean = withTimeoutOrNull(FOREGROUND_WAIT_MS) {
        graph.processing.state.first { state ->
            !state.loading && (state.serviceForeground || (state.queue.isEmpty() && state.running == null))
        }
    }?.serviceForeground ?: false

    companion object {
        const val EXTRA_COMMAND = "cmd"
        const val EXTRA_FILE = "file"
        const val COMMAND_IMPORT = "import"
        const val COMMAND_OPEN = "open"
        const val COMMAND_FILE = "debug-command.json"
        private const val TAG = "DebugCommand"
        private const val FOREGROUND_WAIT_MS = 10_000L
    }
}
