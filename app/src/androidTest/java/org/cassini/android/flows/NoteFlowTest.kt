package org.cassini.android.flows

import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.espresso.Espresso
import kotlinx.coroutines.runBlocking
import org.cassini.android.MainActivity
import org.cassini.android.background.Background
import org.cassini.android.flows.Flows.import
import org.cassini.android.flows.Flows.rows
import org.cassini.android.flows.Flows.tapWord
import org.cassini.android.flows.Flows.text
import org.cassini.android.flows.Flows.waitFor
import org.cassini.android.flows.Flows.waitGone
import org.cassini.android.flows.Flows.waitShown
import org.cassini.android.flows.Flows.waitText
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.DocumentState
import org.cassini.core.library.AudioKind
import org.cassini.test.NeedsModels
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import org.cassini.ui.R as UiR

/**
 * The Note screen end to end with the installed models (features.md sections 3, 3.3 and 6): the FLEURS clip is
 * transcribed from its note, the progress card shows, Read & listen shows the words, a word tap seeks and plays,
 * Transcribe again adds version 2 to the Versions sheet, Save a copy of the Cassini document is byte-identical to
 * `notes/<id>/document.opus`, and the TXT, SRT and VTT exports equal the goldens in `assets/goldens/`.
 *
 * The goldens were recorded on emulator-5554 (API 34, x86_64) with Parakeet Ultra, as the models workflow runs the
 * test: when a golden is missing or differs, the test writes the export to `cacheDir/goldens-candidate/` of the app
 * and fails, so it can be read and reviewed before it is committed. On an arm64 phone (Pixel 8) the INT8 kernels
 * give "Catena della Sentinella" for the "catena della sentinella" of the goldens, with the same times; this test
 * then fails on that line. The second line of the TXT export is the note's date, which changes per run: it is
 * compared as `<date> · <duration>`.
 */
