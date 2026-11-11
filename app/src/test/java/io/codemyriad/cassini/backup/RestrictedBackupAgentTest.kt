package io.codemyriad.cassini.backup

import android.app.Application
import android.app.backup.BackupAgent
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.core.speakers.VoiceCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Auto Backup's restricted mode does not construct the app's Application subclass or startup providers. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RestrictedBackupAgentTest {
    @get:Rule val folder = TemporaryFolder()
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun restrictedModeHonorsCloudConsentAndCanRestoreWithoutTheAppGraph() = runBlocking(Dispatchers.IO) {
        assertEquals(Application::class.java, app.javaClass)
        val agent = Robolectric.setupBackupAgent(CassiniBackupAgent::class.java)
        val output = ReflectionHelpers.newInstance(FullBackupDataOutput::class.java)
        val error = assertThrows(IOException::class.java) { agent.onFullBackup(output) }
        assertEquals("This backup transport is disabled", error.message)
        val source = folder.newFolder()
        val people = File(source, "voices/people.json")
        Files.createDirectories(people.parentFile!!.toPath())
        people.writeText(VoiceCodec.encode(emptyList()))
        val manifest = BackupManifest.create(source)
        val inventory = File(source, "inventory.json").also { it.writeText(manifest.json()) }
        for ((path, file) in listOf(BackupManifest.MANIFEST to inventory, "voices/people.json" to people)) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                agent.onRestoreFile(fd, file.length(), File(app.filesDir, path), BackupAgent.TYPE_FILE, 0, 1)
            }
        }
        assertFalse(File(app.filesDir, "voices/people.json").exists())
        agent.onRestoreFinished()
        assertEquals(people.readText(), File(app.filesDir, "voices/people.json").readText())
    }
}
