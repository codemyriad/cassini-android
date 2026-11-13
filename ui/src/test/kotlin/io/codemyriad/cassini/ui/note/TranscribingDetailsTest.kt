package io.codemyriad.cassini.ui.note

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.banner.BannerAction
import io.codemyriad.cassini.ui.components.DownloadUi
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.BatteryUi
import io.codemyriad.cassini.ui.format.ModelPartUi
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.format.StageUi
import io.codemyriad.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
class TranscribingDetailsTest {
    @get:Rule val rule = createComposeRule()
    private val card = BoardFixtures.transcribingCard

    @Test fun figuresAndStageFollowLiveStateAndHiddenBatteryStaysHidden() {
        var progress by mutableStateOf(card)
        rule.setContent {
            CassiniTheme {
                ProvideFixedFormat(locale = Locale.ENGLISH) {
                    Box(Modifier.height(760.dp)) { TranscribingDetailsContent(progress, false, false, {}, {}) }
                }
            }
        }
        rule.onNodeWithText("62%").assertIsDisplayed()
        rule.onNodeWithText("29:52 of 48:13").assertIsDisplayed()
        rule.onNodeWithText("4,812").assertIsDisplayed()
        rule.onNodeWithContentDescription("Step 3 of 5: Words")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "in progress"))
        rule.runOnIdle {
            progress = progress.copy(stage = StageUi.SAVING, percent = 98, words = 5_000, battery = BatteryUi.Hidden)
        }
        rule.onNodeWithText("98%").assertIsDisplayed()
        rule.onNodeWithText("5,000").assertIsDisplayed()
        rule.onNodeWithText("Battery at finish").assertDoesNotExist()
        rule.onNodeWithContentDescription("Step 5 of 5: Saving")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "in progress"))
    }

    @Test
    @Config(qualifiers = "+pt-rPT")
    fun spokenPortugueseStagesUseTheVisibleCatalogLabels() {
        rule.setContent {
            CassiniTheme {
                ProvideFixedFormat(locale = Locale.forLanguageTag("pt-PT")) {
                    TranscribingDetailsContent(card, false, false, {}, {})
                }
            }
        }
        rule.onNodeWithContentDescription("Etapa 3 de 5: Palavras").assertExists()
        rule.onNodeWithContentDescription("Etapa 4 de 5: Oradores e reconhecimento").assertExists()
        rule.onNodeWithContentDescription("Etapa 5 de 5: Gravação").assertExists()
        rule.onNodeWithContentDescription("Etapa 5 de 5: Armazenamento").assertDoesNotExist()
    }

    @Test fun downloadCancelMemoryNotificationAndBusyStopRemainReachableAtLargeText() {
        val actions = mutableListOf<BannerAction>()
        val progress = card.copy(
            lowMemory = true,
            download = DownloadUi(ModelPartUi.SPEECH, 46, 296_000_000, 640_000_000),
        )
        rule.setContent {
            CassiniTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    ProvideFixedFormat(locale = Locale.ENGLISH) {
                        Box(Modifier.height(480.dp)) {
                            TranscribingDetailsContent(progress, true, true, {}, { actions += it })
                        }
                    }
                }
            }
        }
        rule.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(listOf(BannerAction.CANCEL), actions) }
        rule.onNode(hasText("This phone is low on memory.", substring = true)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("transcribing-details-stop").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Notifications are off, so progress shows only here.").performScrollTo().assertIsDisplayed()
    }

    @Test fun theNativeDismissActionClosesTheSheet() {
        var open by mutableStateOf(true)
        rule.setContent {
            CassiniTheme {
                ProvideFixedFormat(locale = Locale.ENGLISH) {
                    if (open) TranscribingDetails(card, false, false, {}, {}, { open = false })
                }
            }
        }
        rule.onNodeWithTag("transcribing-details-content").assertIsDisplayed()
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).onFirst()
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        rule.onNodeWithTag("transcribing-details-content").assertDoesNotExist()
    }
}
