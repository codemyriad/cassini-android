package org.cassini.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The moving track draws the same picture as the static one at a fixed fraction, in both layout directions: in RTL the
 * fill grows from the right edge.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class LinearTrackDirectionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theMovingTrackMatchesTheStaticOneLeftToRight() = compare(LayoutDirection.Ltr)

    @Test fun theMovingTrackMatchesTheStaticOneRightToLeft() = compare(LayoutDirection.Rtl)

    private fun compare(direction: LayoutDirection) {
        rule.setContent {
            CassiniTheme(darkTheme = false) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Column(Modifier.width(300.dp)) {
                        for (height in TrackHeight.entries) {
                            LinearTrack(0.62f, Modifier.testTag("static-$height"), height = height)
                            LinearTrack(0.62f, Modifier.testTag("moving-$height"), height = height, moving = true)
                        }
                    }
                }
            }
        }
        for (height in TrackHeight.entries) {
            val static = rule.onNode(hasTestTag("static-$height")).captureToImage().asAndroidBitmap()
            val moving = rule.onNode(hasTestTag("moving-$height")).captureToImage().asAndroidBitmap()
            assertTrue("The moving $height track differs from the static one in $direction", static.near(moving))
        }
    }

    // The thick fill is clipped on one and drawn rounded on the other: their antialiased edges may differ by a step.
    private fun Bitmap.near(other: Bitmap): Boolean =
        width == other.width && height == other.height && (0 until width).all { x ->
            (0 until height).all { y -> channelsNear(getPixel(x, y), other.getPixel(x, y)) }
        }

    private fun channelsNear(a: Int, b: Int): Boolean =
        (0 until Int.SIZE_BITS step Byte.SIZE_BITS).all { shift ->
            abs((a ushr shift and 0xFF) - (b ushr shift and 0xFF)) <= 2
        }
}
