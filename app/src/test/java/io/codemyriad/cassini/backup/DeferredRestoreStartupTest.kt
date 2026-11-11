package io.codemyriad.cassini.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.format.OggOpus
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.JobKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.core.library.Origin
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.backup.LibraryBackup
import io.codemyriad.cassini.data.db.CassiniDatabaseOpener
import io.codemyriad.cassini.data.db.JobEntity
import io.codemyriad.cassini.data.db.NoteEntity
import io.codemyriad.cassini.data.models.ModelFetcher
import io.codemyriad.cassini.data.notes.RecoveredTitle
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class DeferredRestoreStartupTest {
    @get:Rule(order = Int.MIN_VALUE)
    val uncaught = NoUncaughtCoroutines()

    @get:Rule val folder = TemporaryFolder()
    private val receivedId = "00000000-0000-4000-8000-000000000003"
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
            Files.createDirectories(source.note(receivedId).toPath())
            withContext(Dispatchers.IO) {
                File(source.note(receivedId), "document.opus").writeBytes(
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
                CassiniDocument.read(File(source.note(receivedId), "document.opus")).defaultId
            }
            catalogue.database.notes().insert(
                NoteEntity(
                    receivedId, "Received meeting", createdAt = 1, addedAt = 1,
                    origin = Origin.IMPORTED_DOCUMENT, audioFile = "document.opus", audioKind = AudioKind.DOCUMENT,
                    selectedVariant = selected, updatedAt = 1,
                ),
            )
            catalogue.database.jobs().insert(
                JobEntity(receivedId, 1, JobKind.TRANSCRIBE_AGAIN, JobState.QUEUED, enqueuedAt = 1, updatedAt = 1),
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
                    receiver.receive(path, it, file.length(), file.lastModified() / 1000, Long.MAX_VALUE)
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
            val row = checkNotNull(catalogue.database.notes().note(receivedId))
            assertEquals(DocumentState.OK, row.documentState)
            assertNotNull(row.selectedVariant)
            assertNull(catalogue.database.jobs().job(receivedId))
            val variant = catalogue.database.cache().variants(receivedId).single()
            assertEquals(1, variant.wordCount)
            assertEquals("Ada", catalogue.database.cache().speakers(receivedId, variant.variantId).single().label)
            withTimeout(30_000) { restored.ready.await() }
            assertFalse(File(staging, "applying.json").exists())
        } finally {
            restored.close()
        }
    }

    @Test fun plainCaptureWithoutACatalogueRebuildsItsActualDateAndTitleFromTarSeconds() = runBlocking {
        val id = "00000000-0000-4000-8000-000000000002"
        val source = folder.newFolder()
        val audio = File(source, "notes/$id/capture.opus")
        Files.createDirectories(audio.parentFile!!.toPath())
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0, 0, -128, -69, 0, 0, 0, 0, 0)
        val tags = CassiniDocument.tagsPacket(emptyList())
        audio.writeBytes(OggOpus.mux(OggOpus.Stream(head, tags, listOf(byteArrayOf(-8, -1, -2)), 960, true), tags))
        val modified = Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()
        Files.setLastModifiedTime(audio.toPath(), FileTime.fromMillis(modified + 321))
        val manifest = BackupManifest.create(source).json().toByteArray()
        val paths = AppPaths(folder.newFolder(), folder.newFolder(), folder.newFolder())
        val backup = folder.newFolder()
        withContext(Dispatchers.IO) {
            val receiver = LibraryBackup(context, paths, File(backup, "restores"))
            audio.inputStream().use {
                receiver.receive(
                    "notes/$id/capture.opus",
                    it,
                    audio.length(),
                    audio.lastModified() / 1000,
                    Long.MAX_VALUE,
                )
            }
            receiver.receive(
                BackupManifest.MANIFEST,
                manifest.inputStream(),
                manifest.size.toLong(),
                modified / 1000,
                Long.MAX_VALUE,
            )
            receiver.finishRestore()
        }
        val restored = graph(paths, backup)
        try {
            withTimeout(30_000) { restored.ready.await() }
            val row = checkNotNull(restored.awaitCatalogue().database.notes().note(id))
            assertEquals(modified, File(paths.note(id), "capture.opus").lastModified())
            assertEquals(20L, row.durationMs)
            assertEquals(modified - 20, row.createdAt)
            assertEquals(RecoveredTitle.english().of(modified - 20), row.title)
        } finally {
            restored.close()
        }
    }

    @Test fun corruptUnappliedTransferKeepsTheExistingLibraryAccessibleAndReportsTheRejection() = runBlocking {
        val target = sourceWithQueuedWork()
        withContext(Dispatchers.IO) {
            val opened = CassiniDatabaseOpener(context, target).open()
            try {
                opened.database.openHelper.writableDatabase.execSQL("DELETE FROM jobs")
            } finally {
                opened.database.close()
            }
        }
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
            withTimeout(30_000) { restored.ready.await() }
            val catalogue = restored.awaitCatalogue()
            assertEquals("Received meeting", catalogue.database.notes().note(receivedId)?.title)
            assertNull(catalogue.database.notes().note("n"))
            assertTrue(target.database.exists())
            assertNotNull(restored.startupReport.value?.failed?.get(AppGraph.STEP_RESTORE))
            assertFalse(File(staging, "applying.json").exists())
        } finally {
            restored.close()
        }
    }
}
