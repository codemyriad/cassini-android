package io.codemyriad.cassini.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.format.DayLabel
import io.codemyriad.cassini.ui.format.Durations
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime
import java.util.Locale

/** The compact visual contract must not replace the existing localized TalkBack status. */
@RunWith(RobolectricTestRunner::class)
class RowStatusLocalesTest {
    @get:Rule val rule = createComposeRule()

    @Test
    @Config(qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
    fun english() = assertStatuses("en-US")

    @Test
    @Config(qualifiers = "it-rIT-w390dp-h844dp-xhdpi")
    fun italian() = assertStatuses("it-IT")

    @Test
    @Config(qualifiers = "fr-rFR-w390dp-h844dp-xhdpi")
    fun french() = assertStatuses("fr-FR")

    @Test
    @Config(qualifiers = "es-rES-w390dp-h844dp-xhdpi")
    fun spanish() = assertStatuses("es-ES")

    @Test
    @Config(qualifiers = "pt-rBR-w390dp-h844dp-xhdpi")
    fun brazilianPortuguese() = assertStatuses("pt-BR")

    @Test
    @Config(qualifiers = "pt-rPT-w390dp-h844dp-xhdpi")
    fun europeanPortuguese() = assertStatuses("pt-PT")

    private fun assertStatuses(tag: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources = context.resources
        val locale = Locale.forLanguageTag(tag)
        assertEquals(tag, resources.configuration.locales[0].toLanguageTag())
        val clock = Durations.clock(DURATION_MS)
        val cases = listOf(
            Case(NoteStatusUi.Recording, R.string.ui_badge_recording, R.string.ui_status_recording, clock),
            Case(NoteStatusUi.Transcribing, R.string.ui_badge_transcribing, R.string.ui_status_transcribing),
            Case(NoteStatusUi.Queued, R.string.ui_badge_queued, R.string.ui_status_queued),
            Case(
                NoteStatusUi.Paused(DURATION_MS),
                R.string.ui_badge_paused,
                R.string.ui_status_paused_at,
                clock,
                clock,
            ),
            Case(NoteStatusUi.Failed, R.string.ui_badge_failed, R.string.ui_status_failed),
            Case(NoteStatusUi.Unplayable, R.string.ui_badge_unplayable, R.string.ui_status_unplayable),
            Case(NoteStatusUi.Unreadable, R.string.ui_badge_unreadable, R.string.ui_status_unreadable),
            Case(NoteStatusUi.NewerFormat, R.string.ui_badge_newer, R.string.ui_status_newer_format),
            Case(NoteStatusUi.Transcribed(1, 1), R.string.ui_status_transcribed, R.string.ui_status_transcribed),
            Case(NoteStatusUi.AudioOnly, R.string.ui_badge_audio_only, R.string.ui_status_audio_only),
        )
        var status by mutableStateOf<NoteStatusUi>(NoteStatusUi.Recording)
        rule.setContent {
            CassiniTheme {
                ProvideFixedFormat(locale = locale) {
                    NoteRow(
                        NoteRowUi(
                            id = "locale-row",
                            title = TITLE,
                            durationMs = DURATION_MS,
                            time = LocalTime.NOON,
                            status = status,
                        ),
                        DayLabel.Today,
                        onAction = {},
                    )
                }
            }
        }
        for (case in cases) {
            rule.runOnIdle { status = case.status }
            val visual = resources.getString(case.visual, *listOfNotNull(case.visualArgument).toTypedArray())
            val spoken = resources.getString(case.spoken, *listOfNotNull(case.spokenArgument).toTypedArray())
            // Same exact, unmerged text match as the acceptance walk's visible badge selector.
            rule.onNodeWithText(visual, useUnmergedTree = true).assertIsDisplayed()
            // Same case-insensitive status match as the Library @CiSmoke device flow.
            val row = rule.onNode(hasContentDescription(spoken, substring = true, ignoreCase = true))
            val description = row.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
            assertTrue("$tag/${case.status}: $description", description.endsWith(", ${spoken.lowercase(locale)}"))
        }
    }

    private data class Case(
        val status: NoteStatusUi,
        @param:StringRes val visual: Int,
        @param:StringRes val spoken: Int,
        val visualArgument: String? = null,
        val spokenArgument: String? = null,
    )

    private companion object {
        const val TITLE = "Locale row"
        const val DURATION_MS = 60_000L
    }
}
