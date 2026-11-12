package io.codemyriad.cassini.ui.theme

import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.security.MessageDigest
import kotlin.math.roundToInt

/** The vendored token file is the value source; Color.kt is the only Android alias table. */
class BrandTokenSyncTest {
    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/brand/$name")).use { it.readBytes() }

    @Test fun vendorDigestAndEveryDecidedOrPlaceholderValueMatchInBothModes() {
        val bytes = resource("tokens.json")
        val upstream = JSONObject(resource("UPSTREAM.json").toString(Charsets.UTF_8))
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(upstream.getJSONObject("files").getJSONObject("tokens.json").getString("sha256"), hash)
        assertEquals("https://github.com/codemyriad/cassini-ux", upstream.getString("repository"))
        assertEquals(40, upstream.getString("commit").length)
        val sheet = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("cassini-ux/brand-tokens@1", sheet.getString("schema"))
        val tokens = sheet.getJSONObject("tokens")
        val accepted = tokens.keys().asSequence().filter {
            tokens.getJSONObject(it).getString("status") in setOf("decided", "placeholder")
        }.toSet()
        for ((mode, values) in listOf("light" to BrandTokenValues.light, "dark" to BrandTokenValues.dark)) {
            assertEquals("No proposed/pending roles may be read", accepted, values.keys)
            for (name in accepted) {
                val expected = tokens.getJSONObject(name).getString(mode)
                val color = values.getValue(name)
                val channels = listOf(color.red, color.green, color.blue) + if (expected.length == 9) listOf(color.alpha) else emptyList()
                val actual = "#" + channels.joinToString("") { "%02X".format((it * 255).roundToInt()) }
                assertEquals("$name/$mode", expected.uppercase(), actual)
            }
        }
    }

    @Test fun everyAndroidColorFieldHasAnAliasOrAnExplicitFrozenException() {
        val frozen = setOf("speakerQuestionWash", "violet", "violetTint")
        val colorFields = CassiniColors::class.java.declaredFields.filter {
            it.type == Color::class.java || it.type == Long::class.javaPrimitiveType
        }
        // Compose Color is an inline value class: its backing fields are packed Longs on the JVM.
        assertEquals(CanvasRoleTokens.keys + frozen, colorFields.map { it.name }.toSet())
        for (dark in listOf(false, true)) {
            val colors = canvasColors(dark)
            val values = requireNotNull(colors.brand)
            for ((field, token) in CanvasRoleTokens) {
                val backing = CassiniColors::class.java.getDeclaredField(field).apply { isAccessible = true }
                assertEquals("$field/$dark", values.getValue(token).value.toLong(), backing.getLong(colors))
            }
            val legacy = if (dark) LegacyDarkColors else LegacyLightColors
            assertEquals(legacy.speakerBlue, colors.speakerBlue)
            assertEquals(legacy.speakerTeal, colors.speakerTeal)
            assertEquals(legacy.speakerViolet, colors.speakerViolet)
            assertEquals(legacy.speakerAmber, colors.speakerAmber)
            assertEquals(legacy.speakerQuestionWash, colors.speakerQuestionWash)
        }
    }

    @Test fun plumbingLeavesTheActivePaletteUnchanged() {
        assertSame(LegacyLightColors, LightColors)
        assertSame(LegacyDarkColors, DarkColors)
    }
}
