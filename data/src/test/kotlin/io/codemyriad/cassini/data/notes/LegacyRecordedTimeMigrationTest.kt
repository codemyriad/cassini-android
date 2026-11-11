package io.codemyriad.cassini.data.notes

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.data.AppPaths
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

/** Actual v1 producer/rebuilder artifacts, not v2 rows pretending to be legacy data. */
@RunWith(RobolectricTestRunner::class)
class LegacyRecordedTimeMigrationTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness
    private val fixtures = File(checkNotNull(System.getProperty("cassini.fixtures")), "legacy-recording-time")
    private val expected get() = JSONObject(File(fixtures, "expected.json").readText())
    private val id get() = expected.getString("id")
    private val start get() = Instant.parse(expected.getString("startUtc"))

    @After fun close() {
        if (::h.isInitialized) h.close()
    }

    private fun migrate(database: String) {
        h = NotesHarness(folder) { paths: AppPaths ->
            File(fixtures, database).copyTo(paths.database)
            paths.note(id).mkdirs()
            File(fixtures, "document.opus").copyTo(File(paths.note(id), "document.opus"))
        }
        h.reader.beforeRead = {
            assertFalse("metadata reads must be off Main", Thread.currentThread() === Looper.getMainLooper().thread)
        }
    }

    @Test fun realV1RecordSealMigrateExportAndImportPreservesTheStart() = runTest {
        migrate("own-v1.db")
        assertNull("candidate is withheld until verified", h.notes.library().first().single().recordingTime())
        assertEquals(start.toEpochMilli(), h.row(id).legacyCaptureCandidate)
        assertEquals(listOf(id), h.documents.refreshInvalidatedMetadata())
        val row = h.notes.library().first().single()
        assertEquals(start, row.recordingTime()?.instant)
        assertEquals(start.toEpochMilli(), h.row(id).captureStartedAt)
        assertNull(h.row(id).legacyCaptureCandidate)
        assertEquals(1, h.reader.reads.get())
        assertTrue(h.documents.refreshInvalidatedMetadata().isEmpty())
        assertEquals(1, h.reader.reads.get())

        val destination = folder.newFile("export.opus")
        assertTrue(h.documents.exportCopy(id, Uri.fromFile(destination)) is ExportOutcome.Saved)
        val exported = CassiniDocument.read(destination)
        assertEquals(start.toString(), exported.manifest!!.getJSONObject("meeting").getString("recordedAtUtc"))
        val importer = ImportRepository(
            h.db,
            h.paths,
            h.dispatchers,
            h.locks,
            h.documents,
            ImportSource.of(ApplicationProvider.getApplicationContext<Context>().contentResolver),
            probe = FakeProbe(),
        )
        val imported = importer.import(Uri.fromFile(destination))
        assertTrue(imported.toString(), imported is ImportOutcome.Created)
        val copy = h.row((imported as ImportOutcome.Created).noteId)
        assertEquals(start, copy.recordingTime()?.instant)
    }

    @Test fun realV1RebuildPackagingStampIsNeverPromotedToCaptureTime() = runTest {
        migrate("rebuilt-v1.db")
        assertEquals(listOf(id), h.documents.refreshInvalidatedMetadata())
        assertNull(h.notes.library().first().single().recordingTime())
        assertNull(h.row(id).captureStartedAt)
        assertNull(h.row(id).legacyCaptureCandidate)
        assertEquals(Instant.parse(expected.getString("sealedUtc")).toEpochMilli(), h.row(id).createdAt)
    }
}
