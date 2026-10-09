package org.cassini.android

import android.graphics.Color

/** The interface colours, in one place. Light surfaces, one blue accent, orange-red only for recording. */
internal object Palette {
    val background = Color.rgb(245, 246, 247)
    val surface = Color.WHITE
    val text = Color.rgb(20, 23, 26)
    val textSoft = Color.rgb(58, 66, 80)
    val muted = Color.rgb(74, 82, 91)
    val line = Color.rgb(226, 229, 233)
    val lineStrong = Color.rgb(201, 206, 213)
    val accent = Color.rgb(35, 70, 200)
    val accentDeep = Color.rgb(26, 53, 150)
    val accentSoft = Color.rgb(232, 237, 252)
    val accentTrack = Color.rgb(201, 212, 246)
    val record = Color.rgb(194, 65, 12)
    val ok = Color.rgb(11, 90, 84)
    val okSoft = Color.rgb(227, 242, 239)
    val warn = Color.rgb(138, 59, 7)
    val warnSoft = Color.rgb(252, 239, 227)
    val error = Color.rgb(163, 51, 11)
    val errorSoft = Color.rgb(251, 231, 225)
    /** Search matches: a pale amber that keeps the blue active word distinct. */
    val match = Color.rgb(253, 230, 170)

    /** The capture screen is dark. */
    val night = Color.rgb(20, 23, 26)
    val nightLine = Color.rgb(58, 64, 72)
    val nightText = Color.rgb(242, 243, 245)
    val nightMuted = Color.rgb(169, 176, 185)
    val nightRecord = Color.rgb(255, 138, 85)

    /** Speaker dots and labels on white. Neighbours differ in lightness as well as hue; labels are dark enough to read. */
    private val speakerDots = intArrayOf(
        Color.rgb(35, 70, 200), Color.rgb(15, 118, 110), Color.rgb(180, 83, 9), Color.rgb(124, 58, 237),
        Color.rgb(190, 24, 93), Color.rgb(77, 124, 15), Color.rgb(3, 105, 161), Color.rgb(107, 114, 128))
    private val speakerLabels = intArrayOf(
        Color.rgb(26, 53, 150), Color.rgb(11, 90, 84), Color.rgb(138, 59, 7), Color.rgb(91, 43, 181),
        Color.rgb(157, 23, 77), Color.rgb(63, 98, 18), Color.rgb(7, 89, 133), Color.rgb(74, 82, 91))
    fun speakerDot(index: Int) = speakerDots[Math.floorMod(index, speakerDots.size)]
    fun speakerLabel(index: Int) = speakerLabels[Math.floorMod(index, speakerLabels.size)]
}
