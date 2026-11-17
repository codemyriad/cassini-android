package io.codemyriad.cassini.ui.screens

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.library.LibraryEvent
import io.codemyriad.cassini.ui.library.LibraryScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.util.Locale

/** Every action stays reachable when the floating notice is taller than the available viewport. */
@Config(qualifiers = "+fr-h600dp", fontScale = 1.5f)
class BackupNoticeLargeTextTest : BoardTest() {
    override val locale: Locale = Locale.FRENCH
    override val fontScale: Float = 1.5f

    @Test fun bothChoicesWrapAndCanBeReached() {
        val events = mutableListOf<LibraryEvent>()
        show { LibraryScreen(BoardFixtures.library.copy(progress = null, backupCard = true), events::add) }
        assertTrue(cutTexts().toString(), cutTexts().isEmpty())
        val context = ApplicationProvider.getApplicationContext<Context>()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText(context.getString(R.string.ui_backup_card_dismiss)).performScrollTo().performClick()
        rule.onNode(
            hasText(context.getString(R.string.ui_backup_card_action)) and hasClickAction(),
        ).performScrollTo().performClick()
        assertEquals(listOf(LibraryEvent.DismissBackupCard, LibraryEvent.KeepOnPhoneOnly), events)
    }
}
