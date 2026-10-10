package org.cassini.android.flows

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.cassini.android.CassiniTestRunner
import org.cassini.android.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.cassini.ui.R as UiR

/**
 * Settings, Model download and Licenses on an empty model root (features.md sections 5 and 11): a "Not downloaded"
 * row opens the consent screen, its sources row opens Licenses with a plain Back, and "Not now" goes back to
 * Settings. Nothing is downloaded. Run it with `-e emptyModelRoot true`; without that argument it is skipped.
 */
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
        compose.waitUntil(TIMEOUT_MS) {
            shows(text(UiR.string.ui_settings_model_not_downloaded))
        }
        compose.onNodeWithText(text(UiR.string.ui_part_speech)).performClick()

        compose.waitUntil(TIMEOUT_MS) { shows(text(UiR.string.ui_models_title)) }
        compose.onNodeWithText(text(UiR.string.ui_models_download)).assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.ui_part_speakers)).assertIsDisplayed()

        compose.onNodeWithText(text(UiR.string.ui_models_sources), substring = true).performClick()
        compose.waitUntil(TIMEOUT_MS) { shows(text(UiR.string.ui_licenses_title)) }
        compose.onNodeWithContentDescription(text(UiR.string.ui_back)).performClick()

        compose.waitUntil(TIMEOUT_MS) { shows(text(UiR.string.ui_models_not_now)) }
        compose.onNodeWithText(text(UiR.string.ui_models_not_now)).performClick()
        compose.waitUntil(TIMEOUT_MS) {
            shows(text(UiR.string.ui_settings_model_not_downloaded))
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
