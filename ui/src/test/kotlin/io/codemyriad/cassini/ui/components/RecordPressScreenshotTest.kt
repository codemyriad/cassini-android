package io.codemyriad.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.theme.CassiniTheme
import org.junit.Test
import org.robolectric.annotation.Config

/** Real pointer and keyboard focus states; no elapsed-time sampling or animation capture. */
class RecordPressScreenshotTest : BoardTest() {
    @Test fun pressedLight() = pressed(dark = false, name = "record-pressed-light")

    @Test
    @Config(qualifiers = "+night")
    fun pressedDark() = pressed(dark = true, name = "record-pressed-dark")

    @Test fun focusedLight() = focused(dark = false, name = "record-focused-light")

    @Test
    @Config(qualifiers = "+night")
    fun focusedDark() = focused(dark = true, name = "record-focused-dark")

    private fun focused(dark: Boolean, name: String) {
        val focus = FocusRequester()
        lateinit var inputMode: InputModeManager
        show(dark) {
            inputMode = LocalInputModeManager.current
            Box(Modifier.fillMaxWidth().background(CassiniTheme.colors.bg).padding(24.dp)) {
                RecordFab(onClick = {}, modifier = Modifier.focusRequester(focus))
            }
        }
        rule.runOnIdle {
            check(inputMode.requestInputMode(InputMode.Keyboard))
            focus.requestFocus()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        capture("components", name)
    }

    private fun pressed(dark: Boolean, name: String) {
        show(dark) {
            Box(Modifier.fillMaxWidth().background(CassiniTheme.colors.bg).padding(24.dp)) {
                RecordFab(onClick = {})
            }
        }
        val button = rule.onNodeWithContentDescription("Record a new note")
        button.performTouchInput { down(center) }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        try {
            capture("components", name)
        } finally {
            button.performTouchInput { up() }
        }
    }
}
