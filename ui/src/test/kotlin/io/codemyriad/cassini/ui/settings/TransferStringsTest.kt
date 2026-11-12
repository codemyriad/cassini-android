package io.codemyriad.cassini.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.ui.R
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/** The pinned D97 catalog is the authority, including the trimmed recognition and Saved voices privacy copy. */
@RunWith(RobolectricTestRunner::class)
class TransferStringsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun allSixLanguagesUseThePinnedTransferAndPrivacyCopy() {
        val source = javaClass.getResourceAsStream("/transfer/strings.json")!!.bufferedReader().use { it.readText() }
        val catalog = JSONObject(source)
        val entries = catalog.getJSONArray("entries")
        for (tag in listOf("en", "it", "fr", "es", "pt-BR", "pt-PT")) {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            val resources = context.createConfigurationContext(configuration).resources
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val id = entry.getString("id")
                val expected = if (tag == "en") {
                    entry.getString("en")
                } else {
                    entry.getJSONObject("l10n").getJSONObject(tag).getString("text")
                }
                val wanted = expected.replace("{name}", "Ada")
                val actual = resources.getString(IDS.getValue(id), "Ada")
                assertEquals("$tag $id", wanted, actual)
            }
            assertEquals(
                resources.getString(R.string.ui_library_privacy),
                resources.getString(R.string.ui_settings_models_footnote),
            )
        }
    }

    private companion object {
        val IDS = mapOf(
            "settings.transfer.heading" to R.string.ui_settings_transfer_heading,
            "settings.transfer.title" to R.string.ui_settings_transfer_title,
            "settings.transfer.body" to R.string.ui_settings_transfer_body,
            "settings.transfer.body.off" to R.string.ui_settings_transfer_body_off,
            "settings.transfer.exportHint" to R.string.ui_settings_transfer_export_hint,
            "settings.transfer.tooLarge" to R.string.ui_settings_transfer_too_large,
            "settings.transfer.unavailable" to R.string.ui_settings_transfer_unavailable,
            "settings.transfer.choice.failed" to R.string.ui_settings_transfer_choice_failed,
            "android.privacy.processing" to R.string.ui_library_privacy,
            "android.speakers.rememberVoice.description" to R.string.ui_name_remember_body,
            "android.speakers.rememberVoice.description.unnamed" to R.string.ui_name_remember_body_unnamed,
            "android.savedVoices.privacy" to R.string.ui_voices_privacy,
        )
    }
}
