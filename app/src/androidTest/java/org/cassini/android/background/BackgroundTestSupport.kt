package org.cassini.android.background

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Printer
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.cassini.android.AppGraph
import org.cassini.android.CassiniApplication
import org.cassini.data.db.LibraryRow
import org.cassini.data.notes.ImportOutcome
import org.junit.Assert.assertTrue
import java.io.File

/**
 * Shared parts of the background device tests. The graph is the one that `CassiniTestRunner` built on the scratch
 * root of this run, with the models of `filesDir`.
 */
internal object Background {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext
    val graph: AppGraph get() = (context.applicationContext as CassiniApplication).graph

    /** Runs [command] as the shell user and returns its output. */
    fun shell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Grants the runtime permissions the services need (notifications on API 33+, and [extra]). */
    fun grant(vararg extra: String) {
        val permissions = extra.toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions += Manifest.permission.POST_NOTIFICATIONS
        permissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
    }

    /** Copies the test asset [name] into the scratch cache and returns the copy. */
    fun assetCopy(name: String): File {
        val target = File(graph.paths.cache, "imports/" + name.substringAfterLast('/'))
        target.parentFile?.mkdirs()
        val assets = instrumentation.context.assets
        val found = name.substringBeforeLast('/', "").let { directory -> assets.list(directory).orEmpty() }
            .contains(name.substringAfterLast('/'))
        assertTrue("Missing test asset $name: run scripts/make-long-clip.sh before the build", found)
        assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    /** Imports [file] through `ImportRepository` from its `file://` Uri and returns the new note's id. */
    suspend fun import(file: File): String {
        graph.ready.await()
        return when (val outcome = graph.awaitCatalogue().imports.import(Uri.fromFile(file))) {
            is ImportOutcome.Created -> outcome.noteId
            is ImportOutcome.Existing -> outcome.noteId
            is ImportOutcome.Rejected -> throw AssertionError("Import of ${file.name} rejected: $outcome")
        }
    }

    /** The Library row of [noteId] (its job state, document state and audio kind). */
    suspend fun row(noteId: String): LibraryRow? =
        graph.awaitCatalogue().notes.library().first().firstOrNull { it.id == noteId }

    /** Polls [value] every 200 ms until it is not null, or fails with [message] after [timeoutMs]. */
    suspend fun <T : Any> waitFor(message: String, timeoutMs: Long = WAIT_MS, value: suspend () -> T?): T {
        val found = withTimeoutOrNull(timeoutMs) {
            var current = value()
            while (current == null) {
                delay(POLL_MS)
                current = value()
            }
            current
        }
        return found ?: throw AssertionError("$message (waited ${timeoutMs / 1_000} s)")
    }

    /**
     * Ends every job and puts every note of the scratch root in the trash, so the next test starts with an empty
     * queue and its imports are new notes (a trashed note does not count as a duplicate). Then clears the hold and
     * waits until the processing service has left the foreground.
     */
    suspend fun reset() {
        val processing = graph.processing
        processing.stopAll()
        val catalogue = graph.awaitCatalogue()
        processing.state.value.running?.let { processing.cancel(it.noteId, discard = true) }
        processing.awaitIdle()
        for (row in catalogue.notes.library().first()) {
            if (row.jobState != null) catalogue.jobs.remove(row.id)
            processing.moveToTrash(row.id)
        }
        graph.settings.setQueueHold(null)
        waitFor("The processing service leaves the foreground", SERVICE_STOP_MS) {
            processing.state.value.takeUnless { it.serviceForeground || it.draining }
        }
    }

    const val WAIT_MS = 240_000L
    private const val SERVICE_STOP_MS = 30_000L
    private const val POLL_MS = 200L
}

/**
 * Measures how long each message of the main looper runs, through `Looper.setMessageLogging`. [stop] removes it;
 * [longest] is the longest dispatch seen, with its message.
 */
internal class MainLooperWatch : Printer {
    @Volatile private var started = 0L

    @Volatile private var current: String? = null

    @Volatile var longestMs = 0L
        private set

    @Volatile var longest: String? = null
        private set

    fun start() = Looper.getMainLooper().setMessageLogging(this)

    fun stop() = Looper.getMainLooper().setMessageLogging(null)

    override fun println(line: String) {
        if (line.startsWith(">>>>> Dispatching")) {
            started = SystemClock.uptimeMillis()
            current = line
        } else if (line.startsWith("<<<<< Finished") && started != 0L) {
            val ms = SystemClock.uptimeMillis() - started
            if (ms > longestMs) {
                longestMs = ms
                longest = current
            }
            started = 0L
        }
    }
}
