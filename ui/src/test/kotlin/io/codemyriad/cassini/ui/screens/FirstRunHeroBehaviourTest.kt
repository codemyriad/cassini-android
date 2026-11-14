package io.codemyriad.cassini.ui.screens

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.library.FirstRunHeroUi
import io.codemyriad.cassini.ui.library.FirstRunPrivacy
import io.codemyriad.cassini.ui.library.LibraryScreen
import io.codemyriad.cassini.ui.library.LibraryUiState
import io.codemyriad.cassini.ui.theme.CassiniTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first-run hero (L-27, D-975) and its backup-gated copy (PARITY Wave 6 contract sections 1-2, D98): the
 * qualifier only while cloud backup is on, read as the catalog's combined sentence, and one privacy line per state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class FirstRunHeroBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private fun text(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun show(hero: FirstRunHeroUi) {
        rule.setContent { CassiniTheme { LibraryScreen(LibraryUiState(hero = hero), onEvent = {}) } }
    }

    @Test fun backupOnPairsTheSubtitleWithItsQualifierAndTheBackupPrivacyLine() {
        show(FirstRunHeroUi(cloudBackup = true, privacy = FirstRunPrivacy.WITH_BACKUP))
        rule.onNodeWithText(text(R.string.ui_hero_title))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithContentDescription(text(R.string.ui_hero_subtitle_backup)).assertIsDisplayed()
        rule.onNodeWithTag("hero-privacy").assert(hasText(text(R.string.ui_privacy_with_backup)))
        rule.onAllNodesWithText(text(R.string.ui_privacy_phone_only)).assertCountEquals(0)
    }

    @Test fun transferOnlyShowsTheProcessingLineWithoutTheQualifier() =
        cloudOff(FirstRunPrivacy.PROCESSING, R.string.ui_privacy_processing)

    @Test fun phoneOnlyIsTheOnlyStateThatSaysTheAudioNeverLeaves() =
        cloudOff(FirstRunPrivacy.PHONE_ONLY, R.string.ui_privacy_phone_only)

    private fun cloudOff(privacy: FirstRunPrivacy, line: Int) {
        show(FirstRunHeroUi(cloudBackup = false, privacy = privacy))
        rule.onNodeWithText(text(R.string.ui_hero_subtitle)).assertIsDisplayed()
        rule.onAllNodesWithContentDescription(text(R.string.ui_hero_subtitle_backup)).assertCountEquals(0)
        rule.onAllNodesWithText(text(R.string.ui_hero_backup_qualifier)).assertCountEquals(0)
        rule.onNodeWithTag("hero-privacy").assert(hasText(text(line)))
    }
}
