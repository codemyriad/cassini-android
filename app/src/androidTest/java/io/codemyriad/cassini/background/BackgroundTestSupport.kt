package io.codemyriad.cassini.background

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Printer
import androidx.test.platform.app.InstrumentationRegistry
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.CassiniApplication
import io.codemyriad.cassini.data.db.LibraryRow
import io.codemyriad.cassini.data.notes.ImportOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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

    /** The `ProcessingService` part of `dumpsys activity services` (empty when it does not run). */
    fun processingService(): String = shell("dumpsys activity services ${context.packageName}")
        .substringAfter("ServiceRecord", "").substringAfter("ProcessingService", "")

    /**
     * True when the dumpsys text of a service ([processingService]) shows a foreground service type. API 30+ prints
     * the types as hex, for example `types=0x00002000`; the dump of API 29 shows no type. For the type that Android
     * has on every API from 29, use `ProcessingService.currentType()`.
     */
    fun showsType(service: String): Boolean = Regex("\\b(types|foregroundServiceType)=").containsMatchIn(service)

    /** True when the dumpsys text of a service ([processingService]) gives the foreground service types [type]. */
    fun hasType(service: String, type: Int): Boolean =
        Regex("\\b(types|foregroundServiceType)=(0x)?0*${Integer.toHexString(type)}\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(service)

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
        withTimeoutOrNull(SERVICE_STOP_MS) {
            processing.state.first { !it.serviceForeground && !it.draining }
        } ?: throw AssertionError("The processing service did not leave the foreground: ${processing.state.value}")
    }

    const val WAIT_MS = 240_000L
    private const val SERVICE_STOP_MS = 30_000L
}

/**
 * Samples thread CPU time around each main-looper dispatch. [stop] returns the dispatch that used the most CPU.
 */
internal class MainLooperWatch : Printer {
    data class Dispatch(val message: String, val cpuMs: Long)

    private var startedCpuMs = 0L
    private var current: String? = null
    private var longest: Dispatch? = null

    fun start() = Background.instrumentation.runOnMainSync {
        Looper.getMainLooper().setMessageLogging(this)
    }

    fun stop(): Dispatch? {
        Background.instrumentation.runOnMainSync {
            Looper.getMainLooper().setMessageLogging(null)
            // The looper can still log the end of the dispatch that removes the logger.
            current = null
        }
        return longest
    }

    override fun println(line: String) {
        if (line.startsWith(">>>>> Dispatching")) {
            startedCpuMs = SystemClock.currentThreadTimeMillis()
            current = line
        } else if (line.startsWith("<<<<< Finished")) {
            val message = current ?: return
            val cpuMs = SystemClock.currentThreadTimeMillis() - startedCpuMs
            if (cpuMs > (longest?.cpuMs ?: -1L)) longest = Dispatch(message, cpuMs)
            current = null
        }
    }
}
