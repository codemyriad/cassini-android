package io.codemyriad.cassini.ui.theme

import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
        val accepted = checkedActiveRoles(sheet)
        for ((mode, values) in listOf("light" to BrandTokenValues.light, "dark" to BrandTokenValues.dark)) {
            assertEquals("No proposed/pending roles may be read", accepted, values.keys)
            for (name in accepted) {
                val token = sheet.getJSONObject("tokens").optJSONObject(name) ?: sheet.getJSONObject("derived").getJSONObject(name)
                val expected = token.getString(mode)
                val color = values.getValue(name)
                val channels = listOf(color.red, color.green, color.blue) + if (expected.length == 9) listOf(color.alpha) else emptyList()
                val actual = "#" + channels.joinToString("") { "%02X".format((it * 255).roundToInt()) }
                assertEquals("$name/$mode", expected.uppercase(), actual)
            }
        }
    }

    // Keep these explicit: promoting or adding a role must require an Android wiring decision.
    private val additionalCanvasRoles = setOf(
        "raised",
        "highlight",
        "highlight-ink",
        "neutral-ink",
        "neutral-tint",
        "record-ink",
        "record-tint",
        "line-soft",
    )
    private val unusedCanvasRoles = setOf("shadow", "card-rest")

    private fun checkedActiveRoles(sheet: JSONObject): Set<String> {
        val active = buildSet {
            for (sectionName in listOf("tokens", "derived")) {
                val section = sheet.optJSONObject(sectionName) ?: continue
                for (name in section.keys()) {
                    val status = section.getJSONObject(name).getString("status")
                    assertTrue(
                        "$sectionName/$name: unknown status '$status'",
                        status in setOf("decided", "placeholder", "proposed", "pending"),
                    )
                    if (status in setOf("decided", "placeholder")) add(name)
                }
            }
        }
        assertEquals(
            "Every active brand token needs an Android alias or a documented deferral/exemption",
            CanvasRoleTokens.values.toSet() + additionalCanvasRoles + unusedCanvasRoles,
            active,
        )
        return active
    }

    @Test fun addedAndPromotedTokensCannotSilentlyBypassAndroidCoverage() {
        val original = resource("tokens.json").toString(Charsets.UTF_8)
        val promotions = (7..10).flatMap { listOf("tokens" to "speaker-$it", "tokens" to "avatar-$it") } +
            listOf("derived" to "future-derived-role", "tokens" to "new-brand-role")
        for ((section, name) in promotions) {
            for (status in listOf("decided", "placeholder")) {
                val sheet = JSONObject(original)
                val roles = sheet.getJSONObject(section)
                val role = JSONObject().put("status", if (section == "derived") "pending" else "proposed")
                roles.put(name, role)
                checkedActiveRoles(sheet)
                role.put("status", status)
                val failure = assertThrows(AssertionError::class.java) { checkedActiveRoles(sheet) }
                assertTrue("$section/$name/$status", failure.message.orEmpty().contains("Every active brand token"))
            }
        }
    }

    @Test fun unknownStatusesCannotBeSilentlySkippedInEitherSection() {
        for ((section, name) in listOf("tokens" to "speaker-1", "derived" to "card-rest")) {
            val sheet = JSONObject(resource("tokens.json").toString(Charsets.UTF_8))
            sheet.getJSONObject(section).getJSONObject(name).put("status", "decide")
            val failure = assertThrows(AssertionError::class.java) { checkedActiveRoles(sheet) }
            assertTrue(failure.message.orEmpty().contains("$section/$name: unknown status 'decide'"))
        }
    }

    @Test fun everyAndroidColorFieldHasAnAliasOrAnExplicitFrozenException() {
        val frozen = emptySet<String>()
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
        }
    }

    @Test fun previewUsesTheCheckedCanvasPalette() {
        assertEquals(BrandTokenValues.light, LightColors.brand)
        assertEquals(BrandTokenValues.dark, DarkColors.brand)
        for (colors in listOf(LightColors, DarkColors)) {
            val additional = mapOf(
                "raised" to colors.raised,
                "highlight" to colors.highlight,
                "highlight-ink" to colors.highlightInk,
                "neutral-ink" to colors.neutralInk,
                "neutral-tint" to colors.neutralTint,
                "record-ink" to colors.recordInk,
                "record-tint" to colors.recordTint,
                "line-soft" to colors.lineSoft,
            )
            assertEquals(additionalCanvasRoles, additional.keys)
            for ((role, value) in additional) assertEquals(role, requireNotNull(colors.brand).getValue(role), value)
        }
    }
}
