package org.cassini.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The static picture of the [Waveform] (docs/design/ui.md section 6): it depends only on the bars. The captured time
 * does not move it, and a running waveform starts from the same picture. Compose tests stop infinite animations, so
 * a running waveform in a screenshot test also keeps this picture, and the goldens stay the same.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class WaveformTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theStaticPictureDoesNotDependOnTheCapturedTime() {
        val bars = boardWaveformLevels()
        show {
            Box(Modifier.testTag(FIRST)) { Waveform(bars) }
            Box(Modifier.testTag(SECOND)) { Waveform(bars.copy(capturedMs = 1_447_310)) }
        }
        assertTrue("The captured time moved the static bars", picture(FIRST).sameAs(picture(SECOND)))
    }

    @Test fun aRunningWaveformStartsFromTheStaticPicture() {
        val bars = boardWaveformLevels().copy(capturedMs = 7_310)
        show {
            Box(Modifier.testTag(FIRST)) { Waveform(bars) }
            Box(Modifier.testTag(SECOND)) { Waveform(bars, running = true) }
        }
        rule.mainClock.advanceTimeBy(ADVANCE_MS)
        assertTrue("The running waveform differs from the static one", picture(FIRST).sameAs(picture(SECOND)))
    }

    @Test fun theBarsAreDrawn() {
        show {
            Box(Modifier.testTag(FIRST)) { Waveform(boardWaveformLevels()) }
            Box(Modifier.testTag(SECOND)) { Waveform(WaveformLevels.Empty) }
        }
        assertFalse("The waveform drew no bars", picture(FIRST).sameAs(picture(SECOND)))
    }

    private fun show(content: @Composable () -> Unit) {
        rule.setContent {
            CassiniTheme(darkTheme = false) {
                Column(Modifier.width(390.dp).background(CassiniTheme.recording.bg)) { content() }
            }
        }
    }

    private fun picture(tag: String): Bitmap = rule.onNode(hasTestTag(tag)).captureToImage().asAndroidBitmap()

    private companion object {
        const val FIRST = "first"
        const val SECOND = "second"
        const val ADVANCE_MS = 1_000L
    }
}
