package io.codemyriad.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import kotlin.math.roundToInt

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

    @Test fun keyboardFocusDrawsAnOutsideRingWithoutStartingRecording() = focusedRecord(dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun keyboardFocusDrawsAnOutsideRingInDarkMode() = focusedRecord(dark = true)

    private fun focusedRecord(dark: Boolean) {
        val focus = FocusRequester()
        lateinit var inputMode: InputModeManager
        var accent = Color.Unspecified
        var density = 0f
        var clicks = 0
        rule.setContent {
            CassiniTheme(darkTheme = dark) {
                inputMode = LocalInputModeManager.current
                accent = CassiniTheme.colors.accent
                density = LocalDensity.current.density
                Box(Modifier.background(CassiniTheme.colors.bg).padding(24.dp)) {
                    RecordFab(onClick = { clicks++ }, modifier = Modifier.focusRequester(focus))
                }
            }
        }
        rule.runOnIdle {
            assertTrue(inputMode.requestInputMode(InputMode.Keyboard))
            focus.requestFocus()
        }
        rule.waitForIdle()
        val bounds = rule.onNodeWithContentDescription("Record a new note").getBoundsInRoot()
        val image = rule.onRoot().captureToImage().toPixelMap()
        // The stroke is centred 3 dp outside the pill: 2 dp clear gap, then a 2 dp accent ring.
        val ring = image[
            ((bounds.left.value + bounds.right.value) / 2 * density).roundToInt(),
            ((bounds.top.value - 3) * density).roundToInt(),
        ]
        assertEquals(accent.red, ring.red, 0.005f)
        assertEquals(accent.green, ring.green, 0.005f)
        assertEquals(accent.blue, ring.blue, 0.005f)
        rule.runOnIdle { assertEquals("focus must not start recording", 0, clicks) }
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
