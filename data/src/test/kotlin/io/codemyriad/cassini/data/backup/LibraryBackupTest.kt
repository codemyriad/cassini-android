package io.codemyriad.cassini.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.library.JobKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.core.speakers.Thresholds
import io.codemyriad.cassini.core.speakers.Voice
import io.codemyriad.cassini.core.speakers.VoiceCodec
import io.codemyriad.cassini.core.speakers.VoiceMatcher
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.db.CassiniDatabaseOpener
import io.codemyriad.cassini.data.db.JobEntity
import io.codemyriad.cassini.data.db.offMain
import io.codemyriad.cassini.data.notes.NotesHarness
import io.codemyriad.cassini.data.notes.fixtureOpus
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class LibraryBackupTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun put(root: File, path: String, text: String): File = File(root, path).also {
        Files.createDirectories(it.parentFile.toPath())
        it.writeText(text)
    }

    private fun transfer(source: File, manifest: BackupManifest, receiver: LibraryBackup) {
        for (path in listOf(BackupManifest.MANIFEST) + manifest.files.map { it.path }) {
            val file = File(source, path)
            file.inputStream().use {
                receiver.receive(path, it, file.length(), file.lastModified() / 1000, Long.MAX_VALUE)
            }
        }
    }

    @Test fun realCatalogueNotesAndSavedVoicesRestoreWithoutModelsConsentOrQueuedWork() = runTest {
        val source = NotesHarness(folder)
        try {
            val started = 1_700_000_000_000L
            val id = source.notes.createRecording("Original title", started, "opus")
            val capture = File(source.dir(id), "capture.opus")
            fixtureOpus.copyTo(capture)
            source.notes.finishRecording(id, 1000)
            offMain {
                source.documents.sealBlocking(
                    id,
                    capture,
                    Transcript(listOf(Word("S1", 0, 400, "Welcome"))),
                    "Original title",
                    JSONObject(),
                    null,
                    mapOf("S1" to "Ada"),
                )
            }
            source.notes.adoptSealed(id)
            source.db.notes().update(source.row(id).copy(title = "Custom title", titleDirty = true))
            source.db.jobs().insert(
                JobEntity(id, 1, JobKind.TRANSCRIBE_AGAIN, JobState.QUEUED, enqueuedAt = 1, updatedAt = 1)
            )
            val voice = Voice("voice-ada", "Ada", "synthetic-model", floatArrayOf(1f, 0f, 0f), 1, 4.0, 1, 1)
            put(source.paths.root, "voices/people.json", VoiceCodec.encode(listOf(voice)))
            put(source.paths.root, "models/weights.onnx", "must not transfer")
            put(source.paths.root, "settings.preferences_pb", "source download consent must not transfer")
            put(source.paths.root, "notes/$id/work/checkpoint.json", "must not resume")
            source.close() // The OS stops the app before invoking its backup agent.
            val snapshot = LibraryBackup(context, source.paths, folder.newFolder())
            val manifest = offMain { snapshot.prepare().first }
            val target = AppPaths(folder.newFolder(), folder.newFolder())
            put(target.root, "settings.preferences_pb", "destination choice")
            val receiver = LibraryBackup(context, target, folder.newFolder())
            offMain {
                transfer(source.paths.root, manifest, receiver)
                receiver.finishRestore()
                receiver.recoverRestore()
            }
            assertFalse(File(target.root, "models").exists())
            assertFalse(File(target.root, "notes/$id/work").exists())
            assertEquals("destination choice", target.settings.readText())
            assertRestored(target, id, started)
        } finally {
            source.close()
        }
    }

    private suspend fun assertRestored(target: AppPaths, id: String, started: Long) {
        val opened = offMain { CassiniDatabaseOpener(context, target).open() }
        try {
            val row = opened.database.notes().note(id)!!
            assertEquals("Custom title", row.title)
            assertEquals(started, row.createdAt)
            assertNull(opened.database.jobs().job(id))
            assertEquals(1, opened.database.cache().variants(id).size)
            assertEquals("Ada", opened.database.cache().speakers(id, row.selectedVariant!!).single().label)
            val restoredVoices = VoiceCodec.decode(target.people.readText())
            val match = VoiceMatcher.match(
                mapOf("new-speaker" to floatArrayOf(1f, 0f, 0f)),
                restoredVoices,
                "synthetic-model",
                Thresholds(0.9f, 0.8f, 0.1f),
            )
            assertEquals("voice-ada", match.getValue("new-speaker").voiceId)
        } finally {
            opened.database.close()
        }
    }

    @Test fun missingOrTruncatedPayloadNeverReplacesTheExistingLibrary() {
        val source = folder.newFolder()
        put(source, "notes/n/source.opus", "complete audio")
        val manifest = BackupManifest.create(source)
        put(source, BackupManifest.MANIFEST, manifest.json())
        val target = AppPaths(folder.newFolder(), folder.newFolder())
        val old = put(target.root, "notes/old/document.opus", "existing library")
        val receiver = LibraryBackup(context, target, folder.newFolder())
        offMain {
            val metadata = File(source, BackupManifest.MANIFEST)
            metadata.inputStream().use {
                receiver.receive(BackupManifest.MANIFEST, it, metadata.length(), 1, Long.MAX_VALUE)
            }
            assertThrows(IllegalArgumentException::class.java) { receiver.finishRestore() }
            receiver.recoverRestore()
            val truncated = LibraryBackup(context, target, folder.newFolder())
            assertThrows(IOException::class.java) {
                truncated.receive("notes/n/source.opus", ByteArrayInputStream(byteArrayOf(1)), 10, 1, Long.MAX_VALUE)
            }
            truncated.abandon()
        }
        assertEquals("existing library", old.readText())
        assertFalse(File(target.root, "notes/n").exists())
    }

    @Test fun modelPathsDuplicateEntriesAndInsufficientSpaceAreRefusedBeforePromotion() {
        val target = AppPaths(folder.newFolder(), folder.newFolder())
        val model = put(target.root, "models/weights.onnx", "existing model")
        val receiver = LibraryBackup(context, target, folder.newFolder())
        offMain {
            assertThrows(IllegalArgumentException::class.java) {
                receiver.receive("models/weights.onnx", ByteArrayInputStream(byteArrayOf(1)), 1, 1, Long.MAX_VALUE)
            }
            assertThrows(IOException::class.java) {
                LibraryBackup(context, target, folder.newFolder()).receive(
                    "notes/n/source.opus",
                    ByteArrayInputStream(byteArrayOf(1)),
                    1,
                    1,
                    0,
                )
            }
            val duplicate = LibraryBackup(context, target, folder.newFolder())
            duplicate.receive("notes/n/source.opus", ByteArrayInputStream(byteArrayOf(1)), 1, 1, Long.MAX_VALUE)
            assertThrows(IllegalArgumentException::class.java) {
                duplicate.receive("notes/n/source.opus", ByteArrayInputStream(byteArrayOf(2)), 1, 1, Long.MAX_VALUE)
            }
            receiver.abandon()
        }
        assertEquals("existing model", model.readText())
        assertFalse(File(target.root, "notes").exists())
        assertTrue(target.root.isDirectory)
    }
}
