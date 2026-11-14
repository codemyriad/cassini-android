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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
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
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [BackupPreflightTest.EmissionShadow::class])
class BackupPreflightTest {
    // A @Config shadow's @Resetter is not run between tests, so every test starts from an empty emission list.
    @Before fun clearEmissions() = EmissionShadow.files.clear()

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

    @Test fun encryptedCloudBackupEmitsTheLibraryAndKeepsItsOwnSizeWarning() = runBlocking {
        val app: CassiniApplication = ApplicationProvider.getApplicationContext()
        val agent = Robolectric.setupBackupAgent(CassiniBackupAgent::class.java)
        withContext(Dispatchers.IO) {
            library(app)
            val choices = BackupChoicesStore(File(app.noBackupFilesDir, "choices.json"), Dispatchers.IO)
            choices.recordQuotaExceeded(true, cloud = false)
            agent.onFullBackup(output(flags = CLIENT_SIDE_ENCRYPTION))
            assertEquals(
                listOf("notes/n/source.opus", "voices/notes/n.json", "voices/people.json", BackupManifest.MANIFEST),
                emitted(app),
            )
            agent.onQuotaExceeded(30_000_000, 25_000_000)
            val value = choices.readBlocking()
            assertTrue(value.cloudQuotaExceeded)
            assertTrue(value.quotaExceeded) // The transfer warning belongs to the other channel.
            assertFalse(app.hasStartedGraph())
        }
    }

    @Test fun savedVoicesOffLeavesEveryVoiceprintOutOfCloudAndTransfer() = runBlocking {
        val app: CassiniApplication = ApplicationProvider.getApplicationContext()
        val agents = List(2) { Robolectric.setupBackupAgent(CassiniBackupAgent::class.java) }
        withContext(Dispatchers.IO) {
            library(app)
            val choices = BackupChoicesStore(File(app.noBackupFilesDir, "choices.json"), Dispatchers.IO)
            choices.setVoices(false)
            for ((agent, flags) in agents.zip(listOf(CLIENT_SIDE_ENCRYPTION, DEVICE_TO_DEVICE))) {
                EmissionShadow.files.clear()
                agent.onFullBackup(output(flags))
                assertEquals(listOf("notes/n/source.opus", BackupManifest.MANIFEST), emitted(app))
                val inventory = BackupManifest.read(File(app.filesDir, BackupManifest.MANIFEST))
                assertFalse(inventory.files.any { it.path.startsWith("voices/") })
            }
        }
    }

    @Test fun unencryptedCloudAndTheCloudChoiceOffEmitNothing() = runBlocking {
        val app: CassiniApplication = ApplicationProvider.getApplicationContext()
        val agent = Robolectric.setupBackupAgent(CassiniBackupAgent::class.java)
        withContext(Dispatchers.IO) {
            library(app)
            assertThrows(IOException::class.java) { agent.onFullBackup(output(flags = 0)) }
            val choices = BackupChoicesStore(File(app.noBackupFilesDir, "choices.json"), Dispatchers.IO)
            choices.setCloud(false)
            assertThrows(IOException::class.java) { agent.onFullBackup(output(flags = CLIENT_SIDE_ENCRYPTION)) }
            assertTrue(EmissionShadow.files.isEmpty())
            choices.keepOnPhoneOnly()
            assertThrows(IOException::class.java) { agent.onFullBackup(output(flags = DEVICE_TO_DEVICE)) }
            assertTrue(EmissionShadow.files.isEmpty())
        }
    }

    private fun library(app: CassiniApplication) {
        for ((path, text) in listOf(
            "voices/people.json" to "Synthetic saved voices",
            "voices/notes/n.json" to "Synthetic note voiceprints",
            "notes/n/source.opus" to "Synthetic audio bytes",
        )) {
            val file = File(app.filesDir, path)
            Files.createDirectories(file.parentFile!!.toPath())
            file.writeText(text)
        }
    }

    private fun output(flags: Int): FullBackupDataOutput =
        ReflectionHelpers.newInstance(FullBackupDataOutput::class.java).also {
            ReflectionHelpers.setField(it, "mTransportFlags", flags)
            ReflectionHelpers.setField(it, "mQuota", Long.MAX_VALUE)
        }

    private fun emitted(app: CassiniApplication) =
        EmissionShadow.files.map { it.relativeTo(app.filesDir).invariantSeparatorsPath }

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

    private companion object {
        const val CLIENT_SIDE_ENCRYPTION = 1
        const val DEVICE_TO_DEVICE = 2
    }
}
