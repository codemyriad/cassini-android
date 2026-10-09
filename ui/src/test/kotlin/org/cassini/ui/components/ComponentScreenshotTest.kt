package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import org.cassini.ui.BoardTest
import org.cassini.ui.banner.BannerArgs
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.banner.BannerSpec
import org.cassini.ui.banner.boardArgs
import org.cassini.ui.format.DayLabel
import org.cassini.ui.format.ModelPartUi
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph
import org.cassini.ui.theme.LightColors
import org.cassini.ui.theme.SpeakerTone
import org.cassini.ui.theme.Tone
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The batch-1 components in all their tones and states, one 390 dp strip per group and theme (docs/design/ui.md
 * section 10.2): components/{chrome,rows,controls,settings,banners}-{light,dark}.png. The strips are taller than the
 * phone, so the window is 3200 dp high; the capture is the content's own height.
 */
@Config(qualifiers = "+h3200dp")
class ComponentScreenshotTest : BoardTest() {
    @Test fun chromeLight() = board("components", "chrome-light") { ChromeStrip() }

    @Test
    @Config(qualifiers = "+night")
    fun chromeDark() = board("components", "chrome-dark", dark = true) { ChromeStrip() }

    @Test fun rowsLight() = board("components", "rows-light") { RowsStrip() }

    @Test
    @Config(qualifiers = "+night")
    fun rowsDark() = board("components", "rows-dark", dark = true) { RowsStrip() }

    @Test fun controlsLight() = board("components", "controls-light") { ControlsStrip() }

    @Test
    @Config(qualifiers = "+night")
    fun controlsDark() = board("components", "controls-dark", dark = true) { ControlsStrip() }

    @Test fun settingsLight() = board("components", "settings-light") { SettingsStrip() }

    @Test
    @Config(qualifiers = "+night")
    fun settingsDark() = board("components", "settings-dark", dark = true) { SettingsStrip() }

    @Test fun bannersLight() = board("components", "banners-light") { BannersStrip() }

    @Test
    @Config(qualifiers = "+night")
    fun bannersDark() = board("components", "banners-dark", dark = true) { BannersStrip() }

    /** Top bars, search, privacy lines, the Library cards and the bottom bar. */
    @Composable
    private fun ChromeStrip() = Strip {
        TopBars()
        SearchAndPrivacy()
        CardsAndBottomBar()
    }

    @Composable
    private fun TopBars() {
        Label("LibraryTopBar")
        LibraryTopBar(onTrash = {}, onSettings = {})
        Label("CassiniTopBar: Screen, Note with subtitle, Read, Trash with Empty")
        CassiniTopBar(title = "Settings", onBack = {}, backLabel = "Back to library")
        CassiniTopBar(
            title = "Weekly product sync",
            onBack = {},
            backLabel = "Back to library",
            style = TopBarStyle.Note,
            subtitle = "Today, 10:02 · 48:13",
        ) {
            IconButton44(Glyph.More, "More actions", onClick = {}, stroke = 0f)
        }
        CassiniTopBar(
            title = "Call with supplier",
            onBack = {},
            backLabel = "Back to library",
            style = TopBarStyle.Read,
        ) {
            IconButton44(Glyph.Search, "Search in transcript", onClick = {}, iconSize = 21.dp)
            IconButton44(Glyph.More, "Share and more", onClick = {}, stroke = 0f)
        }
        CassiniTopBar(title = "Trash", onBack = {}, backLabel = "Back to library") {
            DestructiveTextButton("Empty", onClick = {}, size = DestructiveTextButtonSize.Header)
        }
    }

    @Composable
    private fun SearchAndPrivacy() {
        Label("PillSearchField: empty, with a query, compact; PrivacyLine")
        Padded {
            PillSearchField(query = "", onQueryChange = {}, placeholder = "Search titles and what was said")
            PillSearchField(query = "consegna", onQueryChange = {}, placeholder = "Search titles and what was said")
            PillSearchField(query = "giovedì", onQueryChange = {}, placeholder = "Search in transcript", compact = true)
        }
        Box(Modifier.padding(horizontal = 16.dp)) {
            PrivacyLine("Transcribed on this phone. Audio never leaves it.")
        }
        PrivacyLine(
            "Voiceprints stay on this phone. They are never uploaded or written into shared files — only the names " +
                "you choose are.",
            style = PrivacyLineStyle.SavedVoices,
        )
    }

