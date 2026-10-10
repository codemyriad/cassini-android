package org.cassini.ui.screens

import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import org.cassini.ui.BoardTest
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Board 10, States, banners and errors (docs/design/ui.md section 10.2): one golden per column,
 * `states/10-states-col1.png` ... `col5.png`, captured from the column node. Compare them with the crops of
 * tools/ui-fidelity/crop_states.py through compare.py.
 */
@Config(qualifiers = StatesBoardTest.COLUMN)
class StatesBoardTest : BoardTest() {
    @Test fun trust() = states("states", 1)

    @Test fun recording() = states("states", 2)

    @Test fun transcription() = states("states", 3)

    @Test fun download() = states("states", 4)

    @Test fun library() = states("states", 5)

    companion object {
        /** A board column: 358 dp wide (the banners' width) and tall enough for the longest column. */
        const val COLUMN = "w358dp-h1200dp-xhdpi"

        /** [COLUMN] in the dark theme. */
        const val COLUMN_NIGHT = "w358dp-h1200dp-night-xhdpi"
    }
}

/** Draws States column [number] (1-5) and captures the column node to `<dir>/10-states-col<number>.png`. */
internal fun BoardTest.states(dir: String, number: Int, dark: Boolean = false) {
    show(dark) { StatesColumn(STATES_COLUMNS[number - 1]) }
    rule.onNodeWithTag(STATES_COLUMN_TAG).captureRoboImage(
        "src/test/screenshots/$dir/10-states-col$number.png",
        roborazziOptions = BoardTest.OPTIONS,
    )
}
