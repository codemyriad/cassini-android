package io.codemyriad.cassini.transfer

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.Failure
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
import io.codemyriad.cassini.data.db.JobEntity
import io.codemyriad.cassini.data.notes.ImportSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Review regressions use real repositories and controlled provider streams, with event gates before cancellation. */
@RunWith(RobolectricTestRunner::class)
class BulkTransferReviewTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val paths by lazy { AppPaths(folder.newFolder(), folder.newFolder(), folder.newFolder()) }
    private val graph by lazy { AppGraph(context, AppGraph.Config(paths = paths, network = { false })) }
    private val fixtures = File(checkNotNull(System.getProperty("cassini.fixtures")), "audio")

    @After fun close() { graph.close() }

    @Test fun cleanExternalDocumentsKeepTheirBytesAndReimportAsDuplicates() = runBlocking {
        for ((index, titleTag) in listOf(null, "Original source title").withIndex()) {
            val source = document("Monday $index", titleTag)
            val original = source.readBytes()
            graph.transfers.import(listOf(source.toUri()))
            assertEquals(1, finished().summary?.imported)
            val choice = graph.transfers.choices().single { it.title == "Monday $index" }
            val dao = graph.awaitCatalogue().database.notes()
            assertFalse(checkNotNull(dao.note(choice.id)).titleDirty)
            val stored = File(paths.note(choice.id), "document.opus")
            val destination = folder.newFile("portable-$index.zip")
            graph.transfers.export(listOf(choice.id), destination.toUri())
            assertEquals(1, finished().exported)
            assertArrayEquals(original, stored.readBytes())
            ZipFile(destination).use { zip ->
                assertArrayEquals(original, zip.getInputStream(zip.entries().toList().single()).readBytes())
            }
            graph.transfers.import(listOf(destination.toUri()))
            assertEquals(ImportSummary(duplicates = 1), finished().summary)
            assertNoStaging()
        }
    }

    @Test fun onlyDirtyOkDocumentsAreRetitledForExport() = runBlocking {
        val source = document("Before", null)
        val original = source.readBytes()
        graph.transfers.import(listOf(source.toUri()))
        assertEquals(1, finished().summary?.imported)
        val dao = graph.awaitCatalogue().database.notes()
        val row = dao.all().single()
        val stored = File(paths.note(row.id), row.audioFile)
        dao.update(row.copy(title = "Quarantined", titleDirty = true, documentState = DocumentState.INVALID_METADATA))
        val quarantined = folder.newFile("quarantined.zip")
        graph.transfers.export(listOf(row.id), quarantined.toUri())
        assertEquals(1, finished().exported)
        assertArrayEquals(original, stored.readBytes())
        dao.update(row.copy(title = "After", titleDirty = true))
        val edited = folder.newFile("edited.zip")
        graph.transfers.export(listOf(row.id), edited.toUri())
        assertEquals(1, finished().exported)
        assertEquals("After", CassiniDocument.read(stored).title)
        assertFalse(checkNotNull(dao.note(row.id)).titleDirty)
        ZipFile(edited).use { zip ->
            assertArrayEquals(stored.readBytes(), zip.getInputStream(zip.entries().toList().single()).readBytes())
        }
    }

    @Test fun exportOpensTheDestinationFirstAndKeepsOnlyOneSnapshot() = runBlocking {
        val first = File(fixtures, "fleurs-it-short.wav")
        val second = folder.newFile("Second.wav").apply {
            val changed = first.readBytes()
            changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
            writeBytes(changed)
        }
        graph.transfers.import(listOf(first.toUri(), second.toUri()))
        assertEquals(2, finished().summary?.imported)
        val choices = graph.transfers.choices()
        val bytes = ByteArrayOutputStream()
        var peak = 0
        var stagedWhenOpened = -1
        var sawStagedZip = false
        val destination = "content://bulk-review/stream.zip".toUri()
        shadowOf(context.contentResolver).registerOutputStreamSupplier(destination) {
            stagedWhenOpened = stagedFiles().size
            object : OutputStream() {
                override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    val files = stagedFiles()
                    sawStagedZip = sawStagedZip || files.any { it.name == "notes.zip" }
                    peak = maxOf(peak, files.size)
                    bytes.write(buffer, offset, length)
                }
            }
        }
        graph.transfers.export(choices.map { it.id }, destination)
        assertEquals(2, finished().exported)
        assertEquals("Destination opens before staging", 0, stagedWhenOpened)
        assertEquals("At most one snapshot", 1, peak)
        assertFalse(sawStagedZip)
        val archive = folder.newFile("streamed.zip").apply { writeBytes(bytes.toByteArray()) }
        ZipFile(archive).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(2, entries.size)
            entries.forEach { assertEquals(ZipEntry.STORED, it.method) }
            val exported = entries.associate { it.name to zip.getInputStream(it).readBytes() }
            assertArrayEquals(first.readBytes(), exported.getValue("fleurs-it-short.wav"))
            assertArrayEquals(second.readBytes(), exported.getValue("Second.wav"))
        }
        assertNoStaging()
    }

    @Test fun cancellationDuringProviderReadsRejectsEvenACompleteAudioPrefix() = runBlocking {
        val payload = File(fixtures, "fleurs-it-short.wav").readBytes()
        for (throws in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val streams = ConcurrentLinkedQueue<BlockedInput>()
            val uri = "content://bulk-review/cancel-$throws.wav".toUri()
            shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
                BlockedInput(payload, throws, entered).also(streams::add)
            }
            try {
                graph.transfers.import(listOf(uri))
                withTimeout(20_000) { entered.await() }
                graph.transfers.cancel()
                val result = finished()
                assertTrue(result.cancelled)
                assertFalse(result.failed)
                assertTrue("Blocked read woke on close", streams.any { it.releasedByClose.get() })
                assertTrue(graph.awaitCatalogue().database.notes().all().isEmpty())
                assertNull(graph.notices.imports.state.value.failure)
                assertNoStaging()
            } finally {
                graph.transfers.cancel()
                streams.forEach { it.close() }
            }
        }
    }

    @Test fun cancellationDuringDestinationWriteDismissesWithoutAnError() = runBlocking {
        graph.transfers.import(listOf(File(fixtures, "fleurs-it-short.wav").toUri()))
        assertEquals(1, finished().summary?.imported)
        val entered = CompletableDeferred<Unit>()
        val closed = CountDownLatch(1)
        val releasedByClose = AtomicBoolean()
        val destination = "content://bulk-review/cancel.zip".toUri()
        val output = object : OutputStream() {
            override fun write(value: Int) {
                entered.complete(Unit)
                val notified = closed.await(10, TimeUnit.SECONDS)
                releasedByClose.set(notified)
                check(notified) { "Writer was not closed" }
                throw IOException("Provider closed")
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { write(0) }
            override fun close() { closed.countDown() }
        }
        shadowOf(context.contentResolver).registerOutputStreamSupplier(destination) { output }
        try {
            graph.transfers.export(graph.transfers.choices().map { it.id }, destination)
            withTimeout(20_000) { entered.await() }
            graph.transfers.cancel()
            assertEquals(BulkTransfers.TransferState(), finished())
            assertTrue("Blocked write woke on close", releasedByClose.get())
            assertNoStaging()
        } finally {
            output.close()
        }
    }

    @Test fun choicesExcludeUnplayableMissingAndActiveAudio() = runBlocking {
        graph.transfers.import(listOf(File(fixtures, "fleurs-it-short.wav").toUri()))
        assertEquals(1, finished().summary?.imported)
        val dao = graph.awaitCatalogue().database.notes()
        val original = dao.all().single()
        dao.update(original.copy(audioKind = AudioKind.UNPLAYABLE))
        dao.insert(original.copy(id = UUID.randomUUID().toString(), sourceSha256 = null))
        val active = original.copy(
            id = UUID.randomUUID().toString(),
            sourceSha256 = null,
            audioKind = AudioKind.LIVE_CAPTURE,
        )
        paths.note(active.id).mkdirs()
        File(paths.note(active.id), active.audioFile).writeBytes(File(fixtures, "fleurs-it-short.wav").readBytes())
        dao.insert(active)
        assertTrue(graph.transfers.choices().isEmpty())
        dao.update(original)
        assertEquals(listOf(original.id), graph.transfers.choices().map { it.id })
    }

    @Test fun queuedAndFailedTranscriptionExportsKeepTheStoredTranscript() = runBlocking {
        val source = document("Stored meeting", "Stored meeting")
        graph.transfers.import(listOf(source.toUri()))
        assertEquals(1, finished().summary?.imported)
        val database = graph.awaitCatalogue().database
        val row = database.notes().all().single()
        for (state in listOf(JobState.QUEUED, JobState.FAILED)) {
            database.jobs().insert(JobEntity(row.id, 1, JobKind.TRANSCRIBE_AGAIN, state, enqueuedAt = 1, updatedAt = 1))
            database.notes().update(row.copy(title = "Pending title", titleDirty = true))
            val output = folder.newFile("$state.zip")
            graph.transfers.export(graph.transfers.choices().map { it.id }, output.toUri())
            assertEquals(1, finished().exported)
            ZipFile(output).use { zip ->
                val bytes = zip.getInputStream(zip.entries().toList().single()).readBytes()
                assertArrayEquals(source.readBytes(), bytes)
                val exported = folder.newFile().apply { writeBytes(bytes) }
                assertEquals("Hello", CassiniDocument.read(exported).selected(null)?.transcript?.words?.single()?.text)
            }
            database.jobs().delete(row.id)
        }
    }

    @Test fun exportAllOmitsCompressingAndRecoveredNotesWithoutAudio() = runBlocking {
        val source = File(fixtures, "fleurs-it-short.wav")
        graph.transfers.import(listOf(source.toUri()))
        assertEquals(1, finished().summary?.imported)
        val dao = graph.awaitCatalogue().database.notes()
        val row = dao.all().single()
        // Encoding a plain source writes only work/audio.opus.part; the original source remains exportable.
        paths.work(row.id).mkdirs()
        File(paths.work(row.id), "audio.opus.part").writeText("Incomplete encoder output")
        graph.awaitCatalogue().database.jobs().insert(
            JobEntity(row.id, 1, JobKind.TRANSCRIBE, JobState.RUNNING, enqueuedAt = 1, updatedAt = 1),
        )
        val active = row.copy(
            id = UUID.randomUUID().toString(),
            sourceSha256 = null,
            audioKind = AudioKind.LIVE_CAPTURE,
        )
        paths.note(active.id).mkdirs()
        source.copyTo(File(paths.note(active.id), active.audioFile))
        dao.insert(active)
        dao.insert(row.copy(id = UUID.randomUUID().toString(), sourceSha256 = null, origin = Origin.RECOVERED))
        val choices = graph.transfers.choices()
        assertEquals(listOf(row.id), choices.map { it.id })
        val output = folder.newFile("all-available.zip")
        graph.transfers.export(choices.map { it.id }, output.toUri())
        assertEquals(1, finished().exported)
        ZipFile(output).use { zip ->
            assertArrayEquals(source.readBytes(), zip.getInputStream(zip.entries().toList().single()).readBytes())
        }
        assertNoStaging()
    }

    @Test fun aNewBatchClearsThePriorSingleFileImportErrorButAnEmptyPickDoesNot() = runBlocking {
        graph.notices.imports.refuse(Failure.OPEN)
        graph.transfers.import(emptyList())
        assertNotNull(graph.notices.imports.state.value.failure)
        graph.transfers.import(listOf(File(fixtures, "fleurs-it-short.wav").toUri()))
        assertNull(graph.notices.imports.state.value.failure)
        assertEquals(1, finished().summary?.imported)
    }

    private fun document(title: String, titleTag: String?): File {
        val source = folder.newFile().apply {
            writeBytes(
                CassiniDocument.create(
                    File(fixtures, "fleurs-it-short.opus").readBytes(),
                    Transcript(listOf(Word("S1", 0, 100, "Hello")), "en"),
                    title,
                    JSONObject(),
                ),
            )
        }
        val comments = CassiniDocument.read(source).comments.filterNot { it.startsWith("TITLE=", ignoreCase = true) }
        val tags = CassiniDocument.tagsPacket(comments + listOfNotNull(titleTag?.let { "TITLE=$it" }))
        return folder.newFile("$title.opus").also { OggOpus.rewrite(source, tags, it) }
    }

    private suspend fun finished(): BulkTransfers.TransferState = withTimeout(20_000) {
        graph.transfers.state.first { !it.running }
    }

    private fun stagedFiles(): List<File> =
        File(paths.cache, "bulk-transfers").walkTopDown().filter { it.isFile }.toList()
    private fun assertNoStaging() { assertTrue(stagedFiles().isEmpty()) }

    private class BlockedInput(
        private val payload: ByteArray,
        private val throws: Boolean,
        private val entered: CompletableDeferred<Unit>,
    ) : InputStream() {
        private var position = 0
        private val closed = CountDownLatch(1)
        val releasedByClose = AtomicBoolean()
        override fun read(): Int = error("Buffered reads only")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position < payload.size) {
                val count = minOf(length, payload.size - position)
                payload.copyInto(buffer, offset, position, position + count)
                position += count
                return count
            }
            entered.complete(Unit)
            val notified = closed.await(10, TimeUnit.SECONDS)
            releasedByClose.set(notified)
            check(notified) { "Reader was not closed" }
            if (throws) throw IOException("Provider closed")
            return -1
        }
        override fun close() { closed.countDown() }
    }
}