    @Composable
    private fun CardsAndBottomBar() {
        Label("RecordingBannerCard (recording, paused); ProgressBannerCard (62 %, no track)")
        Padded {
            RecordingBannerCard("Weekly product sync", elapsedMs = 1_447_000, paused = false, onClick = {})
            RecordingBannerCard("Weekly product sync", elapsedMs = 1_447_000, paused = true, onClick = {})
            ProgressBannerCard(
                title = "Transcribing Weekly product sync",
                percent = 62,
                progress = 0.62f,
                line = "About 6–9 min left · 1 more in queue",
                onClick = {},
            )
            ProgressBannerCard(
                title = "Getting ready to transcribe Interview — Marta, a note with a long title",
                percent = null,
                progress = null,
                line = "Finishing the previous note…",
                onClick = {},
            )
        }
        Label("LibraryBottomBar (idle, recording)")
        LibraryBottomBar(onImport = {}, onRecord = {})
        LibraryBottomBar(onImport = {}, onRecord = {}, recordingElapsedMs = 1_447_000)
    }

    /** Day headers and a row in every status, including a search result. */
    @Composable
    private fun RowsStrip() = Strip {
        DayHeader("Today", first = true)
        RowList(ComponentFixtures.boardRows.take(2))
        DayHeader("Yesterday")
        RowList(ComponentFixtures.boardRows.drop(2))
        DayHeader("Other statuses")
        RowList(ComponentFixtures.otherRows)
    }

