package org.cassini.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** How many steps of the stage tracker share a row (features.md section 14: text follows the font scale). */
class StageRowsTest {
    @Test fun fiveStepsShareARowWhenTheWidestWordFits() {
        assertEquals(5, stagesPerRow(widestPx = 120, widthPx = 660, gapPx = 8, minScale = 0.85f))
    }

    @Test fun atLargeScalesTheStepsTakeMoreRowsBeforeTheLabelsShrinkFurther() {
        assertEquals(3, stagesPerRow(widestPx = 200, widthPx = 660, gapPx = 8, minScale = 0.85f))
        assertEquals(2, stagesPerRow(widestPx = 300, widthPx = 660, gapPx = 8, minScale = 0.85f))
        assertEquals(1, stagesPerRow(widestPx = 500, widthPx = 660, gapPx = 8, minScale = 0.85f))
    }

    @Test fun atTheDefaultScaleLabelsMayShrinkToHalfInFiveColumns() {
        assertEquals(5, stagesPerRow(widestPx = 200, widthPx = 660, gapPx = 8, minScale = 0.5f))
    }
}
