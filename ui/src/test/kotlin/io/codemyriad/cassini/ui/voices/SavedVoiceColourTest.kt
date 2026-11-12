package io.codemyriad.cassini.ui.voices

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import io.codemyriad.cassini.ui.theme.CassiniColors
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.SpeakerTone
import io.codemyriad.cassini.ui.theme.speaker
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
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
class SavedVoiceColourTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sortingKeepsStoredAvatarColoursInLightMode() = checkColours(dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun sortingKeepsStoredAvatarColoursInDarkMode() = checkColours(dark = true)

    private fun checkColours(dark: Boolean) {
        val people = persistentListOf(
            PersonUi("g", "Giulia", 14, 600, tone = SpeakerTone.Five),
            PersonUi("p", "Paolo", 2, 60, tone = SpeakerTone.Two),
        )
        val state = mutableStateOf(SavedVoicesUiState(people))
        lateinit var colors: CassiniColors
        var density = 0f
        rule.setContent {
            CassiniTheme(darkTheme = dark) {
                colors = CassiniTheme.colors
                density = LocalDensity.current.density
                SavedVoicesScreen(state.value, onEvent = {})
            }
        }
        repeat(2) {
            rule.waitForIdle()
            val image = rule.onRoot().captureToImage().toPixelMap()
            for (person in people) {
                val label = rule.onNodeWithText(person.name, useUnmergedTree = true).getBoundsInRoot()
                // The avatar is 40 dp, 12 dp before the label. Sample clear of its central initial.
                val pixel = image[
                    ((label.left.value - 44) * density).roundToInt(),
                    ((label.top.value + label.bottom.value) / 2 * density).roundToInt(),
                ]
                assertEquals("${person.id}/$dark", colors.speaker(person.tone).tint, pixel)
            }
            rule.runOnIdle { state.value = SavedVoicesUiState(state.value.people.reversed().toImmutableList()) }
        }
    }
}
