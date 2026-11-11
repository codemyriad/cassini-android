package io.codemyriad.cassini.backup

import android.app.backup.BackupAgent
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.CassiniApplication
import io.codemyriad.cassini.GraphFactory
import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.backup.BackupChoicesStore
import io.codemyriad.cassini.data.backup.LibraryBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class CassiniBackupAgentTest {
    @get:Rule val folder = TemporaryFolder()
    private val app: CassiniApplication = ApplicationProvider.getApplicationContext()

    private fun <T> binder(block: () -> T): T = runBlocking { withContext(Dispatchers.IO) { block() } }

    private fun restore(agent: CassiniBackupAgent, path: String, text: String) {
        val input = folder.newFile()
        input.writeText(text)
        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            agent.onRestoreFile(fd, input.length(), File(app.filesDir, path), BackupAgent.TYPE_FILE, 0, 1)
        }
    }

    @Test fun anInitializingGraphIsAlreadyInUseBeforeItsLazyValueHasBeenPublished() {
        val started = CompletableFuture<Unit>()
        val release = CompletableFuture<Unit>()
        val executor = Executors.newSingleThreadExecutor()
        val agent = Robolectric.setupBackupAgent(CassiniBackupAgent::class.java)
        val output = ReflectionHelpers.newInstance(FullBackupDataOutput::class.java)
        CassiniApplication.graphFactory = GraphFactory { _, _ ->
            started.complete(Unit)
            release.get(10, TimeUnit.SECONDS)
            throw IOException("Synthetic constructor failure")
        }
        try {
            val construction = executor.submit {
                assertThrows(IOException::class.java) { app.graph }
            }
            started.get(10, TimeUnit.SECONDS)
            assertTrue(app.hasStartedGraph())
            val error = assertThrows(IOException::class.java) { binder { agent.onFullBackup(output) } }
            assertEquals("Library is in use", error.message)
            release.complete(Unit)
            construction.get(10, TimeUnit.SECONDS)
            assertTrue(app.hasStartedGraph()) // A failed initialization must not enable a snapshot either.
        } finally {
            release.complete(Unit)
            CassiniApplication.graphFactory = null
            executor.shutdownNow()
        }
    }

    @Test fun cloudIsRefusedBeforeAnyInventoryIsPublishedAndTheGraphStaysCold() {
        val agent = Robolectric.setupBackupAgent(CassiniBackupAgent::class.java)
        val output = ReflectionHelpers.newInstance(FullBackupDataOutput::class.java)
        assertFalse(app.hasStartedGraph())
        val error = assertThrows(IOException::class.java) { binder { agent.onFullBackup(output) } }
        assertEquals("This backup transport is disabled", error.message)
        assertFalse(File(app.filesDir, BackupManifest.MANIFEST).exists())
        assertFalse(app.hasStartedGraph())
    }

    @Test fun cloudBuildFlagRefusesEvenAnExistingOptIn() {
        assertFalse(io.codemyriad.cassini.BuildConfig.CLOUD_BACKUP_ENABLED)
        val choices = BackupChoicesStore(File(app.noBackupFilesDir, "choices.json"), Dispatchers.IO)
        binder { runBlocking { choices.setCloud(true) } }
        val agent = Robolectric.setupBackupAgent(CassiniBackupAgent::class.java)
        val output = ReflectionHelpers.newInstance(FullBackupDataOutput::class.java)
        val error = assertThrows(IOException::class.java) { binder { agent.onFullBackup(output) } }
        assertEquals("This backup transport is disabled", error.message)
        assertFalse(File(app.filesDir, BackupManifest.MANIFEST).exists())
    }

    @Test fun aFailedSnapshotPreparationPreservesTheOldQuotaFailure() {
        val agent = Robolectric.setupBackupAgent(CassiniBackupAgent::class.java)
        binder { agent.onQuotaExceeded(30_000_000, 25_000_000) }
        val choices = BackupChoicesStore(File(app.noBackupFilesDir, "choices.json"), Dispatchers.IO)
        assertTrue(binder { choices.readBlocking().quotaExceeded })
        // Stop the retry at a different, deterministic error after its transport has been permitted.
        val journal = File(app.noBackupFilesDir, "restores/applying.json")
        Files.createDirectories(journal.parentFile!!.toPath())
        journal.writeText(JSONObject().put("payload", "{}").put("sha256", "0".repeat(64)).toString())
        val output = ReflectionHelpers.newInstance(FullBackupDataOutput::class.java)
        ReflectionHelpers.setField(output, "mTransportFlags", 2)
        ReflectionHelpers.setField(output, "mQuota", Long.MAX_VALUE)
        val error = assertThrows(IllegalArgumentException::class.java) { binder { agent.onFullBackup(output) } }
        assertEquals("Damaged restore journal", error.message)
        assertTrue(binder { choices.readBlocking().quotaExceeded })
        assertFalse(File(app.filesDir, BackupManifest.MANIFEST).exists())
        assertFalse(app.hasStartedGraph())
    }

    @Test fun aRefusedTransportDoesNotClearThePreviousSizeFailure() {
        val agent = Robolectric.setupBackupAgent(CassiniBackupAgent::class.java)
        binder { agent.onQuotaExceeded(30_000_000, 25_000_000) }
        val output = ReflectionHelpers.newInstance(FullBackupDataOutput::class.java)
        val error = assertThrows(IOException::class.java) { binder { agent.onFullBackup(output) } }
        assertEquals("This backup transport is disabled", error.message)
        val choices = BackupChoicesStore(File(app.noBackupFilesDir, "choices.json"), Dispatchers.IO)
        assertTrue(binder { choices.readBlocking().quotaExceeded })
    }

    @Test fun restoredLibraryDoesNotRequireOrRestoreAnOldCloudOptInAndDoesNotLoadModels() {
        val source = folder.newFolder()
        File(source, "voices").mkdirs()
        File(source, "voices/people.json").writeText("Synthetic saved voice")
        val manifest = BackupManifest.create(source)
        val model = File(app.filesDir, "models/synthetic.bin").also {
            it.parentFile!!.mkdirs()
            it.writeText("target model")
        }
        val agent = Robolectric.setupBackupAgent(CassiniBackupAgent::class.java)
        binder {
            restore(agent, BackupManifest.MANIFEST, manifest.json())
            restore(agent, "voices/people.json", "Synthetic saved voice")
            assertFalse(File(app.filesDir, "voices/people.json").exists())
            agent.onRestoreFinished()
            assertFalse(File(app.filesDir, "voices/people.json").exists())
            val paths = AppPaths(app.filesDir, app.cacheDir)
            LibraryBackup(app, paths, File(app.noBackupFilesDir, "restores")).recoverRestore()
        }
        assertEquals("Synthetic saved voice", File(app.filesDir, "voices/people.json").readText())
        assertEquals("target model", model.readText())
        val choices = BackupChoicesStore(File(app.noBackupFilesDir, "choices.json"), Dispatchers.IO)
        assertFalse(binder { choices.readBlocking().cloud })
        assertFalse(app.hasStartedGraph())
    }

    @Test fun unexpectedPathsAndMissingManifestLeaveTheCurrentLibraryUntouched() {
        val old = File(app.filesDir, "voices/people.json").also {
            it.parentFile!!.mkdirs()
            it.writeText("original")
        }
        val agent = Robolectric.setupBackupAgent(CassiniBackupAgent::class.java)
        assertThrows(IOException::class.java) { binder { restore(agent, "models/injected.bin", "unwanted") } }
        assertThrows(IOException::class.java) { binder { restore(agent, "../outside.bin", "unwanted") } }
        assertThrows(IOException::class.java) { binder { restore(agent, "voices/people.json", "uncommitted") } }
        assertThrows(IOException::class.java) { binder { restore(agent, BackupManifest.MANIFEST, "{}") } }
        assertThrows(IOException::class.java) { binder { agent.onRestoreFinished() } }
        assertEquals("original", old.readText())
        assertFalse(File(app.filesDir, "models/injected.bin").exists())
        assertFalse(app.hasStartedGraph())
    }

    @Test fun quotaCallbackPersistsAFailureWithoutClaimingAnUploadOrGrantingConsent() {
        val agent = Robolectric.setupBackupAgent(CassiniBackupAgent::class.java)
        binder { agent.onQuotaExceeded(30_000_000, 25_000_000) }
        val choices = BackupChoicesStore(File(app.noBackupFilesDir, "choices.json"), Dispatchers.IO)
        val value = binder { choices.readBlocking() }
        assertTrue(value.quotaExceeded)
        assertFalse(value.cloud)
        assertTrue(value.transfer)
        assertFalse(app.hasStartedGraph())
    }
}
