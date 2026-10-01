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

    private fun finishScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.distinct()
                .filter { it is MainActivity || it is SettingsActivity || it is LibraryActivity }.forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
    }

    override fun before() {
        finishScreens()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        catalogue = listOf("library.json", "library-migrated").associateWith { File(directory, it).takeIf { file -> file.exists() }?.readBytes() }
        documents = File(directory, "documents").listFiles().orEmpty().map { it.name }.toSet()
    }

    override fun after() {
        finishScreens()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        catalogue.forEach { (name, bytes) -> File(directory, name).let { if (bytes == null) it.delete() else it.writeBytes(bytes) } }
        File(directory, "documents").listFiles().orEmpty().filter { it.name !in documents }.forEach { it.delete() }
    }
}
