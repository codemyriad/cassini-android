package org.cassini.android.flows

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.cassini.android.MainActivity
import org.cassini.android.background.Background
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.cassini.data.db.LibraryRow
import org.cassini.data.notes.ImportOutcome
import org.json.JSONObject
import org.junit.Assert.assertTrue
import java.io.File

/** The compose rule of the flow tests: the real [MainActivity] over the graph of `CassiniTestRunner`. */
internal typealias AppRule = AndroidComposeTestRule<*, MainActivity>

/**
 * Shared parts of the flow tests (docs/architecture.md section 9). They drive [MainActivity] over the graph that
 * `CassiniTestRunner` built on the scratch root of the run, and wait on the graph's state flows or on the screen,
 * never on fixed sleeps.
 */
internal object Flows {
    const val SCREEN_MS = 15_000L
    const val JOB_MS = 300_000L
    private const val TAG = "CassiniFlow"

    val graph get() = Background.graph

    /** Text of [id] in the activity's language (English on the test devices unless a test changes it). */
    fun AppRule.text(id: Int, vararg args: Any): String = activity.getString(id, *args)

    /** Waits until a node matches [matcher]; [timeoutMs] covers async ViewModel loads and the job. */
    fun AppRule.waitShown(matcher: SemanticsMatcher, timeoutMs: Long = SCREEN_MS, unmerged: Boolean = false) =
        logOnTimeout(matcher) {
            waitUntil(timeoutMs) { onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty() }
        }

    /**
     * Waits until a node matches [matcher] without waiting for the UI to go idle first, as [waitShown] does. Use it
     * while the player plays: it redraws every frame then, so an idle-synced lookup returns only after playback ends.
     */
    fun AppRule.waitShownLive(matcher: SemanticsMatcher, timeoutMs: Long = SCREEN_MS, unmerged: Boolean = false) =
        logOnTimeout(matcher) {
            waitUntil(timeoutMs) { liveNodes(unmerged).any(matcher::matches) }
        }

    /** The semantics nodes of the activity's window as they are now, read on the UI thread without an idle sync. */
    fun AppRule.liveNodes(unmerged: Boolean = false): List<SemanticsNode> = runOnUiThread {
        composeRoots(activity.window.decorView).flatMap { root ->
            val owner = root.semanticsOwner
            descendants(if (unmerged) owner.unmergedRootSemanticsNode else owner.rootSemanticsNode)
        }
    }

