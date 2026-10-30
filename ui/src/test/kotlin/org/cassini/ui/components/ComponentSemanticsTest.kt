package org.cassini.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getFirstLinkBounds
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import org.cassini.ui.banner.BannerArgs
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.banner.BannerSpec
import org.cassini.ui.format.DayLabel
import org.cassini.ui.format.FIXED_ZONE
import org.cassini.ui.format.ProvideFixedFormat
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph
import org.cassini.ui.theme.SpeakerTone
import org.cassini.ui.theme.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Semantics of the batch-1 components (docs/design/ui.md sections 5, 6 and 11): touch areas of at least 48 dp,
 * the Library row's inline suffix, description and custom actions, banner headings and live regions, the segmented
 * control's radio group and the blocked primary button.
 *
 * Accessibility Test Framework checks (`enableAccessibilityChecks()`) do not run under Robolectric (P2 finding,
 * see ScaffoldScreenshotTest); the P10 device flow tests run them on the emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class ComponentSemanticsTest {
    @get:Rule val rule = createComposeRule()

    private fun setThemed(content: @Composable () -> Unit) {
        rule.setContent {
            CassiniTheme(darkTheme = false) {
                ProvideFixedFormat(locale = Locale.ENGLISH, is24Hour = true, zone = FIXED_ZONE, content = content)
            }
        }
    }

    private fun SemanticsNodeInteraction.assertTouchAtLeast48() = assertTouchAtLeast48(rule.density)

    @Test fun everyControlHasA48dpTouchArea() {
        setThemed {
            // Everything fits in the 844 dp window: touch bounds are clipped to it.
            FlowRow(Modifier.width(390.dp)) {
                Banner(RECOVERED, onAction = {}, onDismiss = {})
                PillSearchField(query = "consegna", onQueryChange = {}, placeholder = "Search")
                SegmentedControl(persistentListOf("Phone", "English", "Italiano"), selectedIndex = 0, onSelect = {})
                IconButton44(Glyph.Back, "Back to library", onClick = {})
                InkButton("Ink small", onClick = {})
                InkButton("Ink medium", onClick = {}, size = InkButtonSize.Medium)
                SecondaryButton("Secondary small", onClick = {})
                SecondaryButton("Secondary medium", onClick = {}, size = SecondaryButtonSize.Medium)
                SecondaryButton("Secondary block", onClick = {}, size = SecondaryButtonSize.Block)
                SecondaryButton("Cancel", onClick = {}, size = SecondaryButtonSize.Regular)
                TextButton("Merge into…", onClick = {})
                DestructiveTextButton("Delete now", onClick = {})
                DestructiveTextButton("Empty", onClick = {}, size = DestructiveTextButtonSize.Header)
                PrimaryButton("Save name", onClick = {})
                DestructiveButton("Delete", onClick = {})
                VersionChip("Version 2 of 2", onClick = {})
                FollowChip("Follow playback", onClick = {})
                LargeChip("Not verified", Tone.Warn, onClick = {}, onClickLabel = "Show details")
                RecordFab(onClick = {})
            }
        }
        val labels = listOf(
            "Ink small", "Ink medium", "Secondary small", "Secondary medium", "Secondary block", "Cancel",
            "Merge into…", "Delete now", "Empty", "Save name", "Delete", "Version 2 of 2", "Follow playback",
            "Phone", "English", "Italiano", "Open", "Not verified",
        )
        for (label in labels) rule.onNodeWithText(label).assertTouchAtLeast48()
        for (description in listOf("Back to library", "Clear search", "Dismiss", "Record a new note")) {
            rule.onNodeWithContentDescription(description).assertTouchAtLeast48()
        }
        rule.onNodeWithContentDescription("Record a new note").assertHasClickAction()
        val chip = rule.onNodeWithText("Not verified").fetchSemanticsNode().config
        assertEquals("Show details", chip[SemanticsActions.OnClick].label)
        assertEquals(Role.Button, chip[SemanticsProperties.Role])
    }

    @Test fun aLargeChipWithoutAClickIsAPlainLabel() {
        setThemed { LargeChip("Verified", Tone.Ok) }
        rule.onNodeWithText("Verified").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun theAvatarIsHiddenSoTheRowReadsOnlyTheName() {
        setThemed {
            Row {
                Avatar("G", SpeakerTone.Blue)
                Text("Giulia")
            }
        }
        rule.onAllNodesWithText("G").assertCountEquals(0)
        rule.onNodeWithText("Giulia").assertExists()
    }

    @Test fun rowSuffixKeepsItsLayoutAndTakesTapsWithin48dp() {
        val actions = mutableListOf<NoteRowAction>()
        setThemed {
            Column(Modifier.width(390.dp)) {
                NoteRow(ComponentFixtures.board, DayLabel.Yesterday, onAction = { actions += it })
            }
        }
        val suffix = rule.onNodeWithContentDescription("Resume transcribing Board review prep")
        suffix.assertExtendedTouchAtLeast48(rule.density)
        // The text keeps its board size: the 48 dp comes from extended hit testing, not from layout.
        val bounds = suffix.getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("suffix grew to $height", height < 24.dp)
        suffix.performTouchInput { click(Offset(centerX, centerY - 14.dp.toPx())) }
        rule.runOnIdle { assertEquals(listOf(NoteRowAction.RESUME), actions) }
    }

    @Test fun rowIsOneDescribedNodeWithClickLongClickAndCustomActions() {
        val actions = mutableListOf<NoteRowAction>()
        setThemed {
            Column(Modifier.width(390.dp)) {
                NoteRow(ComponentFixtures.weekly, DayLabel.Today, onAction = { actions += it })
                NoteRow(ComponentFixtures.board, DayLabel.Yesterday, onAction = { actions += it })
            }
        }
        val weekly = rule.onNode(
            hasContentDescription("Weekly product sync, 48 minutes 13 seconds, today 10:02, transcribing"),
        )
        assertEquals(listOf("Rename", "Move to trash"), weekly.customActionLabels())
        weekly.performClick()
        weekly.performTouchInput { longClick() }
        rule.runOnIdle { assertEquals(listOf(NoteRowAction.OPEN, NoteRowAction.LONG_PRESS), actions) }

        actions.clear()
        val board = rule.onNode(
            hasContentDescription("Board review prep, 34 minutes 12 seconds, yesterday 11:00, paused at 12:40"),
        )
        assertEquals(listOf("Resume", "Rename", "Move to trash"), board.customActionLabels())
        val custom = board.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        rule.runOnIdle { custom.forEach { it.action() } }
        assertEquals(listOf(NoteRowAction.RESUME, NoteRowAction.RENAME, NoteRowAction.TRASH), actions)
    }

    /** The note being recorded cannot move to trash (features.md section 6): its row offers only Rename. */
    @Test fun theRecordingRowHasNoTrashAction() {
        val (recording, day) = ComponentFixtures.otherRows.first { it.first.id == "rec" }
        setThemed {
            Column(Modifier.width(390.dp)) { NoteRow(recording, day, onAction = {}) }
        }
        val row = rule.onNode(hasContentDescription("Standup,", substring = true))
        assertEquals(listOf("Rename"), row.customActionLabels())
    }

    @Test fun aSearchResultRowSpeaksItsSuffixAndPreview() {
        val (result, day) = ComponentFixtures.otherRows.first { it.first.id == "result" }
        setThemed {
            Column(Modifier.width(390.dp)) {
                NoteRow(result, day, onAction = {})
                NoteRow(ComponentFixtures.otherRows.first { it.first.id == "silent" }.first, day, onAction = {})
            }
        }
        rule.onNode(
            hasContentDescription(
                "Call with supplier, 23 minutes 50 seconds, yesterday 15:10, transcribed, 3 speakers, " +
                    "quindi la consegna slitta a giovedì, but the price stays as agreed",
            ),
        ).assertExists()
        rule.onNode(hasContentDescription("no speech", substring = true)).assertExists()
    }

    @Test fun bannerTitleIsAHeadingAndAnnounceMakesALiveRegion() {
        var dismissed = 0
        setThemed {
            Column(Modifier.width(390.dp)) {
                Banner(
                    BannerSpec(BannerKind.TX_PAUSED, BannerArgs(ms1 = 760_000, ms2 = 2_052_000), announce = true),
                    onAction = {},
                    onDismiss = { dismissed++ },
                )
                Banner(RECOVERED, onAction = {}, onDismiss = { dismissed++ })
            }
        }
        rule.onNodeWithText("Paused at 12:40 of 34:12").assert(isHeading())
        rule.onNodeWithText("1 recording recovered").assert(isHeading())
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assertCountEquals(1)
        // TX_PAUSED is not dismissible: only RECOVERED shows the close button.
        rule.onAllNodesWithContentDescription("Dismiss").assertCountEquals(1)
        rule.onNodeWithContentDescription("Dismiss").performClick()
        rule.runOnIdle { assertEquals(1, dismissed) }
        rule.onAllNodesWithText("Resume").assertCountEquals(1)
        rule.onAllNodesWithText("Start over").assertCountEquals(1)
    }

    @Test fun segmentedControlIsASelectableGroupOfRadioButtons() {
        var selected by mutableIntStateOf(0)
        setThemed {
            SegmentedControl(
                persistentListOf("Phone", "English", "Italiano"),
                selectedIndex = selected,
                onSelect = { selected = it },
            )
        }
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertExists()
        for (label in listOf("Phone", "English", "Italiano")) {
            rule.onNodeWithText(label).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }
        rule.onNodeWithText("Phone").assertIsSelected()
        rule.onNodeWithText("English").assertIsNotSelected().performClick()
        rule.onNodeWithText("English").assertIsSelected()
        rule.onNodeWithText("Phone").assertIsNotSelected()
    }

    @Test fun blockedPrimaryButtonLooksEnabledAndReportsTheReason() {
        var clicks = 0
        var blocked = 0
        setThemed {
            Column {
                PrimaryButton(
                    "Save name",
                    onClick = { clicks++ },
                    blockedReason = BLOCKED_REASON,
                    onBlocked = { blocked++ },
                )
                PrimaryButton("Disabled", onClick = { clicks++ }, enabled = false)
                PrimaryButton("Busy", onClick = { clicks++ }, busy = true)
            }
        }
        rule.onNodeWithText("Save name")
            .assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, BLOCKED_REASON))
            .performClick()
        rule.onNodeWithText("Disabled").assertIsNotEnabled()
        rule.onNodeWithText("Busy").assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(0, clicks)
            assertEquals(1, blocked)
        }
    }

    @Test fun progressBannerDescriptionMovesInFivePercentSteps() {
        var percent by mutableIntStateOf(62)
        var line by mutableStateOf("About 6–9 min left")
        setThemed {
            ProgressBannerCard(
                title = "Transcribing Weekly product sync",
                percent = percent,
                progress = percent / 100f,
                line = line,
                onClick = {},
            )
        }
        val first = "Transcribing Weekly product sync, 60%, About 6–9 min left"
        val card = rule.onNode(hasContentDescription(first))
        card.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertEquals("Open note", card.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        rule.runOnIdle {
            percent = 64
            line = "About 5–8 min left"
        }
        // The live region's node holds no visible text, so a new percent or time-left line changes nothing in it.
        val node = rule.onNode(hasContentDescription(first)).fetchSemanticsNode()
        assertTrue("visible text in the live region: ${node.config}", SemanticsProperties.Text !in node.config)
        rule.onNodeWithText("64%").assertDoesNotExist()
        rule.onNodeWithText("About 5–8 min left").assertDoesNotExist()
        rule.runOnIdle { percent = 65 }
        rule.onNode(hasContentDescription("Transcribing Weekly product sync, 65%, About 5–8 min left")).assertExists()
    }

    @Test fun aStandaloneDownloadBannerOpensSettings() {
        setThemed {
            ProgressBannerCard(
                title = "Downloading speech recognition",
                percent = 46,
                progress = 0.46f,
                line = "296 of 640 MB",
                onClick = {},
                onClickLabel = "Open settings",
            )
        }
        val card = rule.onNode(hasClickAction()).fetchSemanticsNode()
        assertEquals("Open settings", card.config[SemanticsActions.OnClick].label)
    }

    @Test fun aSettingsRowWithoutAClickIsStillOneNode() {
        setThemed {
            GroupCard {
                SettingsRow(
                    "Speech recognition",
                    subtitle = "INT8 · 640 MB",
                    trailing = SettingsTrailing.Status("Ready", Tone.Ok),
                )
                SettingsRow("Trash", subtitle = "2 notes", trailing = SettingsTrailing.Chevron, onClick = {})
            }
        }
        val row = rule.onNodeWithText("Speech recognition").fetchSemanticsNode()
        assertEquals(
            listOf("Speech recognition", "INT8 · 640 MB", "Ready"),
            row.config[SemanticsProperties.Text].map { it.text },
        )
        rule.onAllNodesWithText("Ready").assertCountEquals(1)
        rule.onNodeWithText("Trash").assertHasClickAction()
    }

    @Test fun recordingCardSpeaksTheElapsedTimeAndItsAction() {
        var clicks = 0
        setThemed { RecordingBannerCard("Weekly product sync", 1_447_000, paused = false, onClick = { clicks++ }) }
        val card = rule.onNode(hasClickAction())
        card.assert(hasContentDescription("24 minutes 7 seconds"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertEquals("Return to recording", card.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        rule.onNodeWithText("24:07").assertDoesNotExist()
        card.performClick()
        rule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun aboutCardNamesTheProductAndItsVersion() {
        setThemed { AboutCard(version = "0.2.0", onSourceClick = {}) }
        rule.onNodeWithText("Cassini talk 0.2.0 ", substring = true).assertExists()
    }

    @Test fun aboutCardLinkOpensTheSource() {
        var opened = 0
        setThemed { AboutCard(version = "0.2.0", onSourceClick = { opened++ }) }
        val paragraph = rule.onNodeWithText("Source code and notices", substring = true)
        val link = checkNotNull(paragraph.getFirstLinkBounds()) { "no link in the About card" }
        paragraph.performTouchInput { click(link.center) }
        rule.runOnIdle { assertEquals(1, opened) }
    }

    private fun SemanticsNodeInteraction.customActionLabels(): List<String> =
        fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    private companion object {
        const val BLOCKED_REASON = "Answer the question above first"
        val RECOVERED = BannerSpec(BannerKind.RECOVERED, BannerArgs(count = 1, ms1 = 312_000))
    }
}
