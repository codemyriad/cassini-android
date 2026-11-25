package io.codemyriad.cassini.ui.screens

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.library.FirstRunHeroUi
import io.codemyriad.cassini.ui.library.LibraryEvent
import io.codemyriad.cassini.ui.library.LibraryScreen
import io.codemyriad.cassini.ui.library.LibraryUiState
import io.codemyriad.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first-run hero (L-70, L-71, D105): "Every word written down." / "Right here, on this phone.",
 * the phone how-to line, and the always-true two-line privacy line, with no backup qualifier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class FirstRunHeroBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private fun text(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    @Test fun showsD105HeroHowToAndTwoLinePrivacyWithoutBackupQualifier() {
        rule.setContent { CassiniTheme { LibraryScreen(LibraryUiState(hero = FirstRunHeroUi), onEvent = {}) } }
        rule.onNodeWithText(text(R.string.ui_hero_title))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithText(text(R.string.ui_hero_subtitle_phone)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.ui_first_run_how_to_phone)).assertIsDisplayed()
        val expectedPrivacy = text(R.string.ui_first_run_privacy_phone) + "\n" +
            text(R.string.ui_first_run_privacy_backups)
        rule.onNodeWithTag("hero-privacy").assertIsDisplayed().assert(hasText(expectedPrivacy))
    }

    /** QA 25 Nov: before the first meeting there is no search, no Trash and no ZIP export; only Settings stays. */
    @Test fun firstRunShowsOnlySettingsAtTheTop() {
        val events = mutableListOf<LibraryEvent>()
        rule.setContent {
            CassiniTheme { LibraryScreen(LibraryUiState(hero = FirstRunHeroUi), onEvent = { events += it }) }
        }
        rule.onNodeWithTag("library-search-bar").assertDoesNotExist()
        rule.onNodeWithContentDescription(text(R.string.ui_library_trash)).assertDoesNotExist()
        rule.onNodeWithTag("first-run-top-bar").assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.ui_library_settings)).assertIsDisplayed().performClick()
        assertEquals(listOf<LibraryEvent>(LibraryEvent.OpenSettings), events)
    }
}
