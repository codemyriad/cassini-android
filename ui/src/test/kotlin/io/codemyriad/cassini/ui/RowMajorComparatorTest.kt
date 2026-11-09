package io.codemyriad.cassini.ui

import com.dropbox.differ.Color
import com.dropbox.differ.Image
import com.dropbox.differ.ImageComparator
import com.dropbox.differ.Mask
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Compare the complete result and each diff-mask float with the pinned library, without Android or rendering. */
class RowMajorComparatorTest {
    @Test fun pointwiseResultsAndMasksMatchTheLibrary() {
        val random = Random(82)
        val reference = RoborazziOptions.CompareOptions().imageComparator
        repeat(50) { seed ->
            val left = Pixels(11, 7) { _, _ -> Color(random.nextInt()) }
            val right = Pixels(11, 7) { x, y ->
                val original = left.getPixel(x, y)
                if ((x + y + seed) % 3 == 0) original.copy(r = original.r + 0.001f * (seed % 12)) else original
            }
            check(reference, left, right)
        }
        // Alpha, channel extremes, signed zero and non-finite values must retain the library's mask bits too.
        val colors = listOf(
            Color(0f, 0f, 0f, 0f),
            Color(1f, 1f, 1f, 1f),
            Color(-0f, 0f, 1f, 0f),
            Color(Float.NaN, 0f, 0f, 1f),
            Color(Float.POSITIVE_INFINITY, 0f, 0f, 1f),
        )
        check(reference, Pixels(5, 5) { x, _ -> colors[x] }, Pixels(5, 5) { _, y -> colors[y] })
    }

    @Test fun mismatchedDimensionsAndShiftedPoliciesUseTheReference() {
        val left = Pixels(3, 4) { x, y -> Color(x / 3f, y / 4f, 0f, 1f) }
        val right = Pixels(5, 2) { _, _ -> Color(0f, 0f, 0f, 1f) }
        check(SimpleImageComparator(), left, right)
        val shiftedLeft = Pixels(4, 2) { x, _ -> Color(if (x == 1) 1f else 0f, 0f, 0f, 1f) }
        val shiftedRight = Pixels(4, 2) { x, _ -> Color(if (x == 2) 1f else 0f, 0f, 0f, 1f) }
        check(SimpleImageComparator(hShift = 1, vShift = 1), shiftedLeft, shiftedRight)
        val reference = SimpleImageComparator()
        val expectedMask = Mask(6, 6)
        val actualMask = Mask(6, 6)
        assertEquals(
            reference.compare(left, left, expectedMask),
            RowMajorComparator(reference).compare(left, left, actualMask),
        )
        for (y in 0 until 6) for (x in 0 until 6) {
            assertEquals(expectedMask.getValue(x, y).toRawBits(), actualMask.getValue(x, y).toRawBits())
        }
        var called = false
        val custom = object : ImageComparator {
            override fun compare(left: Image, right: Image, diffMask: Mask?): ImageComparator.ComparisonResult {
                called = true
                return ImageComparator.ComparisonResult(3, 4, 1, 4)
            }
        }
        val expected = custom.compare(left, right, null)
        called = false
        assertEquals(expected, RowMajorComparator(custom).compare(left, right, null))
        assertTrue(called)
    }

    @Test fun theExistingPixelAndChangedAreaThresholdsRemainInForce() {
        val reference = RoborazziOptions.CompareOptions(changeThreshold = 0.0005f)
        val baseline = Pixels(100, 100) { _, _ -> Color(0f, 0f, 0f, 1f) }
        val faint = Pixels(100, 100) { _, _ -> Color(0.003f, 0f, 0f, 1f) }
        val changed = Pixels(100, 100) { x, y -> Color(if (x == 0 && y < 6) 1f else 0f, 0f, 0f, 1f) }
        val options = rowMajorComparison(reference)
        assertSame(reference.resultValidator, options.resultValidator)
        val fast = options.imageComparator
        assertEquals(0, fast.compare(baseline, faint, null).pixelDifferences)
        val result = fast.compare(baseline, changed, null)
        assertEquals(6, result.pixelDifferences)
        assertFalse(options.resultValidator(result))
        val belowLimit = Pixels(100, 100) { x, y -> Color(if (x == 0 && y < 4) 1f else 0f, 0f, 0f, 1f) }
        assertTrue(options.resultValidator(fast.compare(baseline, belowLimit, null)))
        assertEquals(
            reference.resultValidator(reference.imageComparator.compare(baseline, changed, null)),
            reference.resultValidator(result),
        )
    }

    private fun check(reference: ImageComparator, left: Image, right: Image) {
        val width = maxOf(left.width, right.width)
        val height = maxOf(left.height, right.height)
        val before = Mask(width, height)
        val after = Mask(width, height)
        val candidate = RowMajorComparator(reference)
        assertEquals(reference.compare(left, right, before), candidate.compare(left, right, after))
        assertEquals(reference.compare(left, right, null), candidate.compare(left, right, null))
        for (y in 0 until height) for (x in 0 until width) {
            assertEquals(before.getValue(x, y).toRawBits(), after.getValue(x, y).toRawBits())
        }
    }

    private class Pixels(override val width: Int, override val height: Int, pixel: (Int, Int) -> Color) : Image {
        private val colors = Array(width * height) { index -> pixel(index % width, index / width) }
        override fun getPixel(x: Int, y: Int): Color = colors[y * width + x]
    }
}
