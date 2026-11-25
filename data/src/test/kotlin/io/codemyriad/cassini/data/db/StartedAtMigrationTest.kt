package io.codemyriad.cassini.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.CassiniPayload
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.format.MeetingTime
import io.codemyriad.cassini.core.format.OggOpus
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.DocumentSignature
import io.codemyriad.cassini.data.notes.NoteFiles
import io.codemyriad.cassini.data.notes.NotesHarness
import io.codemyriad.cassini.data.notes.RefreshOutcome
import io.codemyriad.cassini.data.notes.fixtureOpus
import io.codemyriad.cassini.data.notes.recordingTime
import io.codemyriad.cassini.data.notes.transcript
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

/**
 * Schema 5 (D112, D-1164): adds `started_at` and `source_modified_at`, and invalidates cached document signatures so
 * startup refresh recalculates dates under the D112 reader rules without touching authoritative rows or stars.
 */
@RunWith(RobolectricTestRunner::class)
class StartedAtMigrationTest {
    @get:Rule val folder = TemporaryFolder()

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CassiniDatabase::class.java,
    )

    private val words = transcript("migrating legacy document date caches on upgrade")

    private data class LegacyDocSpec(
        val title: String,
        val extensions: JSONObject? = null,
        val recordedAtUtc: Instant? = null,
        val extraMeetingFields: Map<String, String> = emptyMap(),
        val dateTag: String? = null,
    )

    @Test fun addsTwoEmptyColumnsInvalidatesDocumentSignaturesAndKeepsStars() {
        helper.createDatabase("started-at-migration", 4).use { db ->
            val columns = "id,title,created_at,added_at,origin,audio_file,audio_kind,duration_ms," +
                "position_ms,title_dirty,document_warnings,trust_acknowledged,document_signature,updated_at,recorded_at"
            db.execSQL(
                "INSERT INTO notes ($columns) VALUES " +
                    "('plain','Keep plain',1,2,'IMPORTED_AUDIO','source.opus','PLAIN',3,0,0,'[]',0,'plain-sig',5,NULL)," +
                    "('doc','Keep doc',10,20,'IMPORTED_DOCUMENT','document.opus','DOCUMENT',30,0,0,'[]',0,'doc-sig',50," +
                    "'recordedAtUtc=2026-05-01T08:00:00Z')",
            )
            db.execSQL("INSERT INTO note_stars (note_id, starred_at) VALUES ('plain', 9), ('doc', 11)")
        }
        helper.runMigrationsAndValidate("started-at-migration", 5, true, CassiniDatabase.MIGRATION_4_5).use { db ->
            db.query(
                "SELECT id, title, created_at, document_signature, started_at, source_modified_at FROM notes ORDER BY id",
            ).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals("doc", rows.getString(0))
                assertEquals("Keep doc", rows.getString(1))
                assertEquals(10, rows.getLong(2))
                assertTrue("document signatures are invalidated for startup re-derivation", rows.isNull(3))
                assertTrue("no one has set a start", rows.isNull(4))
                assertTrue(rows.isNull(5))

                assertTrue(rows.moveToNext())
                assertEquals("plain", rows.getString(0))
                assertEquals("Keep plain", rows.getString(1))
                assertEquals(1, rows.getLong(2))
                assertEquals("plain rows keep their signature", "plain-sig", rows.getString(3))
                assertTrue(rows.isNull(4))
                assertTrue(rows.isNull(5))
            }
            db.query("SELECT COUNT(*) FROM note_stars").use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(2, rows.getInt(0))
            }
        }
    }

    @Test fun upgradeRecalculatesCachedLegacyDocumentDatesAtStartup() = runTest {
        val legacyPublishUtc = Instant.parse("2026-05-01T08:00:00Z")
        val legacyRecordedUtc = Instant.parse("2026-11-20T09:00:00Z")
        val startedAt = "2026-11-23T14:36:00+01:00"
        val startedMs = Instant.parse("2026-11-23T13:36:00Z").toEpochMilli()

        val importTimeExt = JSONObject().put(
            MeetingTime.UX_NAMESPACE,
            JSONObject().put("namePrefixSource", "import-time"),
        )
        val libv2File = writeLegacyDocument(
            folder.newFile("libv2.opus"),
            LegacyDocSpec(
                title = "Legacy import-time note",
                extensions = importTimeExt,
                extraMeetingFields = mapOf(MeetingTime.STARTED to "2026-05-01T10:00:00+02:00"),
                dateTag = legacyPublishUtc.toString(),
            ),
        )
        val overrideFile = writeLegacyDocument(
            folder.newFile("override.opus"),
            LegacyDocSpec(
                title = "Started beats recordedAtUtc",
                recordedAtUtc = legacyRecordedUtc,
                extraMeetingFields = mapOf(MeetingTime.STARTED to startedAt),
            ),
        )

        val dbName = "started-at-upgrade-refresh"
        val dbFile = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(dbName)
        dbFile.delete()
        val harness = NotesHarness(folder) { paths ->
            seedLegacyV4(paths, dbName, dbFile, libv2File, overrideFile, legacyPublishUtc, legacyRecordedUtc)
        }
        try {
            assertEquals(listOf("libv2", "override"), harness.documents.refreshInvalidatedMetadata())
            val rows = harness.notes.library().first().associateBy { it.id }
            assertNull("legacy import-time publish stamp is dropped", rows.getValue("libv2").recordingTime())
            assertEquals(MeetingTime(MeetingTime.STARTED, startedAt), rows.getValue("override").recordingTime())
            assertEquals(startedMs, rows.getValue("override").createdAt)
            assertTrue(harness.documents.refreshInvalidatedMetadata().isEmpty())
            assertEquals(RefreshOutcome.Fresh, harness.documents.refreshIfStale("override"))
        } finally {
            harness.close()
            dbFile.delete()
        }
    }

    @Suppress("LongParameterList")
    private fun seedLegacyV4(
        paths: AppPaths,
        dbName: String,
        dbFile: File,
        libv2File: File,
        overrideFile: File,
        legacyPublishUtc: Instant,
        legacyRecordedUtc: Instant,
    ) {
        val libv2Dest = File(paths.note("libv2").apply { mkdirs() }, NoteFiles.DOCUMENT)
        libv2File.copyTo(libv2Dest, overwrite = true)
        val overrideDest = File(paths.note("override").apply { mkdirs() }, NoteFiles.DOCUMENT)
        overrideFile.copyTo(overrideDest, overwrite = true)
        val libv2Sig = checkNotNull(DocumentSignature.of(libv2Dest))
        val overrideSig = checkNotNull(DocumentSignature.of(overrideDest))

        helper.createDatabase(dbName, 4).use { db ->
            val columns = "id,title,created_at,added_at,origin,audio_file,audio_kind,duration_ms," +
                "position_ms,title_dirty,document_state,document_warnings,trust_acknowledged," +
                "document_signature,updated_at,recorded_at"
            db.execSQL(
                "INSERT INTO notes ($columns) VALUES " +
                    "('libv2','Legacy import-time note',${legacyPublishUtc.toEpochMilli()},1," +
                    "'IMPORTED_DOCUMENT','document.opus','DOCUMENT',3000,0,0,'OK','[]',0," +
                    "'$libv2Sig',5,'date=$legacyPublishUtc')," +
                    "('override','Started beats recordedAtUtc',${legacyRecordedUtc.toEpochMilli()},2," +
                    "'IMPORTED_DOCUMENT','document.opus','DOCUMENT',3000,0,0,'OK','[]',0," +
                    "'$overrideSig',6,'recordedAtUtc=$legacyRecordedUtc')",
            )
        }
        dbFile.copyTo(paths.database, overwrite = true)
    }

    private fun writeLegacyDocument(target: File, spec: LegacyDocSpec): File {
        val base = folder.newFile()
        CassiniDocument.create(
            fixtureOpus,
            words,
            spec.title,
            JSONObject().put("engine", "test"),
            existing = null,
            speakerLabels = emptyMap(),
            target = base,
            extensions = spec.extensions,
            recordedAtUtc = spec.recordedAtUtc,
            createdAtUtc = Instant.parse("2026-05-02T12:00:00Z"),
        )
        val doc = CassiniDocument.read(base)
        val manifest = JSONObject(checkNotNull(doc.manifest).toString())
        val meeting = manifest.getJSONObject("meeting")
        spec.extraMeetingFields.forEach { (key, value) -> meeting.put(key, value) }
        val kept = doc.comments.filterNot {
            val name = it.substringBefore('=').uppercase()
            (name.startsWith("CASSINI_PAYLOAD_") && name != "CASSINI_PAYLOAD_SCHEMA") ||
                (spec.dateTag != null && name == "DATE")
        }
        val manifestTags = CassiniPayload.encode("CASSINI_PAYLOAD_", CassiniPayload.MIME, manifest.toString()).second
        val comments = kept + listOfNotNull(spec.dateTag?.let { "DATE=$it" }) + manifestTags
        OggOpus.rewrite(base, CassiniDocument.tagsPacket(comments), target)
        assertEquals(DocumentState.OK, CassiniDocument.read(target).documentState)
        return target
    }
}