    private fun composeRoots(view: View): List<ViewRootForTest> = when {
        view is ViewRootForTest -> listOf(view)
        view is ViewGroup -> (0 until view.childCount).flatMap { composeRoots(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)

    /** Runs [wait]; on a timeout logs the screen's semantics tree (tag CassiniFlow) before the failure. */
    private fun AppRule.logOnTimeout(matcher: SemanticsMatcher, wait: () -> Unit) {
        try {
            wait()
        } catch (timeout: ComposeTimeoutException) {
            onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { index ->
                onAllNodes(isRoot())[index].printToLog(TAG)
            }
            throw AssertionError("Not shown: ${matcher.description}", timeout)
        }
    }

    fun AppRule.waitText(text: String, timeoutMs: Long = SCREEN_MS, substring: Boolean = false) =
        waitShown(hasText(text, substring = substring), timeoutMs)

    fun AppRule.waitGone(matcher: SemanticsMatcher, timeoutMs: Long = SCREEN_MS) =
        waitUntil(timeoutMs) { onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }

    /**
     * Waits until the window that shows the node of [matcher] has no keyboard inset. A sheet opened while a field has
     * the keyboard first stands above the keyboard, with its content scrolled; the system closes the keyboard when the
     * sheet's window takes the focus, and the sheet then grows to its full height. Until then, the rows move.
     */
    fun AppRule.waitKeyboardClosed(matcher: SemanticsMatcher, timeoutMs: Long = SCREEN_MS) {
        val window = (onNode(matcher).fetchSemanticsNode().root as ViewRootForTest).view.rootView
        waitFor("The keyboard closes under ${matcher.description}", timeoutMs) {
            runOnUiThread {
                val insets = ViewCompat.getRootWindowInsets(window)
                (insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0) == 0
            }
        }
    }

    /**
     * Presses Back once the activity's own window has input focus. Right after an ACTION_VIEW (`onNewIntent`, the
     * activity pauses and resumes) a slow emulator can drop a key injected before the window has focus again, so
     * the Back press would not reach the app. Do not use it to close a sheet or dialog: their window has the focus.
     */
    fun AppRule.pressBackWhenFocused(timeoutMs: Long = SCREEN_MS) {
        try {
            waitUntil(timeoutMs) { activity.hasWindowFocus() }
        } catch (timeout: ComposeTimeoutException) {
            val waited = "waited ${timeoutMs / 1_000} s"
            throw AssertionError("The activity got no input focus ($waited; ${focusReport()})", timeout)
        }
        Espresso.pressBack()
    }

    /**
     * The window that has the input focus and any system error dialog, for a failure message. A dialog that stays
     * over the test (a launcher's "Application Not Responding") keeps the focus: no test window gets it, and no soft
     * keyboard opens. API 34 does not always report such a dialog as the focused window, so the window list counts.
     */
    fun focusReport(): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val windows = automation.executeShellCommand("dumpsys window windows").use { pipe ->
            ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().readText()
        }
        val focus = windows.lineSequence().map(String::trim).filter { it.startsWith("mCurrentFocus=") }.distinct()
        val dialogs = ERROR_DIALOG.findAll(windows).map { it.value }.distinct()
        return "${focus.joinToString().ifEmpty { "no mCurrentFocus" }}, system error dialogs: " +
            dialogs.joinToString().ifEmpty { "none" }
    }

    private val ERROR_DIALOG = Regex("Application (Not Responding|Error): [^ }:]+")

    /** Polls [condition] on the test thread (graph state, files) while the UI keeps running. */
    fun AppRule.waitFor(message: String, timeoutMs: Long = SCREEN_MS, condition: suspend () -> Boolean) {
        try {
            waitUntil(timeoutMs) { runBlocking { condition() } }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("$message (waited ${timeoutMs / 1_000} s)", timeout)
        }
    }

    /**
     * Opens [file] as ACTION_VIEW does from a file manager: the intent reaches the running activity (`singleTop`,
     * so `onNewIntent`). ActivityScenario follows only an activity whose intent equals its launch intent, so the
     * launch intent is put back once the activity has taken the new one; else the rule cannot close the activity.
     */
    fun AppRule.view(file: File, mime: String) {
        val launch = activity.intent
        val intent = Intent(activity, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(file), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(intent)
        waitUntil(SCREEN_MS) { activity.intent.action == Intent.ACTION_VIEW }
        runOnUiThread { activity.intent = launch }
    }

    /** The live (not trashed) Library rows of the scratch root. */
    suspend fun rows(): List<LibraryRow> = graph.awaitCatalogue().notes.library().first()

    /** The single row titled [title]; fails when there is none or more than one. */
    suspend fun row(title: String): LibraryRow? = rows().singleOrNull { it.title == title }

    /** Taps the word [word] of a Read & listen paragraph at the centre of its glyphs. */
    fun SemanticsNodeInteraction.tapWord(word: String) {
        val node = fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        val layout = results.single()
        val text = layout.layoutInput.text.text
        val start = Regex("\\b${Regex.escape(word)}\\b").find(text)?.range?.first
        assertTrue("\"$word\" is in the paragraph: $text", start != null)
        val box = layout.getBoundingBox(start!! + word.length / 2)
        performTouchInput { click(Offset(box.center.x, box.center.y)) }
    }

    /**
     * Answers the next `ACTION_CREATE_DOCUMENT` with [target], as the system picker would when the user saves there.
     * The app's ActivityResultRegistry receives the result through Instrumentation, so no picker opens. Remove the
     * monitor with [Instrumentation.removeMonitor].
     */
    fun createDocumentAnswer(target: File): Instrumentation.ActivityMonitor {
        val result = Instrumentation.ActivityResult(
            android.app.Activity.RESULT_OK,
            Intent().setData(Uri.fromFile(target)),
        )
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                result.takeIf { intent.action == Intent.ACTION_CREATE_DOCUMENT }
        }
        Background.instrumentation.addMonitor(monitor)
        return monitor
    }

    /**
     * A sealed two-speaker document over the FLEURS Opus clip, written to the scratch cache: "ciao a tutti" by Ada,
     * then "allora cominciamo subito" by a placeholder speaker.
     */
    fun meetingDocument(title: String): File {
        val audio = Background.assetCopy("fleurs-it-short.opus")
        val document = File(audio.parentFile, "$title.opus")
        val words = listOf(
            Word("S1", 0, 400, "ciao"),
            Word("S1", 450, 900, "a"),
            Word("S1", 950, 1_400, "tutti"),
            Word("S2", 2_000, 2_400, "allora"),
            Word("S2", 2_450, 2_900, "cominciamo"),
            Word("S2", 2_950, 3_400, "subito"),
        )
        CassiniDocument.create(
            audio,
            Transcript(words, language = "it"),
            title,
            JSONObject().put("engine", "test"),
            null,
            mapOf("S1" to "Ada"),
            document,
        )
        audio.delete()
        return document
    }

    /** Imports [file] as the Library's picker does (through the import tracker) and returns its note id. */
    suspend fun import(file: File): String =
        when (val outcome = graph.notices.imports.start(Uri.fromFile(file).toString()).await()) {
            is ImportOutcome.Created -> outcome.noteId
            is ImportOutcome.Existing -> outcome.noteId
            else -> throw AssertionError("Import of ${file.name} ended with $outcome")
        }
}
