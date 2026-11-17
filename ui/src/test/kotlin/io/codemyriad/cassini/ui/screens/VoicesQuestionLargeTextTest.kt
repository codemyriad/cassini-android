package io.codemyriad.cassini.ui.screens

import android.content.Context
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.note.NoteSheet
import io.codemyriad.cassini.ui.note.VoicesQuestionSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.util.Locale

@Config(qualifiers = "+fr-h600dp", fontScale = 1.5f)
class VoicesQuestionLargeTextTest : BoardTest() {
    override val locale: Locale = Locale.FRENCH
    override val fontScale: Float = 1.5f

    @Test fun bothAnswersAndTheFailureAreReachableInAShortWindow() {
        val choices = mutableListOf<Boolean>()
        show { VoicesQuestionSheet(choices::add, {}, NoteSheet.VoicesQuestion(error = true)) }
        rule.mainClock.autoAdvance = true
        val context = ApplicationProvider.getApplicationContext<Context>()
        rule.onNodeWithText(context.getString(R.string.ui_voices_setup_enable)).performScrollTo().performClick()
        rule.onNodeWithText(context.getString(R.string.ui_action_not_now)).performScrollTo().performClick()
        rule.onNodeWithText(context.getString(R.string.ui_settings_transfer_choice_failed)).performScrollTo()
        assertEquals(listOf(true, false), choices)
        assertTrue(cutTexts().toString(), cutTexts().isEmpty())
    }
}
