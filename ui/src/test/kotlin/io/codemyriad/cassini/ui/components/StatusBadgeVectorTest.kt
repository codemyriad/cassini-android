package io.codemyriad.cassini.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.DarkColors
import io.codemyriad.cassini.ui.theme.LightColors
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Presentation proof only: these fixtures do not introduce shared persisted lifecycles into Android. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
class StatusBadgeVectorTest {
    @get:Rule val rule = createComposeRule()

    @Test fun meetingAndTransientEntryVectorsRenderTheirSharedCopyToneAndGlyphInBothModes() {
        val vectors = JSONObject(resource("vectors/states/states-vectors.json"))
        val cases = listOf("vectors", "entryVectors").flatMap { key ->
            val array = vectors.getJSONArray(key)
            List(array.length()) { array.getJSONObject(it) }
        }
        assertEquals(19, cases.size)
        var current by mutableStateOf<Sample?>(null)
        var dark by mutableStateOf(false)
        rule.setContent {
            CassiniTheme(darkTheme = dark) {
                current?.let {
                    StatusChip(
                        statusBadgeText(it.kind, it.liveMs, it.minutes),
                        it.kind,
                        Modifier.testTag("contract-badge"),
                    )
                }
            }
        }
        for (mode in listOf(false, true)) {
            for (vector in cases) {
                val expected = vector.optJSONObject("badge")
                val fields = vector.getJSONObject("fields")
                val sample = expected?.let {
                    Sample(kind(it.getString("kind")), fields.optLong("liveMs"), fields.optionalLong("etaMinutes"))
                }
                rule.runOnIdle {
                    dark = mode
                    current = sample
                }
                if (expected == null) {
                    rule.onNodeWithTag("contract-badge").assertDoesNotExist()
                    continue
                }
                val value = requireNotNull(sample)
                assertEquals(vector.getString("id"), expected.getString("tint"), value.kind.tone.name.lowercase())
                assertEquals(vector.getString("id"), expected.getString("glyph"), value.kind.glyph.name.lowercase())
                val text = rule.onNodeWithText(expected.getString("text"), useUnmergedTree = true)
                val layouts = mutableListOf<TextLayoutResult>()
                text.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                val colors = if (mode) DarkColors else LightColors
                val ink = when (value.kind.tone) {
                    StatusBadgeTone.Red -> colors.recordInk
                    StatusBadgeTone.Amber -> colors.amberInk
                    StatusBadgeTone.Neutral, StatusBadgeTone.Outline -> colors.text2
                }
                assertEquals(ink, layouts.single().layoutInput.style.color)
                val glyph = rule.onNodeWithTag("badge-glyph-${value.kind}", useUnmergedTree = true).fetchSemanticsNode()
                assertTrue(glyph.size.width > 0)
            }
        }
    }

    private data class Sample(val kind: StatusBadgeKind, val liveMs: Long, val minutes: Long?)

    private fun kind(name: String): StatusBadgeKind = when (name) {
        "needsAttention" -> StatusBadgeKind.Attention
        else -> StatusBadgeKind.entries.single { it.name.equals(name, ignoreCase = true) }
    }

    private fun JSONObject.optionalLong(name: String): Long? = if (isNull(name)) null else getLong(name)

    private fun resource(path: String): String =
        requireNotNull(javaClass.getResourceAsStream("/$path")).bufferedReader().use { it.readText() }
}
