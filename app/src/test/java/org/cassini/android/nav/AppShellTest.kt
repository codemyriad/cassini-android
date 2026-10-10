package org.cassini.android.nav

import android.Manifest
import android.app.Application
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cassini.android.AppGraph
import org.cassini.android.CassiniApplication
import org.cassini.android.GraphFactory
import org.cassini.android.MainActivity
import org.cassini.android.recording.FakeCaptureSource
import org.cassini.android.recording.FakeConsent
import org.cassini.android.recording.FakePlatform
import org.cassini.android.recording.RecordingState
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.cassini.data.AppPaths
import org.cassini.data.models.ModelFetcher
import org.cassini.data.notes.ImportOutcome
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * The whole app on Robolectric: [MainActivity] over an [AppGraph] on a scratch root, with fakes for the microphone
 * and no model download (docs/design/ui.md sections 9 and 11, docs/architecture.md section 6). It checks back
 * (a sheet, then the search, then the screen), the minimised recording, the predictive back callback, the
 * edge-to-edge insets, the system bars of each screen and the TalkBack semantics of the Note screen.
 *
 * The Accessibility Test Framework checks do not run under Robolectric (P2); the device test
 * `flows/AccessibilityChecksTest` runs them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class AppShellTest {
    private val folder = TemporaryFolder()
    private val app: CassiniApplication = ApplicationProvider.getApplicationContext()
    private val platform = FakePlatform()

    /** Installs the scratch graph before the activity starts and closes it after the test. */
    private val graphRule = object : ExternalResource() {
        override fun before() {
            val paths = AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder())
            CassiniApplication.graphFactory = GraphFactory { context, _ ->
                AppGraph(
                    context,
                    AppGraph.Config(
                        paths = paths,
                        fetcher = ModelFetcher { _, _, _ -> fail("No model is fetched in this test") },
                        network = { false },
                        recording = AppGraph.RecordingHooks(
                            capture = FakeCaptureSource(),
                            platform = platform,
                            consent = FakeConsent(),
                        ),
                    ),
                )
            }
            platform.controller = { app.graph.recording }
            shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            runBlocking { withTimeout(TIMEOUT_MS) { app.graph.ready.await() } }
        }

        override fun after() {
            app.graph.close()
            CassiniApplication.graphFactory = null
        }
    }

    private val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(folder).around(graphRule).around(rule)

    @Test fun backClosesTheSheetThenTheSearchThenLeavesTheNote() {
        importDocument()
        rule.onNodeWithText(TITLE).performClick()
        waitFor(hasContentDescription("Search in transcript"))
        rule.onNodeWithContentDescription("Search in transcript").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("allora")
        rule.onNode(hasContentDescription("Speaker 2", substring = true)).performClick()
        waitFor(hasText("Save name"))

        Espresso.pressBack()
        rule.onNodeWithText("Save name").assertDoesNotExist()
        rule.onNodeWithContentDescription("Close search").assertExists()

        Espresso.pressBack()
        rule.onNodeWithContentDescription("Close search").assertDoesNotExist()
        rule.onNodeWithContentDescription("Search in transcript").assertExists()

        Espresso.pressBack()
        rule.onNodeWithContentDescription("Search in transcript").assertDoesNotExist()
        rule.onNodeWithContentDescription("Settings").assertExists()
        rule.onNodeWithText(TITLE).assertExists()
    }

    @Test fun backOnRecordingMinimisesItAndTheLibraryShowsTheCard() {
        shadowOf(app as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        rule.onNodeWithText("Record").performClick()
        rule.waitUntil(TIMEOUT_MS) { app.graph.recording.state.value is RecordingState.Active }
        rule.onNodeWithText("Finish").assertExists()

        Espresso.pressBack()
        rule.onNodeWithText("Finish").assertDoesNotExist()
        rule.onNodeWithText("Tap to return").assertExists()
        assertTrue("the recording continues", app.graph.recording.state.value is RecordingState.Active)

        rule.onNodeWithText("Tap to return").performClick()
        rule.onNodeWithText("Finish").assertExists()
    }

    @Test fun theAppUsesPredictiveBackAndTheLibraryLeavesBackToTheSystem() {
        // Robolectric does not set the ApplicationInfo flag, so the merged manifest it runs with is read instead.
        val attribute = "enableOnBackInvokedCallback"
        assertEquals("android:$attribute", "true", mergedManifestApplication(attribute))
        val dispatcher = rule.activity.onBackPressedDispatcher
        // The root takes no back, so the system animates back to home.
        rule.runOnIdle { assertFalse(dispatcher.hasEnabledCallbacks()) }

        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNode(hasText("App language", ignoreCase = true)).assertExists()
        rule.runOnIdle { assertTrue("NavDisplay takes back above the root", dispatcher.hasEnabledCallbacks()) }
        Espresso.pressBack()
        rule.onNode(hasText("App language", ignoreCase = true)).assertDoesNotExist()
        rule.runOnIdle { assertFalse(dispatcher.hasEnabledCallbacks()) }
    }

    @Test fun theScreensDrawEdgeToEdgeAndPadForTheSystemBars() {
        val content = rule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_PX, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAVIGATION_PX))
            .build()
        rule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(content, insets) }
        rule.waitForIdle()

        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val record = rule.onNodeWithContentDescription("Record a new note").fetchSemanticsNode().boundsInRoot.bottom
        val settings = rule.onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot
        val density = rule.activity.resources.displayMetrics.density
        // The board puts Record 24 dp above the bottom; the navigation bar adds its height.
        val gap = root.bottom - record
        assertTrue("Record sits ${gap}px above the bottom", abs(gap - (NAVIGATION_PX + BOTTOM_DP * density)) < 2f)
        assertTrue("the header starts below the status bar", settings.top >= STATUS_PX)
    }

    @Test fun eachScreenSetsItsSystemBarIcons() {
        assertEquals(true, darkIcons())
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNode(hasText("App language", ignoreCase = true)).assertExists()
        assertEquals(true, darkIcons())
        Espresso.pressBack()

        shadowOf(app as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        rule.onNodeWithText("Record").performClick()
        rule.onNodeWithText("Finish").assertExists()
        assertEquals("Recording is dark in both themes", false, darkIcons())
        Espresso.pressBack()
        rule.onNodeWithText("Tap to return").assertExists()
        assertEquals(true, darkIcons())
    }

    @Config(qualifiers = "+night")
    @Test
    fun theDarkThemeHasLightBarIcons() {
        rule.onNodeWithContentDescription("Settings").assertExists()
        assertEquals(false, darkIcons())
    }

    @Test fun transcriptWordsAreLinksAndSpeakerLabelsAreSpokenButtons() {
        importDocument()
        rule.onNodeWithText(TITLE).performClick()
        waitFor(hasContentDescription("Search in transcript"))

        val speaker = rule.onNode(hasContentDescription("Speaker 2", substring = true))
        speaker.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val label = speaker.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label
        assertEquals("Name this speaker", label)
        val description = speaker.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue("the label says when the turn starts: $description", description.startsWith("Speaker 2, at "))
        rule.onNode(hasContentDescription("Ada", substring = true))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))

        // Each word of a turn is its own link node for TalkBack, inside the paragraph.
        val paragraph = rule.onNode(hasText(SECOND_TURN), useUnmergedTree = true).fetchSemanticsNode()
        val links = paragraph.children.count { it.config.contains(SemanticsProperties.LinkTestMarker) }
        assertEquals(SECOND_TURN.split(' ').size, links)
    }

    /** An attribute of `<application>` in the merged manifest of this test run (AGP's test_config.properties). */
    private fun mergedManifestApplication(attribute: String): String {
        val config = Properties()
        javaClass.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties").use(config::load)
        val manifest = File(config.getProperty("android_merged_manifest"))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val application = factory.newDocumentBuilder().parse(manifest).getElementsByTagName("application").item(0)
        return (application as Element).getAttributeNS(ANDROID_NS, attribute)
    }

    private fun waitFor(matcher: SemanticsMatcher) =
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun darkIcons(): Boolean {
        rule.waitForIdle()
        val window = rule.activity.window
        return WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
    }

    /** A sealed two-speaker document over the FLEURS clip, imported as ACTION_VIEW does; Library shows its row. */
    private fun importDocument() {
        val outcome = runBlocking {
            withTimeout(TIMEOUT_MS) { app.graph.notices.imports.start(Uri.fromFile(writeDocument()).toString()).await() }
        }
        assertTrue("imported: $outcome", outcome is ImportOutcome.Created)
        waitFor(hasText(TITLE))
    }

    /** The document of [importDocument], written to a scratch folder. */
    private fun writeDocument(): File {
        val fixture = File(checkNotNull(System.getProperty("cassini.fixtures")), "audio/fleurs-it-short.opus")
        val document = File(folder.newFolder(), "riunione.opus")
        val words = listOf(
            Word("S1", 0, 400, "ciao"),
            Word("S1", 450, 900, "a"),
            Word("S1", 950, 1_400, "tutti"),
            Word("S2", 2_000, 2_400, "allora"),
            Word("S2", 2_450, 2_900, "cominciamo"),
            Word("S2", 2_950, 3_400, "subito"),
        )
        CassiniDocument.create(
            fixture,
            Transcript(words, language = "it"),
            TITLE,
            JSONObject().put("engine", "test"),
            null,
            mapOf("S1" to "Ada"),
            document,
        )
        return document
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val TITLE = "Riunione"
        const val SECOND_TURN = "allora cominciamo subito"
        const val TIMEOUT_MS = 10_000L
        const val STATUS_PX = 60
        const val NAVIGATION_PX = 96
        const val BOTTOM_DP = 24
    }
}