@NeedsModels
class NoteFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val graph get() = Flows.graph

    @Before fun setUp() = runBlocking {
        Background.grant()
        graph.ready.await()
        Background.reset()
    }

    @After fun tearDown() = runBlocking { Background.reset() }

    @Test fun transcribeReadSeekVersionsAndExports() {
        val noteId = runBlocking { import(Background.assetCopy("fleurs-it-short.wav")) }
        rule.waitShown(hasContentDescription(TITLE, substring = true))
        rule.onNode(hasContentDescription(TITLE, substring = true)).performClick()

        // Transcribe from the Audio only banner; the progress card shows while the job runs.
        val transcribe = hasText(rule.text(UiR.string.ui_action_transcribe)) and hasClickAction()
        rule.waitShown(transcribe)
        rule.onAllNodes(transcribe).onFirst().performClick()
        rule.waitText(rule.text(UiR.string.ui_action_stop_keep_progress), timeoutMs = Flows.JOB_MS)
        awaitSealed(noteId, variants = 1)

        // Read & listen: verified, with the words; a word tap seeks there and plays.
        rule.waitText(rule.text(UiR.string.ui_trust_verified), timeoutMs = Flows.SCREEN_MS)
        rule.waitShown(hasText(WORD, substring = true), unmerged = true)
        val word = document(noteId).selected(null)!!.transcript!!.words.first { WORD in it.text }
        rule.onNode(hasText(WORD, substring = true), useUnmergedTree = true).tapWord(WORD)
        rule.waitFor("The tap seeks to \"$WORD\" (${word.startMs} ms) and plays") {
            val state = graph.playback.state.value
            state.playing && state.positionMs in (word.startMs - SEEK_SLACK_MS)..(word.startMs + PLAY_SLACK_MS)
        }
        rule.runOnUiThread { graph.playback.pause() }

        // Transcribe again: version 2, and the Versions sheet lists both.
        openActions()
        clickOnce(hasText(rule.text(UiR.string.ui_action_transcribe_again)) and hasClickAction())
        awaitSealed(noteId, variants = 2)
        val chip = rule.text(UiR.string.ui_version_chip, 2, 2)
        rule.waitText(chip, substring = true)
        rule.onNode(hasText(chip, substring = true) and hasClickAction()).performClick()
        rule.waitText(rule.text(UiR.string.ui_versions_title))
        rule.waitText(rule.text(UiR.string.ui_versions_row, 1))
        rule.waitText(rule.text(UiR.string.ui_versions_row, 2))
        Espresso.pressBack()
        rule.waitGone(hasText(rule.text(UiR.string.ui_versions_title)))

        // Save a copy: the document byte for byte.
        val copy = export(rule.text(UiR.string.ui_actions_document), "copy.opus")
        val stored = runBlocking { documentFile(noteId) }
        assertEquals("document.opus", stored.name)
        assertArrayEquals("Save a copy is byte-identical to the stored document", stored.readBytes(), copy.readBytes())

        // The transcript exports equal the goldens.
        val text = export(rule.text(UiR.string.ui_actions_text), "copy.txt").readText()
        val srt = export(rule.text(UiR.string.ui_actions_subtitles), "copy.srt", rule.text(UiR.string.ui_subrip))
        val vtt = export(rule.text(UiR.string.ui_actions_subtitles), "copy.vtt", rule.text(UiR.string.ui_webvtt))
        val results = listOf(
            golden("fleurs-it-short.txt", normalizeText(text)),
            golden("fleurs-it-short.srt", srt.readText()),
            golden("fleurs-it-short.vtt", vtt.readText()),
        )
        assertTrue(results.filterNotNull().joinToString("\n"), results.all { it == null })
    }

    /** Waits until the note's job is gone and its document is verified with [variants] transcripts. */
    private fun awaitSealed(noteId: String, variants: Int) {
        rule.waitFor("The job seals a verified document with $variants versions", Flows.JOB_MS) {
            val row = rows().firstOrNull { it.id == noteId }
            row != null && row.jobState == null && row.audioKind == AudioKind.DOCUMENT &&
                row.documentState == DocumentState.OK && document(noteId).variants.size == variants
        }
    }

    private suspend fun documentFile(noteId: String): File {
        val row = graph.awaitCatalogue().notes.rowBlocking(noteId)!!
        return File(graph.paths.note(noteId), row.audioFile)
    }

    private fun document(noteId: String): CassiniDocument = runBlocking { CassiniDocument.read(documentFile(noteId)) }

    /** Opens the Note actions sheet, unless it is open. */
    private fun openActions() {
        val section = hasText(rule.text(UiR.string.ui_actions_save_copy), ignoreCase = true)
        if (rule.onAllNodes(section).fetchSemanticsNodes().isEmpty()) {
            rule.onNode(hasContentDescription(rule.text(UiR.string.ui_note_share_more))).performClick()
        }
        rule.waitShown(section)
    }

    /**
     * Runs the export row [label] (and [format] in the subtitle chooser) with the picker answered by a file in the
     * scratch cache, and returns that file once the "Saved" snackbar shows.
     */
    private fun export(label: String, name: String, format: String? = null): File {
        val target = File(graph.paths.cache, "exports-test/$name")
        target.parentFile?.mkdirs()
        target.delete()
        val monitor = Flows.createDocumentAnswer(target)
        try {
            openActions()
            // A semantics click: the sheet may still be settling, and a touch then misses the window.
            clickOnce(hasText(label, substring = true) and hasClickAction())
            if (format != null) clickOnce(hasText(format, substring = true) and hasClickAction())
            rule.waitFor("The export writes $name") { target.length() > 0 }
            val saved = rule.text(UiR.string.ui_actions_saved_file, "\u0000").substringBefore("\u0000")
            rule.waitText(saved, substring = true)
        } finally {
            Background.instrumentation.removeMonitor(monitor)
        }
        val section = hasText(rule.text(UiR.string.ui_actions_save_copy), ignoreCase = true)
        if (rule.onAllNodes(section).fetchSemanticsNodes().isNotEmpty()) Espresso.pressBack()
        rule.waitGone(section)
        return target
    }

    /**
     * Waits until one node matches [matcher] (a sheet that is closing still has its rows for a moment), then clicks
     * it through its semantics action: while the sheet settles, a touch can miss its window.
     */
    private fun clickOnce(matcher: SemanticsMatcher) {
        rule.waitUntil(Flows.SCREEN_MS) { rule.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
        rule.onNode(matcher).performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Null when [actual] equals the golden [name]; else what differs, with a candidate file to review. */
    private fun golden(name: String, actual: String): String? {
        val expected = try {
            val assets = Background.instrumentation.context.assets
            assets.open("goldens/$name").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: FileNotFoundException) {
            null
        }
        if (expected == actual) return null
        val candidate = File(Background.context.cacheDir, "goldens-candidate/$name")
        candidate.parentFile?.mkdirs()
        candidate.writeText(actual)
        Log.i(TAG, "Candidate for goldens/$name at $candidate:\n$actual")
        return if (expected == null) "No golden $name; review $candidate" else "$name differs; see $candidate"
    }

    /** The TXT export with its date (line 2, before " · ") replaced by `<date>`. */
    private fun normalizeText(text: String): String {
        val lines = text.split('\n').toMutableList()
        if (lines.size > 1) lines[1] = "<date> · " + lines[1].substringAfterLast(" · ")
        return lines.joinToString("\n")
    }

    private companion object {
        const val TAG = "NoteFlowTest"
        const val TITLE = "fleurs-it-short"
        const val WORD = "Antartide"
        const val SEEK_SLACK_MS = 300L
        const val PLAY_SLACK_MS = 3_000L
    }
}
