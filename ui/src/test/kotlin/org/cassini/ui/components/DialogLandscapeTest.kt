package org.cassini.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.cassini.ui.format.FIXED_ZONE
import org.cassini.ui.format.ProvideFixedFormat
import org.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * In a window wider than 560 dp (a phone in landscape), the dialog's scrim still covers the whole window: only the
 * card is capped at 560 dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w844dp-h390dp-xhdpi")
class DialogLandscapeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theScrimCoversAWideWindow() {
        rule.setContent {
            CassiniTheme(darkTheme = false) {
                ProvideFixedFormat(locale = Locale.ENGLISH, is24Hour = true, zone = FIXED_ZONE) {
                    CassiniDialog(
                        onDismissRequest = {},
                        title = "Delete for good?",
                        body = "This can’t be undone.",
                        actions = { DialogActions("Delete", onConfirm = {}, onCancel = {}, destructive = true) },
                    )
                }
            }
        }
        val pane = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Delete for good?"))
        val bounds = pane.getUnclippedBoundsInRoot()
        assertEquals(0f, bounds.left.value, TOLERANCE)
        assertEquals(0f, bounds.top.value, TOLERANCE)
        assertEquals(WIDTH, bounds.right.value, TOLERANCE)
        assertEquals(HEIGHT, bounds.bottom.value, TOLERANCE)
        val card = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading), useUnmergedTree = true)
        val cardWidth = card.getUnclippedBoundsInRoot().let { it.right - it.left }
        check(cardWidth <= 560.dp) { "title wider than the card cap: $cardWidth" }
    }

    private companion object {
        const val WIDTH = 844f
        const val HEIGHT = 390f
        const val TOLERANCE = 0.5f
    }
}
