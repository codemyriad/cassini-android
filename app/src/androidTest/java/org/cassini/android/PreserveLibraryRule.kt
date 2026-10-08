package org.cassini.android

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.rules.ExternalResource
import java.io.File

/** Existing viewer tests use synthetic sessions. Keep those fixtures out of the user's library. */
class PreserveLibraryRule : ExternalResource() {
    private var catalogue = emptyMap<String, ByteArray?>()
    private var documents = emptySet<String>()
    private var voiceprints = emptyMap<String, ByteArray>()

    private fun finishScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var screens = emptyList<android.app.Activity>()
        instrumentation.runOnMainSync {
            screens = Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.distinct()
                .filter { it is MainActivity || it is SettingsActivity || it is LibraryActivity || it is RecordingActivity }
            screens.forEach { it.finish() }
        }
        // finish() requests an asynchronous lifecycle transition. Restoring the catalogue at the
        // first idle can race onPause() and its save; wait for destruction and pending audio writes.
        val deadline = android.os.SystemClock.uptimeMillis() + 10000
        var finished = false
        while (!finished && android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                finished = screens.all { it.isDestroyed && (it !is RecordingActivity || !it.savingAudio) }
            }
            if (!finished) android.os.SystemClock.sleep(25)
        }
        org.junit.Assert.assertTrue("Screens and audio writes must finish before restoring user data", finished)
        instrumentation.waitForIdleSync()
    }

    override fun before() {
        finishScreens()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.filesDir
        catalogue = listOf("library.json", "library-migrated", "session.json", "session.json.bak", "voices.json").associateWith { File(directory, it).takeIf { file -> file.exists() }?.readBytes() }
        documents = File(directory, "documents").listFiles().orEmpty().map { it.name }.toSet()
        voiceprints = File(directory, "voiceprints").listFiles().orEmpty().associate { it.name to it.readBytes() }
    }

    override fun after() {
        finishScreens()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.filesDir
        catalogue.forEach { (name, bytes) -> File(directory, name).let { if (bytes == null) it.delete() else it.writeBytes(bytes) } }
        File(directory, "documents").listFiles().orEmpty().filter { it.name !in documents }.forEach { it.delete() }
        File(directory, "voiceprints").listFiles().orEmpty().filter { it.name !in voiceprints }.forEach { it.delete() }
        // "Forget all voices" removes the whole directory.
        if (voiceprints.isNotEmpty()) File(directory, "voiceprints").mkdirs()
        voiceprints.forEach { (name, bytes) -> File(directory, "voiceprints/$name").writeBytes(bytes) }
    }
}
