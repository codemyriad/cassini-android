package io.codemyriad.cassini.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
class LibraryActionBarTest {
    @get:Rule val rule = createComposeRule()

    @Test fun recordAcknowledgesPointerDownWithTheCanvasShadeBeforeClick() {
        var clicks = 0
        rule.setContent { CassiniTheme { RecordFab(onClick = { clicks++ }) } }
        val button = rule.onNodeWithContentDescription("Record a new note")
        val bounds = button.getBoundsInRoot()
        assertEquals(56f, (bounds.bottom - bounds.top).value, 0.1f)
        val rest = button.captureToImage()
        val restPixel = rest.toPixelMap()[rest.width / 2, rest.height / 8]
        button.performTouchInput { down(center) }
        rule.waitForIdle()
        assertEquals("pointer down does not start recording", 0, clicks)
        val held = button.captureToImage()
        val pressed = held.toPixelMap()[held.width / 2, held.height / 8]
        assertEquals(restPixel.red * 0.84f, pressed.red, 0.005f)
        assertEquals(restPixel.green * 0.84f, pressed.green, 0.005f)
        assertEquals(restPixel.blue * 0.84f, pressed.blue, 0.005f)
        button.performTouchInput { up() }
        rule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun bottomBarKeepsBothActionsWithTouchTargetsAtLargeFontScale() {
        val events = mutableListOf<String>()
        rule.setContent {
            CassiniTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Box(Modifier.width(390.dp)) {
                        LibraryBottomBar(onImport = { events += "import" }, onRecord = { events += "record" })
                    }
                }
            }
        }
        val import = rule.onNodeWithContentDescription("Import audio")
        val record = rule.onNodeWithContentDescription("Record a new note")
        for (node in listOf(import, record)) {
            val bounds = node.getBoundsInRoot()
            assertTrue((bounds.bottom - bounds.top).value >= 48f && (bounds.right - bounds.left).value >= 48f)
        }
        assertTrue(import.getBoundsInRoot().right <= record.getBoundsInRoot().left)
        import.performClick()
        record.performClick()
        rule.runOnIdle { assertEquals(listOf("import", "record"), events) }
        rule.onNodeWithText("Record", useUnmergedTree = true).assertExists()
    }
}
