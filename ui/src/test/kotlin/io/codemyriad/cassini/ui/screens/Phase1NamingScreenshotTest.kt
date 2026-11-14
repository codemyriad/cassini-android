package io.codemyriad.cassini.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.note.NoteScreen
import io.codemyriad.cassini.ui.note.NoteSheet
import org.junit.Test
import org.robolectric.annotation.Config

/** Actual native modal placement and scroll evidence, recorded only on Linux. */
class Phase1NamingScreenshotTest : BoardTest() {
    @Test fun nativeLight() = naming(dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun nativeDark() = naming(dark = true)
}

@Config(fontScale = 2f)
class Phase1NamingLargeScreenshotTest : BoardTest() {
    override val fontScale = 2f

    @Test fun nativeLight() = naming(dark = false, large = true)

    @Test
    @Config(qualifiers = "+night", fontScale = 2f)
    fun nativeDark() = naming(dark = true, large = true)
}

private fun BoardTest.naming(dark: Boolean, large: Boolean = false) {
    show(dark) {
        NoteScreen(
            BoardFixtures.readAndListen.copy(sheet = NoteSheet.NameSpeaker(BoardFixtures.nameSpeaker)),
            onEvent = {},
        )
    }
    rule.mainClock.autoAdvance = true
    rule.onNodeWithText("Remember this voice", substring = true).performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("Save name").assertIsDisplayed()
    rule.waitForIdle()
    rule.mainClock.autoAdvance = false
    rule.mainClock.advanceTimeBy(1_000)
    capture(if (dark) "dark" else "light", if (large) "19-name-native-large" else "18-name-native", window = true)
}
