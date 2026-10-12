package org.cassini.android.flows

import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import org.cassini.android.MainActivity
import org.cassini.test.CiSmoke
import org.junit.Rule
import org.junit.Test
import org.cassini.ui.R as UiR

/**
 * The Accessibility Test Framework checks on the real app (features.md section 14): the Library of the runner's
 * scratch root, then Settings with its ViewModel over the graph. [ScreenAccessibilityTest] checks the screens in
 * board states; this one checks the screens as the routes, the insets and the theme of [MainActivity] build them.
 */
@CiSmoke
class AppAccessibilityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int): String = rule.activity.getString(id)

    @Test fun theLibraryAndSettingsHaveNoFindings() {
        val suffixes = setOf(text(UiR.string.ui_action_resume), text(UiR.string.ui_action_try_again))
        rule.enableAccessibilityChecks(accessibilityValidator(suffixes))
        val settings = text(UiR.string.ui_library_settings)
        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodesWithContentDescription(settings).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onRoot().tryPerformAccessibilityChecks()

        rule.onNodeWithContentDescription(settings).performClick()
        val back = text(UiR.string.ui_back_to_library)
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodesWithContentDescription(back).fetchSemanticsNodes().isNotEmpty() }
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
