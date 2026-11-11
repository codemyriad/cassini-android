package io.codemyriad.cassini.data.notes

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.format.OggOpus
import io.codemyriad.cassini.data.AppPaths
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
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

    @Test fun realV1UnverifiedAudioWithholdsItsCandidateUntilTheValidAudioReturns() = runTest {
        migrate("own-v1.db")
        val file = h.document(id)
        val valid = file.readBytes()
        val stream = OggOpus.read(valid)
        file.writeBytes(OggOpus.mux(stream.copy(finalGranule = 0), stream.tags))
        assertEquals(listOf(id), h.documents.refreshInvalidatedMetadata())
        assertEquals(DocumentState.UNVERIFIED, h.row(id).documentState)
        assertNull(h.row(id).captureStartedAt)
        assertNull(h.row(id).captureAudioSha256)
        assertEquals(start.toEpochMilli(), h.row(id).legacyCaptureCandidate)
        assertNull(h.notes.library().first().single().recordingTime())
        val oldMtime = file.lastModified()
        file.writeBytes(valid)
        assertTrue(file.setLastModified(oldMtime + 1))
        h.documents.refreshIfStale(id)
        assertEquals(DocumentState.OK, h.row(id).documentState)
        assertEquals(start.toEpochMilli(), h.row(id).captureStartedAt)
        assertNull(h.row(id).legacyCaptureCandidate)
        assertEquals(start, h.notes.library().first().single().recordingTime()?.instant)
    }

    @Test fun realV1DigestMismatchNeverPromotesTheClockAndExportsTheExactStaleBytes() = runTest {
        migrate("own-v1.db")
        val file = h.document(id)
        val stream = OggOpus.read(file.readBytes())
        val packets = stream.audio.toMutableList()
        packets[0] = packets[0].clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val stale = OggOpus.mux(stream.copy(audio = packets), stream.tags)
        file.writeBytes(stale)
        assertEquals(listOf(id), h.documents.refreshInvalidatedMetadata())
        assertEquals(DocumentState.STALE_AUDIO, h.row(id).documentState)
        assertNull(h.row(id).captureStartedAt)
        assertNull(h.row(id).captureAudioSha256)
        assertNull(h.row(id).legacyCaptureCandidate)
        assertNull(h.notes.library().first().single().recordingTime())
        val copy = folder.newFile("stale-export.opus")
        assertTrue(h.documents.exportCopy(id, Uri.fromFile(copy)) is ExportOutcome.Saved)
        assertArrayEquals(stale, copy.readBytes())
        assertArrayEquals(stale, file.readBytes())
        assertEquals(DocumentState.STALE_AUDIO, CassiniDocument.read(copy).documentState)
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
