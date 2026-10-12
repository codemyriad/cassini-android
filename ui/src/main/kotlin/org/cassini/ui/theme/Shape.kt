// The radii of docs/design/ui.md section 4.4, in the file docs/design/ui.md section 1 names.
@file:Suppress("MagicNumber", "MatchingDeclarationName")

package org.cassini.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** The corner shapes of docs/design/ui.md section 4.4, read through `CassiniTheme.shapes`. */
@Immutable
class CassiniShapes {
    /** Fully rounded ends: pills, round buttons, the Record button. */
    val pill: RoundedCornerShape = RoundedCornerShape(percent = 50)

    /** 2 dp: the 4 dp tracks. */
    val track4: RoundedCornerShape = RoundedCornerShape(2.dp)

    /** 3 dp: the 6 dp tracks. */
    val track6: RoundedCornerShape = RoundedCornerShape(3.dp)

    /** 4 dp: the active word, the Finish square, the checkbox. */
    val small: RoundedCornerShape = RoundedCornerShape(4.dp)

    /** 6 dp: the "auto" badge. */
    val badge: RoundedCornerShape = RoundedCornerShape(6.dp)

    /** 8 dp: tags, the format badge. */
    val tag: RoundedCornerShape = RoundedCornerShape(8.dp)

    /** 10 dp: status chips. */
    val chip: RoundedCornerShape = RoundedCornerShape(10.dp)

    /** 12 dp: segments. */
    val segment: RoundedCornerShape = RoundedCornerShape(12.dp)

    /** 14 dp: rows, inputs, inner sheet groups, the suggestion card, the snackbar, the confirm box, menus. */
    val row: RoundedCornerShape = RoundedCornerShape(14.dp)

    /** 16 dp: banners, settings groups, trash cards, the library banner, 32 dp chips. */
    val card: RoundedCornerShape = RoundedCornerShape(16.dp)

    /** 18 dp: the Saved voices list. */
    val list: RoundedCornerShape = RoundedCornerShape(18.dp)

    /** 20 dp: the progress card. */
    val progressCard: RoundedCornerShape = RoundedCornerShape(20.dp)

    /** 28 dp top corners: bottom sheets. */
    val sheet: RoundedCornerShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** 28 dp: dialogs. */
    val dialog: RoundedCornerShape = RoundedCornerShape(28.dp)
}

/** The M3 shape scale for stock components. */
fun CassiniShapes.toMaterialShapes(): Shapes = Shapes(
    extraSmall = small,
    small = tag,
    medium = row,
    large = card,
    extraLarge = dialog,
)

val CassiniShapeSet: CassiniShapes = CassiniShapes()

val LocalCassiniShapes = staticCompositionLocalOf { CassiniShapeSet }
