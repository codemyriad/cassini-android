package io.codemyriad.cassini.transfer

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.R
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.notes.ImportSummary
import io.codemyriad.cassini.library.LibraryViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
class BulkTransfersTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val paths by lazy { AppPaths(folder.newFolder(), folder.newFolder(), folder.newFolder()) }
    private var io: CoroutineDispatcher = Dispatchers.IO
    private val graph by lazy {
        val config = AppGraph.Config(paths = paths, network = { false }, dispatchers = CassiniDispatchers(io = io))
        AppGraph(context, config)
    }
    private val fixtures = File(checkNotNull(System.getProperty("cassini.fixtures")), "audio")

    @After fun close() { graph.close() }

    @Test fun severalFilesAndAnArchiveUseRealDigestDeduplication() = runBlocking {
        val audio = File(fixtures, "fleurs-it-short.wav")
        val zip = folder.newFile("notes.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for (name in listOf("nested/Monday.wav", "copy.wav", "ignored.txt")) {
                out.putNextEntry(ZipEntry(name))
                out.write(if (name.endsWith(".wav")) audio.readBytes() else "Ignored".toByteArray())
                out.closeEntry()
            }
        }
        val contract = BulkImportContract()
        val picker = contract.createIntent(context, LibraryViewModel.IMPORT_TYPES)
        assertTrue(picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        val label = context.getString(R.string.import_filter_cassini_and_zip)
        assertEquals(label, picker.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue("application/zip" in LibraryViewModel.IMPORT_TYPES)
        val result = Intent().apply {
            clipData = ClipData.newRawUri("Audio", audio.toUri()).apply { addItem(ClipData.Item(zip.toUri())) }
        }
        val picked = contract.parseResult(Activity.RESULT_OK, result)
        assertEquals(listOf(audio.toUri(), zip.toUri()), picked)
        graph.transfers.import(picked)
        val state = finished()
        assertEquals(ImportSummary(imported = 1, duplicates = 2, unsupported = 1), state.summary)
        assertEquals(1, graph.awaitCatalogue().notes.library().first().size)
        assertNoStaging()
    }

    @Test fun selectedExportKeepsTheCompleteDocumentAndOriginalAudioFormats() = runBlocking {
        val document = folder.newFile("Monday.opus")
        document.writeBytes(
            CassiniDocument.create(
                File(fixtures, "fleurs-it-short.opus").readBytes(),
                Transcript(listOf(Word("S1", 0, 100, "Hello")), "en"),
                "Monday",
                JSONObject(),
            ),
        )
        val wav = File(fixtures, "fleurs-it-short.wav")
        graph.transfers.import(listOf(document.toUri(), wav.toUri()))
        assertEquals(2, finished().summary?.imported)
        val choices = graph.transfers.choices()
        assertEquals(2, choices.size)
        val selected = choices.single { it.title == "Monday" }
        val output = folder.newFile("selected.zip")
        graph.transfers.export(listOf(selected.id), output.toUri())
        assertEquals(1, finished().exported)
        ZipFile(output).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(listOf("Monday.opus"), entries.map { it.name })
            assertEquals(ZipEntry.STORED, entries.single().method)
            assertArrayEquals(document.readBytes(), zip.getInputStream(entries.single()).readBytes())
        }
        val all = folder.newFile("all.zip")
        graph.transfers.export(choices.map { it.id }, all.toUri())
        assertEquals(2, finished().exported)
        ZipFile(all).use { zip ->
            val audio = zip.entries().toList().single { it.name.endsWith(".wav") }
            assertArrayEquals(wav.readBytes(), zip.getInputStream(audio).readBytes())
        }
        assertNoStaging()
    }

    @Test fun cancellationBeforeIoStartsStillPublishesTheSharedCancelledState() = runBlocking {
        val gate = GateDispatcher()
        io = gate
        withTimeout(20_000) { graph.ready.await() }
        gate.pause()
        try {
            graph.transfers.import(listOf(File(fixtures, "fleurs-it-short.wav").toUri()))
            assertTrue(graph.transfers.state.value.running)
            graph.transfers.cancel()
        } finally {
            gate.resume()
        }
        val cancelled = finished()
        assertTrue(cancelled.cancelled)
        assertEquals(0, cancelled.summary?.imported ?: 0)
        assertNoStaging()
    }

    private class GateDispatcher : CoroutineDispatcher() {
        private var paused = false
        private val pending = ArrayList<Pair<CoroutineContext, Runnable>>()

        @Synchronized fun pause() { paused = true }

        @Synchronized override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (paused) pending += context to block else Dispatchers.IO.dispatch(context, block)
        }
        fun resume() {
            val tasks = synchronized(this) {
                paused = false
                pending.toList().also { pending.clear() }
            }
            tasks.forEach { (context, task) -> Dispatchers.IO.dispatch(context, task) }
        }
    }

    private suspend fun finished(): BulkTransfers.TransferState = withTimeout(20_000) {
        graph.transfers.state.first { !it.running }
    }

    private fun assertNoStaging() {
        assertTrue(File(paths.cache, "bulk-transfers").listFiles().orEmpty().isEmpty())
    }
}
