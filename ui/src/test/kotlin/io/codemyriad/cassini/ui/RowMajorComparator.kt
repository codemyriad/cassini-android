package io.codemyriad.cassini.ui

import com.dropbox.differ.Image
import com.dropbox.differ.ImageComparator
import com.dropbox.differ.Mask
import com.dropbox.differ.SimpleImageComparator
import com.dropbox.differ.distance
import com.github.takahirom.roborazzi.RoborazziOptions

/** The pinned pointwise comparator, traversed in BufferedImage's row order. Other policies use the delegate. */
internal class RowMajorComparator(private val delegate: ImageComparator) : ImageComparator {
    override fun compare(left: Image, right: Image, diffMask: Mask?): ImageComparator.ComparisonResult {
        val maxDistance = (delegate as? SimpleImageComparator)?.takeIf { it.hShift == 0 && it.vShift == 0 }?.maxDistance
        val sameSize = left.width == right.width && left.height == right.height
        val validMask = diffMask?.let {
            it.width == left.width && it.height == left.height && it.size >= left.width * left.height
        } ?: true
        if (maxDistance == null || !sameSize || !validMask) return delegate.compare(left, right, diffMask)
        return compareRows(left, right, diffMask, maxDistance)
    }

    private fun compareRows(left: Image, right: Image, mask: Mask?, maxDistance: Float): ImageComparator.ComparisonResult {
        var differences = 0
        for (y in 0 until left.height) {
            for (x in 0 until left.width) {
                val distance = left.getPixel(x, y).distance(right.getPixel(x, y))
                if (distance > maxDistance) differences++
                mask?.setValue(x, y, distance)
            }
        }
        return ImageComparator.ComparisonResult(differences, left.width * left.height, left.width, left.height)
    }
}

/** Preserve the original changed-area validator when selecting row traversal. */
internal fun rowMajorComparison(original: RoborazziOptions.CompareOptions): RoborazziOptions.CompareOptions =
    original.copy(imageComparator = RowMajorComparator(original.imageComparator))
