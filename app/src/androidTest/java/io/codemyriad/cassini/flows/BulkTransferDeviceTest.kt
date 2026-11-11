package io.codemyriad.cassini.flows

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import io.codemyriad.cassini.MainActivity
import io.codemyriad.cassini.R
import io.codemyriad.cassini.background.Background
import io.codemyriad.cassini.data.notes.ImportSummary
import io.codemyriad.cassini.flows.Flows.awaitUi
import io.codemyriad.cassini.test.CiSmoke
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Real Android URI IO, normal digest deduplication, ZIP output and Library summary/selection controls. */
@CiSmoke
class BulkTransferDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() = runBlocking {
        Background.grant()
        Flows.graph.ready.await()
        Background.reset()
    }

    @After fun tearDown() = runBlocking {
        Flows.graph.transfers.cancel()
        withTimeout(20_000) { Flows.graph.transfers.state.first { !it.running } }
        Flows.graph.transfers.dismiss()
        Background.reset()
    }

    @Test fun anEmptyLibraryCannotOpenTheZipDestinationPicker() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val label = context.getString(R.string.export_zip_all)
        rule.awaitUi(20_000) {
            rule.onNodeWithText(label).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
        rule.onNodeWithText(label).assertIsNotEnabled()
    }

    @Test fun multipleImportsShowTheirSummaryAndExportOriginalAudio() {
        val graph = Flows.graph
        val audio = Background.assetCopy("fleurs-it-short.wav")
        val archive = File(graph.paths.cache, "bulk-device.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for (name in listOf("Meeting.wav", "ignored.txt")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(if (name.endsWith(".wav")) audio.readBytes() else "Ignored".toByteArray())
                zip.closeEntry()
            }
        }
        graph.transfers.import(listOf(archive.toUri(), audio.toUri()))
        val imported = runBlocking { withTimeout(20_000) { graph.transfers.state.first { !it.running } } }
        assertEquals(ImportSummary(imported = 1, duplicates = 1, unsupported = 1), imported.summary)
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        for (resource in listOf(
            R.plurals.import_summary_imported,
            R.plurals.import_summary_duplicates,
            R.plurals.import_summary_unsupported,
        )) {
            rule.onNodeWithText(context.resources.getQuantityString(resource, 1, 1)).assertIsDisplayed()
        }
        rule.onNodeWithText(context.getString(R.string.common_done)).performClick()
        rule.onNodeWithText(context.getString(R.string.export_zip_all)).performClick()
        rule.onNodeWithText(context.getString(R.string.common_cancel)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.common_cancel)).performClick()
        val choices = runBlocking { graph.transfers.choices() }
        val exported = File(graph.paths.cache, "bulk-device-output.zip")
        graph.transfers.export(choices.map { it.id }, exported.toUri())
        val result = runBlocking { withTimeout(20_000) { graph.transfers.state.first { !it.running } } }
        assertEquals(1, result.exported)
        ZipFile(exported).use { zip ->
            val entry = zip.entries().toList().single()
            assertEquals("Meeting.wav", entry.name)
            assertEquals(ZipEntry.STORED, entry.method)
            assertArrayEquals(audio.readBytes(), zip.getInputStream(entry).readBytes())
        }
        archive.delete()
        exported.delete()
    }
}
