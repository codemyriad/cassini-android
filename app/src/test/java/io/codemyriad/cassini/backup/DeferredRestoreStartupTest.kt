package io.codemyriad.cassini.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.JobKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.core.library.Origin
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.backup.LibraryBackup
import io.codemyriad.cassini.data.db.JobEntity
import io.codemyriad.cassini.data.db.NoteEntity
import io.codemyriad.cassini.data.models.ModelFetcher
import io.codemyriad.cassini.testing.NoUncaughtCoroutines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class DeferredRestoreStartupTest {
    @get:Rule(order = Int.MIN_VALUE)
    val uncaught = NoUncaughtCoroutines()

    @get:Rule val folder = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun graph(paths: AppPaths, backup: File) = AppGraph(
        context,
        AppGraph.Config(
            paths = paths,
            backupDirectory = backup,
            network = { false },
            fetcher = ModelFetcher { _, _, _ -> error("No model download is authorized in this test") },
        ),
    )

    private suspend fun sourceWithQueuedWork(): AppPaths {
        val source = AppPaths(folder.newFolder(), folder.newFolder(), folder.newFolder())
        val sourceBackup = folder.newFolder()
        val first = graph(source, sourceBackup)
        try {
            withTimeout(30_000) { first.ready.await() }
            val catalogue = first.awaitCatalogue()
            val audio = File(checkNotNull(System.getProperty("cassini.fixtures")), "audio/fleurs-it-short.opus")
            Files.createDirectories(source.note("received").toPath())
            withContext(Dispatchers.IO) {
                File(source.note("received"), "document.opus").writeBytes(
                    CassiniDocument.create(
                        audio.readBytes(),
                        Transcript(listOf(Word("S1", 0, 400, "Welcome"))),
                        "Received meeting",
                        JSONObject(),
                        speakerLabels = mapOf("S1" to "Ada"),
                    ),
                )
            }
            val selected = withContext(Dispatchers.IO) {
                CassiniDocument.read(File(source.note("received"), "document.opus")).defaultId
            }
            catalogue.database.notes().insert(
                NoteEntity(
                    "received", "Received meeting", createdAt = 1, addedAt = 1,
                    origin = Origin.IMPORTED_DOCUMENT, audioFile = "document.opus", audioKind = AudioKind.DOCUMENT,
                    selectedVariant = selected, updatedAt = 1,
                ),
            )
            catalogue.database.jobs().insert(
                JobEntity("received", 1, JobKind.TRANSCRIBE_AGAIN, JobState.QUEUED, enqueuedAt = 1, updatedAt = 1),
            )
        } finally {
            first.close()
        }
        return source
    }

    @Test fun catalogueIsValidatedAndRebuiltBeforeTheGraphPublishesIt() = runBlocking {
        val source = sourceWithQueuedWork()
        val sourceBackup = folder.newFolder()
        val target = AppPaths(folder.newFolder(), folder.newFolder(), folder.newFolder())
        val backup = folder.newFolder()
        val staging = File(backup, "restores")
        withContext(Dispatchers.IO) {
            val manifest = LibraryBackup(context, source, File(sourceBackup, "restores")).prepare().first
            val receiver = LibraryBackup(context, target, staging)
            for (path in listOf(BackupManifest.MANIFEST) + manifest.files.map { it.path }) {
                val file = File(source.root, path)
                file.inputStream().use {
                    receiver.receive(path, it, file.length(), file.lastModified(), Long.MAX_VALUE)
                }
            }
            receiver.finishRestore()
        }
        assertFalse(target.database.exists())
        assertTrue(File(staging, "applying.json").isFile)
        val restored = graph(target, backup)
        try {
            // awaitCatalogue, not the later ready event: no consumer can see unvalidated restored rows/jobs.
            val catalogue = withTimeout(30_000) { restored.awaitCatalogue() }
            val row = checkNotNull(catalogue.database.notes().note("received"))
            assertEquals(DocumentState.OK, row.documentState)
            assertNotNull(row.selectedVariant)
            assertNull(catalogue.database.jobs().job("received"))
            val variant = catalogue.database.cache().variants("received").single()
            assertEquals(1, variant.wordCount)
            assertEquals("Ada", catalogue.database.cache().speakers("received", variant.variantId).single().label)
            withTimeout(30_000) { restored.ready.await() }
            assertFalse(File(staging, "applying.json").exists())
        } finally {
            restored.close()
        }
    }

    @Test fun invalidDeferredPayloadRefusesStartupBeforeOpeningTheLiveCatalogue() = runBlocking {
        val target = AppPaths(folder.newFolder(), folder.newFolder(), folder.newFolder())
        val backup = folder.newFolder()
        val staging = File(backup, "restores")
        val source = folder.newFolder()
        val file = File(source, "notes/n/source.opus")
        Files.createDirectories(file.parentFile!!.toPath())
        file.writeText("Received audio")
        val manifest = BackupManifest.create(source)
        val inventory = File(source, "inventory.json").also { it.writeText(manifest.json()) }
        withContext(Dispatchers.IO) {
            val receiver = LibraryBackup(context, target, staging)
            for ((path, value) in listOf(BackupManifest.MANIFEST to inventory, "notes/n/source.opus" to file)) {
                value.inputStream().use { receiver.receive(path, it, value.length(), 1, Long.MAX_VALUE) }
            }
            receiver.finishRestore()
        }
        val stage = staging.listFiles()!!.single { it.isDirectory }
        File(stage, "incoming/notes/n/source.opus").writeText("Changed audio!")
        val restored = graph(target, backup)
        try {
            val error = assertThrows(IllegalArgumentException::class.java) {
                runBlocking { withTimeout(30_000) { restored.awaitCatalogue() } }
            }
            assertEquals("Incomplete or changed library backup", error.message)
            assertFalse(target.database.exists())
            assertTrue(File(staging, "applying.json").isFile)
        } finally {
            restored.close()
        }
    }
}
