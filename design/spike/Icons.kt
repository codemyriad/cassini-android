package org.cassini.spike

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"

fun strokeIcon(name: String, size: Int, vb: Float, width: Float, vararg d: String) =
    ImageVector.Builder(name, size.dp, size.dp, vb, vb).apply {
        d.forEach {
            addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

object CassiniIcons {
    val Trash = strokeIcon("trash", 22, 24f, 1.7f, "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13")
    val Settings = strokeIcon("settings", 22, 24f, 1.7f, circle(12f, 12f, 3f),
        "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z")
    val Search = strokeIcon("search", 20, 24f, 1.8f, circle(11f, 11f, 7f), "m20 20-3.5-3.5")
    val Shield = strokeIcon("shield", 14, 24f, 2f, "M12 3 4 6v6c0 4.5 3.4 8 8 9 4.6-1 8-4.5 8-9V6z")
    val Import = strokeIcon("import", 20, 24f, 1.8f, "M12 4v11M7 10l5 5 5-5M5 20h14")
    fun logo(size: Int, width: Float) = ImageVector.Builder("logo", size.dp, size.dp, 28f, 28f).apply {
        addPath(addPathNodes(circle(14f, 14f, 5.5f)), stroke = SolidColor(Color.Black), strokeLineWidth = width)
        group(rotate = -20f, pivotX = 14f, pivotY = 14f) {
            addPath(addPathNodes("M2,14 a12,4.2 0 1,0 24,0 a12,4.2 0 1,0 -24,0"), stroke = SolidColor(Color.Black), strokeLineWidth = width)
        }
    }.build()
}

object MoreIcons {
    val Back = strokeIcon("back", 22, 24f, 1.8f, "M15 6l-6 6 6 6")
    val Search21 = strokeIcon("search", 21, 24f, 1.8f, "M18,11 a7,7 0 1,0 -14,0 a7,7 0 1,0 14,0", "m20 20-3.5-3.5")
    val Check = strokeIcon("check", 14, 24f, 2.2f, "m5 12 5 5 9-10")
    val ChevronDown14 = strokeIcon("chev", 14, 24f, 2f, "m6 9 6 6 6-6")
    val Back10 = strokeIcon("back10", 28, 24f, 1.6f, "M4 12a8 8 0 1 0 2.3-5.6", "M4 4v4h4")
    val Fwd10 = strokeIcon("fwd10", 28, 24f, 1.6f, "M20 12a8 8 0 1 1-2.3-5.6", "M20 4v4h-4")
    val More = ImageVector.Builder("more", 22.dp, 22.dp, 24f, 24f).apply {
        listOf(5f, 12f, 19f).forEach { y -> addPath(addPathNodes("M10.3,$y a1.7,1.7 0 1,0 3.4,0 a1.7,1.7 0 1,0 -3.4,0"), fill = SolidColor(Color.Black)) }
    }.build()
    val Pause = ImageVector.Builder("pause", 26.dp, 26.dp, 24f, 24f).apply {
        addPath(addPathNodes("M7 5h2a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z"), fill = SolidColor(Color.Black))
        addPath(addPathNodes("M15 5h2a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z"), fill = SolidColor(Color.Black))
    }.build()
}
