package io.codemyriad.cassini.data.notes

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class ArchiveConcurrentImportTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness
    private lateinit var importer: ImportRepository
    private val ids = listOf("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002")

    @Before fun setUp() {
        h = NotesHarness(folder)
        val source = ImportSource.of(ApplicationProvider.getApplicationContext<Context>().contentResolver)
        assertNotSame("Gated readers must hold different stripes", h.locks.lockFor(ids[0]), h.locks.lockFor(ids[1]))
        val next = AtomicInteger()
        importer = ImportRepository(
            h.db, h.paths, h.dispatchers, h.locks, h.documents, source,
            newId = { ids[next.getAndIncrement()] },
        )
    }

    @After fun close() { h.close() }

    @Test fun bulkAndSingleDocumentImportsMeetAtTheCommitAndCreateOnlyOneNote() = runBlocking {
        val bytes = documentBytes(transcript("Stored words"), "Meeting")
        val input = folder.newFile().apply { writeBytes(bytes) }
        val arrived = CompletableDeferred<Unit>()
        val readers = AtomicInteger()
        val release = CountDownLatch(1)
        // Both imports have already checked the digest and classified the document, but neither has committed.
        h.reader.beforeRead = { file ->
            assertTrue("Importer must use the controlled IDs", file.parentFile.name in ids)
            if (readers.incrementAndGet() == 2) arrived.complete(Unit)
            check(release.await(20, TimeUnit.SECONDS)) { "Document readers were not released" }
        }
        try {
            val bulk = async(Dispatchers.IO) {
                NoteArchives().read(archive(bytes).inputStream(), folder.newFolder()) { file, name ->
                    importer.import(Uri.fromFile(file), name)
                }
            }
            val single = async(Dispatchers.IO) { importer.import(Uri.fromFile(input)) }
            withTimeout(20_000) { arrived.await() }
            release.countDown()
            val outcome = single.await()
            val summary = bulk.await()
            assertEquals(1, summary.imported + if (outcome is ImportOutcome.Created) 1 else 0)
            assertEquals(1, summary.duplicates + if (outcome is ImportOutcome.Existing) 1 else 0)
            val row = h.db.notes().all().single()
            assertEquals(2, readers.get())
            assertTrue(row.id in ids)
            assertEquals(listOf(row.id), h.paths.notes.list().orEmpty().toList())
            assertEquals("Stored words", h.db.notes().text(row.id)?.body)
        } finally {
            release.countDown()
        }
    }

    @Test fun lateBulkRefusalCannotRollBackAConcurrentSingleImportOrItsEdits() = runBlocking {
        val bytes = documentBytes(transcript("Stored words"), "Meeting")
        val input = folder.newFile().apply { writeBytes(bytes) }
        val bomb = archive(bytes, "x".repeat(200_000))
        bomb.putInt(bomb.recordOffsets(0x02014b50).last() + 24, 8)
        val spooled = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val stream = object : ByteArrayInputStream(bomb) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val n = super.read(buffer, offset, length)
                if (n < 0) {
                    spooled.complete(Unit)
                    check(release.await(20, TimeUnit.SECONDS)) { "Archive spool was not released" }
                }
                return n
            }
        }
        try {
            val bulk = async(Dispatchers.IO) {
                try {
                    NoteArchives().read(stream, folder.newFolder()) { file, name ->
                        importer.import(Uri.fromFile(file), name)
                    }
                    error("Bomb accepted")
                } catch (error: ArchiveRejected) {
                    assertEquals(ArchiveFailure.TOO_LARGE, error.reason)
                }
            }
            withTimeout(20_000) { spooled.await() }
            val outcome = importer.import(Uri.fromFile(input))
            assertTrue(outcome is ImportOutcome.Created)
            val row = h.db.notes().all().single()
            h.db.notes().update(row.copy(title = "User edit", titleDirty = true))
            release.countDown()
            bulk.await()
            assertEquals("User edit", h.db.notes().all().single().title)
            assertEquals("Stored words", h.db.notes().text(row.id)?.body)
            assertTrue(h.document(row.id).isFile)
            assertEquals(ImportOutcome.Existing(row.id), importer.import(Uri.fromFile(input)))
        } finally {
            release.countDown()
        }
    }

    private fun archive(document: ByteArray, ignored: String? = null): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("Meeting.opus"))
            zip.write(document)
            zip.closeEntry()
            if (ignored != null) {
                zip.putNextEntry(ZipEntry("ignored.txt"))
                zip.write(ignored.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