    @Composable
    private fun RowList(rows: List<Pair<NoteRowUi, DayLabel>>) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            rows.forEach { (row, day) -> NoteRow(row, day, onAction = {}) }
        }
    }

    /** Chips, tags, every button in its states, the segmented control, avatars and tracks. */
    @Composable
    private fun ControlsStrip() = Strip {
        ChipsAndTags()
        Buttons()
        OtherControls()
    }

    @Composable
    private fun ChipsAndTags() {
        Label("StatusChip")
        Wrap {
            StatusChip("Recording", Tone.Error, dot = true)
            StatusChip("Transcribing", Tone.Info)
            StatusChip("Queued", Tone.Outline)
            StatusChip("Paused at 12:40", Tone.Warn)
            StatusChip("Couldn’t transcribe", Tone.Error)
            StatusChip("Transcribed", Tone.Ok)
            StatusChip("Audio only", Tone.Outline)
        }
        Label("Tags: Likely, dashed, auto; FormatBadge on rec.bg")
        Wrap {
            LikelyTag("Likely")
            DashedTag("Words may still change")
            AutoBadge("auto")
            Box(
                Modifier
                    .background(CassiniTheme.recording.bg)
                    .padding(6.dp),
            ) {
                FormatBadge("Opus 48 kHz")
            }
        }
        Label("LargeChip, VersionChip, FollowChip")
        Wrap {
            LargeChip("Verified", Tone.Ok)
            LargeChip("Not verified", Tone.Warn)
            LargeChip("Can’t read transcript", Tone.Error)
            VersionChip("Version 2 of 2 · speakers merged", onClick = {})
            FollowChip("Follow playback", onClick = {})
        }
    }

    @Composable
    private fun Buttons() {
        Label("PrimaryButton: large; regular, disabled, busy, blocked")
        Padded {
            PrimaryButton("Download and transcribe", onClick = {}, size = PrimaryButtonSize.Large)
            PrimaryButton("Download and transcribe", onClick = {}, size = PrimaryButtonSize.Large, busy = true)
        }
        Wrap {
            PrimaryButton("Save name", onClick = {})
            PrimaryButton("Save name", onClick = {}, enabled = false)
            PrimaryButton("Save name", onClick = {}, busy = true)
            PrimaryButton("Save name", onClick = {}, blockedReason = "Answer the question above first")
        }
        Label("InkButton small, medium; disabled, busy")
        Wrap {
            InkButton("Resume", onClick = {})
            InkButton("Same person", onClick = {}, size = InkButtonSize.Medium)
            InkButton("Yes, Luca", onClick = {}, padding = 16.dp)
            InkButton("Resume", onClick = {}, enabled = false)
            InkButton("Resume", onClick = {}, busy = true)
        }
        Label("SecondaryButton: large, regular, block, medium, medium plain, small; disabled, busy")
        Wrap {
            SecondaryButton("Import audio", onClick = {}, size = SecondaryButtonSize.Large, glyph = Glyph.Import)
            SecondaryButton("Cancel", onClick = {}, size = SecondaryButtonSize.Regular)
        }
        Padded {
            SecondaryButton("Stop and keep progress", onClick = {}, size = SecondaryButtonSize.Block)
        }
        Wrap {
            SecondaryButton("Restore", onClick = {}, size = SecondaryButtonSize.Medium)
            SecondaryButton("Someone else", onClick = {}, size = SecondaryButtonSize.MediumPlain)
            SecondaryButton("Start over", onClick = {})
            SecondaryButton("Start over", onClick = {}, enabled = false)
            SecondaryButton("Start over", onClick = {}, busy = true)
        }
        Label("TextButton regular, tall, disabled; DestructiveTextButton header, small; DestructiveButton")
        Wrap {
            TextButton("Merge into…", onClick = {})
            TextButton("Not now — keep the audio", onClick = {}, size = TextButtonSize.Tall)
            TextButton("Merge into…", onClick = {}, enabled = false)
            DestructiveTextButton("Empty", onClick = {}, size = DestructiveTextButtonSize.Header)
            DestructiveTextButton("Delete now", onClick = {})
            DestructiveButton("Delete", onClick = {})
            DestructiveButton("Delete", onClick = {}, busy = true)
        }
        Label("DestructiveOutlineButton; disabled")
        Padded {
            DestructiveOutlineButton("Forget everyone…", onClick = {})
            DestructiveOutlineButton("Forget everyone…", onClick = {}, enabled = false)
        }
    }

    @Composable
    private fun OtherControls() {
        Label("RecordFab (idle, recording)")
        Wrap {
            RecordFab(onClick = {})
            RecordFab(onClick = {}, recordingElapsedMs = 1_447_000)
        }
        Label("SegmentedControl (each selection)")
        Padded {
            val options = persistentListOf("Phone", "English", "Italiano")
            SegmentedControl(options, selectedIndex = 0, onSelect = {})
            SegmentedControl(options, selectedIndex = 1, onSelect = {})
            SegmentedControl(options, selectedIndex = 2, onSelect = {})
        }
        Label("Avatar (blue, teal, amber, violet; 32 dp)")
        Wrap {
            Avatar("G", SpeakerTone.Blue)
            Avatar("P", SpeakerTone.Teal)
            Avatar("3", SpeakerTone.Amber)
            Avatar("L", SpeakerTone.Violet)
            Avatar("A", SpeakerTone.Blue, size = 32.dp)
        }
        Label("LinearTrack thin and thick: 0, 62 %, 100 %")
        Padded {
            LinearTrack(0f)
            LinearTrack(0.62f)
            LinearTrack(1f)
            LinearTrack(0f, height = TrackHeight.Thick)
            LinearTrack(0.62f, height = TrackHeight.Thick)
            LinearTrack(1f, height = TrackHeight.Thick)
        }
    }

    /** Section headers, group cards with every trailing kind, the helper and the About card. */
    @Composable
    private fun SettingsStrip() = Strip {
        SectionHeader("App language", first = true)
        SegmentedControl(
            persistentListOf("Phone", "English", "Italiano"),
            selectedIndex = 0,
            onSelect = {},
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        HelperText("Speech is recognised in any language, automatically.")
        SectionHeader("On-device models")
        GroupCard(Modifier.padding(horizontal = 16.dp)) {
            SettingsRow(
                "Speech recognition",
                subtitle = "INT8 · 640 MB",
                trailing = SettingsTrailing.Status("Ready", Tone.Ok),
            )
            SettingsRow(
                "Telling speakers apart",
                subtitle = "62 MB",
                trailing = SettingsTrailing.Status("Downloading 46%", Tone.Info),
            )
            SettingsRow(
                "Recognising saved voices",
                subtitle = "28 MB",
                trailing = SettingsTrailing.Status("Downloads with next transcription", Tone.Warn),
            )
            SettingsRow(
                "Speech recognition",
                subtitle = "INT8 · 640 MB",
                trailing = SettingsTrailing.Status("Download failed — tries again next time", Tone.Error),
            )
        }
        HelperText("Transcription runs on this phone. Audio never leaves it.")
        SectionHeader("Privacy")
        GroupCard(Modifier.padding(horizontal = 16.dp)) {
            SettingsRow("Saved voices", subtitle = "4 people", trailing = SettingsTrailing.Chevron, onClick = {})
            SettingsRow("Trash", subtitle = "2 notes", trailing = SettingsTrailing.Chevron, onClick = {})
        }
        SectionHeader("About")
        AboutCard(version = "0.1.0", onSourceClick = {}, modifier = Modifier.padding(horizontal = 16.dp))
        SectionHeader("Bordered group (board 05) and a single row")
        GroupCard(Modifier.padding(horizontal = 16.dp), bordered = true) {
            SettingsRow("Speech recognition", trailing = SettingsTrailing.Status("640 MB", Tone.Plain))
            SettingsRow("Telling speakers apart", trailing = SettingsTrailing.Status("62 MB", Tone.Plain))
        }
        GroupCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), shape = CassiniTheme.shapes.list) {
            SettingsRow("Source code on GitHub")
        }
    }

    /** One banner per tone with 0, 1 and 2 actions, the dismissible kinds, a track and the light set on rec.bg. */
    @Composable
    private fun BannersStrip() = Strip {
        Label("Tones: ok, info, warn, error, plain; 0–2 actions")
        Padded(gap = 12) {
            for (kind in BANNER_KINDS) Banner(BannerSpec(kind, boardArgs(kind)), onAction = {})
        }
        Label("Dismissible (close button), announce, optional track")
        Padded(gap = 12) {
            Banner(BannerSpec(BannerKind.RECOVERED, boardArgs(BannerKind.RECOVERED)), onAction = {}, onDismiss = {})
            Banner(
                BannerSpec(BannerKind.NO_ENCODER, BannerArgs(m4a = true), announce = true),
                onAction = {},
                onDismiss = {},
            )
            Banner(
                BannerSpec(
                    BannerKind.DL_DOWNLOADING,
                    BannerArgs(percent = 46, bytes = 310_378_496, bytes2 = 671_088_640, part = ModelPartUi.SPEECH),
                ),
                onAction = {},
                progress = 0.46f,
            )
            BannerCard(tone = Tone.Plain, title = "Queued", body = "2 ahead of this note.") {
                SecondaryButton("Remove from queue", onClick = {})
            }
        }
        Label("Recording screen: light tones on rec.bg")
        Box(
            Modifier
                .background(CassiniTheme.recording.bg)
                .padding(16.dp),
        ) {
            Banner(
                BannerSpec(BannerKind.REC_PAUSED_OTHER_APP, boardArgs(BannerKind.REC_PAUSED_OTHER_APP)),
                onAction = {},
                colors = LightColors,
            )
        }
    }

    @Composable
    private fun Wrap(content: @Composable () -> Unit) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }

    private companion object {
        val BANNER_KINDS = listOf(
            BannerKind.TRUST_VERIFIED,
            BannerKind.DL_CHECKING,
            BannerKind.TX_PAUSED,
            BannerKind.TX_MEMORY,
            BannerKind.LIB_EMPTY,
            BannerKind.LIB_NO_MATCH,
        )
    }
}
