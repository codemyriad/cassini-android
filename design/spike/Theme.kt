package org.cassini.spike

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.W400),
    Font(R.font.instrument_sans_medium, FontWeight.W500),
    Font(R.font.instrument_sans_semibold, FontWeight.W600),
    Font(R.font.instrument_sans_bold, FontWeight.W700),
)
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.W400),
    Font(R.font.ibm_plex_mono_medium, FontWeight.W500),
)

@Immutable
data class CassiniColors(
    val ink: Color = Color(0xFF14171A), val bg: Color = Color(0xFFF5F6F7), val surface: Color = Color(0xFFFFFFFF),
    val text2: Color = Color(0xFF4A525B), val text3: Color = Color(0xFF3A4250), val text4: Color = Color(0xFF6B7480),
    val line: Color = Color(0xFFE2E5E9), val line2: Color = Color(0xFFC9CED5), val line3: Color = Color(0xFF9AA2AC),
    val blue: Color = Color(0xFF2346C8), val blueInk: Color = Color(0xFF1A3596), val blueTint: Color = Color(0xFFE8EDFC), val blueTrack: Color = Color(0xFFC9D4F6),
    val teal: Color = Color(0xFF0F766E), val tealInk: Color = Color(0xFF0B5A54), val tealTint: Color = Color(0xFFE3F2EF),
    val amber: Color = Color(0xFFB45309), val amberInk: Color = Color(0xFF8A3B07), val amberTint: Color = Color(0xFFFCEFE3), val amberWash: Color = Color(0xFFFCF6EE),
    val red: Color = Color(0xFFC2410C), val redInk: Color = Color(0xFFA3330B), val redTint: Color = Color(0xFFFBE7E1), val redLine: Color = Color(0xFFE3B9A6),
    val violet: Color = Color(0xFF5B2BB5), val violetTint: Color = Color(0xFFEFEAFB),
)
val LocalCassiniColors = staticCompositionLocalOf { CassiniColors() }

private val Lh = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
fun sans(size: Int, weight: Int = 400, lh: Float = 1.22f, ls: Float = 0f) = TextStyle(
    fontFamily = InstrumentSans, fontWeight = FontWeight(weight), fontSize = size.sp,
    lineHeight = lh.em, letterSpacing = ls.em, lineHeightStyle = Lh,
)
fun mono(size: Int, weight: Int = 400, lh: Float = 1.3f, ls: Float = 0f) = TextStyle(
    fontFamily = PlexMono, fontWeight = FontWeight(weight), fontSize = size.sp,
    lineHeight = lh.em, letterSpacing = ls.em, lineHeightStyle = Lh,
)
