package io.codemyriad.cassini.backup

import android.app.backup.BackupAgent
import android.app.backup.FullBackupDataOutput
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.CassiniApplication
import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.data.backup.BackupChoicesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [BackupPreflightTest.EmissionShadow::class])
class BackupPreflightTest {
    @Test fun quotaDoesNotShortCircuitMeasurementAndTheInventoryIsEmittedLast() = runBlocking {
        val app: CassiniApplication = ApplicationProvider.getApplicationContext()
        val agent = Robolectric.setupBackupAgent(CassiniBackupAgent::class.java)
        withContext(Dispatchers.IO) {
            val people = File(app.filesDir, "voices/people.json")
            Files.createDirectories(people.parentFile!!.toPath())
            people.writeText("Synthetic saved voices")
            val audio = File(app.filesDir, "notes/n/source.opus")
            Files.createDirectories(audio.parentFile!!.toPath())
            audio.writeText("Synthetic audio bytes")
            val output = ReflectionHelpers.newInstance(FullBackupDataOutput::class.java)
            ReflectionHelpers.setField(output, "mTransportFlags", 2)
            ReflectionHelpers.setField(output, "mQuota", 1L)
            val choices = BackupChoicesStore(File(app.noBackupFilesDir, "choices.json"), Dispatchers.IO)
            choices.recordQuotaExceeded(true)
            agent.onFullBackup(output)
            assertEquals(
                listOf("notes/n/source.opus", "voices/people.json", BackupManifest.MANIFEST),
                EmissionShadow.files.map { it.relativeTo(app.filesDir).invariantSeparatorsPath },
            )
            assertTrue(EmissionShadow.files.sumOf { it.length() } > output.quota)
            assertFalse(choices.readBlocking().quotaExceeded)
            // The framework, after measuring, reports quota failure; the app must retain that callback.
            agent.onQuotaExceeded(EmissionShadow.files.sumOf { it.length() }, output.quota)
            assertTrue(choices.readBlocking().quotaExceeded)
            assertFalse(app.hasStartedGraph())
        }
    }

    /** Observe emissions; the real local-transport test separately verifies Android's tar-framed quota result. */
    @Implements(BackupAgent::class)
    class EmissionShadow {
        @Implementation
        fun fullBackupFile(file: File, @Suppress("UNUSED_PARAMETER") output: FullBackupDataOutput) {
            files += file
        }

        companion object {
            val files = CopyOnWriteArrayList<File>()

            @JvmStatic
            @Resetter
            fun reset() = files.clear()
        }
    }
}
