package io.codemyriad.cassini.flows

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.codemyriad.cassini.CassiniTestRunner
import io.codemyriad.cassini.MainActivity
import io.codemyriad.cassini.flows.Flows.awaitUi
import io.codemyriad.cassini.test.Manual
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import io.codemyriad.cassini.ui.R as UiR

/**
 * Settings, Model download and Licenses on an empty model root (features.md sections 5 and 11): the missing Parakeet
 * row opens speech-only consent, its sources row opens Licenses with a plain Back, and "Not now" goes back to
 * Settings. Nothing is downloaded. Run it with `-e emptyModelRoot true`; without that argument it is skipped, so it is
 * Manual (docs/development.md, Device flow tests).
 */
@Manual
class ModelDownloadFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int): String = compose.activity.getString(id)

    private fun shows(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Before
    fun needsAnEmptyModelRoot() = assumeTrue(CassiniTestRunner.emptyModelRoot)

    @Test
    fun notDownloadedRowOpensTheConsentAndNotNowGoesBack() {
        compose.onNodeWithContentDescription(text(UiR.string.ui_library_settings)).performClick()
        compose.awaitUi(TIMEOUT_MS) {
            shows(text(UiR.string.ui_settings_model_not_downloaded))
        }
        val speech = hasText("Parakeet Ultra") and hasClickAction()
        compose.awaitUi(TIMEOUT_MS) { compose.onAllNodes(speech).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(speech).performClick()

        compose.awaitUi(TIMEOUT_MS) { shows(text(UiR.string.ui_models_title)) }
        compose.onNodeWithText(text(UiR.string.ui_models_download)).assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.ui_part_speech)).assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.ui_part_speakers)).assertDoesNotExist()

        compose.onNodeWithText(text(UiR.string.ui_models_sources), substring = true).performClick()
        compose.awaitUi(TIMEOUT_MS) { shows(text(UiR.string.ui_licenses_title)) }
        compose.onNodeWithContentDescription(text(UiR.string.ui_back)).performClick()

        compose.awaitUi(TIMEOUT_MS) { shows(text(UiR.string.ui_models_not_now)) }
        compose.onNodeWithText(text(UiR.string.ui_models_not_now)).performClick()
        compose.awaitUi(TIMEOUT_MS) {
            shows(text(UiR.string.ui_settings_model_not_downloaded))
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
