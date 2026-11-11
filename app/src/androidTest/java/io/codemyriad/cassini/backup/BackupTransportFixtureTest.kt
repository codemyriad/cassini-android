package io.codemyriad.cassini.backup

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.format.OggOpus
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.JobKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.core.library.Origin
import io.codemyriad.cassini.core.speakers.Thresholds
import io.codemyriad.cassini.core.speakers.Voice
import io.codemyriad.cassini.core.speakers.VoiceCodec
import io.codemyriad.cassini.core.speakers.VoiceMatcher
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.NoteLocks
import io.codemyriad.cassini.data.backup.BackupChoicesStore
import io.codemyriad.cassini.data.db.CassiniDatabase
import io.codemyriad.cassini.data.db.CassiniDatabaseOpener
import io.codemyriad.cassini.data.db.JobEntity
import io.codemyriad.cassini.data.db.NoteEntity
import io.codemyriad.cassini.data.notes.DocumentRepository
import io.codemyriad.cassini.data.notes.recordingTime
import io.codemyriad.cassini.test.Manual
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.time.Instant

/** Only for an explicitly owned, empty debug emulator. No commands, receivers or test data ship in the app APK. */
@Manual
class BackupTransportFixtureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val paths get() = AppPaths(context.filesDir, context.cacheDir)
    private val choices get() = BackupChoicesStore(File(context.noBackupFilesDir, "choices.json"), Dispatchers.IO)

    @Test fun ownedEmulatorFixture() = runBlocking<Unit>(Dispatchers.IO) {
        assertEquals("true", arguments.getString("ownedBackupEmulator"))
        assertEquals("io.codemyriad.cassini.talk.debug", context.packageName)
        assertTrue("Never seed a physical device", Build.HARDWARE in setOf("ranchu", "goldfish"))
        when (val stage = arguments.getString("backupStage")) {
            "seed" -> seed()
            "choices" -> {
                arguments.getString("cloud")?.let { choices.setCloud(it.toBooleanStrict()) }
                arguments.getString("transfer")?.let { choices.setTransfer(it.toBooleanStrict()) }
            }
            "verify" -> verify()
            "markers" -> markers()
            "oversized" -> {
                // Synthetic bytes only, beyond LocalTransport's 25 MiB quota even in D2D mode.
                RandomAccessFile(File(paths.note(ID), "source.opus"), "rw").use { it.setLength(26L * 1024 * 1024) }
            }
            "verifyQuota" -> {
                assertTrue("The real refusal must be visible in Settings", choices.choices.first().quotaExceeded)
                val document = CassiniDocument.read(File(paths.note(ID), "document.opus"))
                assertEquals(DocumentState.OK, document.documentState)
            }
            else -> error("Unknown explicit backup fixture stage: $stage")
        }
    }

    private suspend fun seed() {
        assertFalse("Requires a fresh synthetic install", paths.database.exists())
        assertFalse(paths.notes.exists())
        assertFalse(paths.people.exists())
        Files.createDirectories(paths.note(ID).toPath())
        File(paths.note(ID), "document.opus").writeBytes(documentBytes())
        withDatabase { database ->
            database.notes().insert(
                NoteEntity(
                    ID,
                    "Synthetic backup note",
                    createdAt = START.toEpochMilli(),
                    addedAt = START.toEpochMilli(),
                    origin = Origin.IMPORTED_DOCUMENT,
                    audioFile = "document.opus",
                    audioKind = AudioKind.DOCUMENT,
                    updatedAt = START.toEpochMilli(),
                ),
            )
            documents(database).refreshIfStale(ID)
            database.jobs().insert(
                JobEntity(ID, 1, JobKind.TRANSCRIBE_AGAIN, JobState.QUEUED, enqueuedAt = 1, updatedAt = 1),
            )
        }
        Files.createDirectories(paths.voices.toPath())
        val voice = Voice("ada", "Ada", "synthetic", floatArrayOf(1f, 0f, 0f), 1, 4.0, 1, 1)
        paths.people.writeText(VoiceCodec.encode(listOf(voice)))
        markers()
        assertFalse(choices.choices.first().cloud)
        assertTrue(choices.choices.first().transfer)
    }

    private fun documentBytes(): ByteArray {
        val words = Transcript(listOf(Word("S1", 0, 20, "Welcome")), "en")
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0, 0, -128, -69, 0, 0, 0, 0, 0)
        val tags = CassiniDocument.tagsPacket(emptyList())
        val silence = OggOpus.mux(OggOpus.Stream(head, tags, listOf(byteArrayOf(-8, -1, -2)), 960, true), tags)
        return CassiniDocument.create(
            silence,
            words,
            "Synthetic backup note",
            JSONObject(),
            speakerLabels = mapOf("S1" to "Ada"),
            recordedAtUtc = START,
            createdAtUtc = START.plusSeconds(540),
        )
    }

    private fun markers() {
        assertEquals("Synthetic backup note", CassiniDocument.read(File(paths.note(ID), "document.opus")).title)
        Files.createDirectories(File(paths.root, "models").toPath())
        File(paths.root, "models/excluded-fixture.bin").writeText("Synthetic marker; never model weights")
        Files.createDirectories(paths.work(ID).toPath())
        File(paths.work(ID), "checkpoint.json").writeText("Synthetic unfinished work; must not travel")
    }

    private suspend fun verify() {
        assertFalse("Downloaded models must not travel", File(paths.root, "models").exists())
        assertFalse("Unfinished inference work must not travel", paths.work(ID).exists())
        assertFalse("An old device's cloud opt-in must not travel", choices.choices.first().cloud)
        assertTrue(choices.choices.first().transfer)
        withDatabase { database ->
            val row = checkNotNull(database.notes().note(ID))
            assertEquals("Synthetic backup note", row.title)
            assertEquals(DocumentState.OK, row.documentState)
            assertEquals(START, row.recordingTime()?.instant)
            assertNull(database.jobs().job(ID))
            val variant = database.cache().variants(ID).single()
            assertEquals(1, variant.wordCount)
            assertEquals("Ada", database.cache().speakers(ID, variant.variantId).single().label)
        }
        val saved = VoiceCodec.decode(paths.people.readText())
        val match = VoiceMatcher.match(
            mapOf("speaker" to floatArrayOf(1f, 0f, 0f)),
            saved,
            "synthetic",
            Thresholds(0.9f, 0.8f, 0.1f),
        )
        assertEquals("ada", match.getValue("speaker").voiceId)
        assertEquals(DocumentState.OK, CassiniDocument.read(File(paths.note(ID), "document.opus")).documentState)
    }

    private fun documents(database: CassiniDatabase) =
        DocumentRepository(database, paths, CassiniDispatchers(), NoteLocks(), context.contentResolver)

    private suspend fun withDatabase(block: suspend (CassiniDatabase) -> Unit) {
        val opened = CassiniDatabaseOpener(context, paths).open()
        try {
            assertNull(opened.setAside)
            block(opened.database)
        } finally {
            opened.database.close()
        }
    }

    private companion object {
        const val ID = "backup-fixture-note"
        val START: Instant = Instant.parse("2026-11-11T12:00:00Z")
    }
}
