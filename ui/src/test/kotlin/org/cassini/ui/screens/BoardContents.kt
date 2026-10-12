// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package org.cassini.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.cassini.ui.banner.BannerGroup
import org.cassini.ui.components.Banner
import org.cassini.ui.components.SheetBoardFrame
import org.cassini.ui.fixtures.BoardFixtures
import org.cassini.ui.library.LibraryScreen
import org.cassini.ui.models.ModelDownloadScreen
import org.cassini.ui.note.NameSpeakerSheetContent
import org.cassini.ui.note.NoteActionsSheetContent
import org.cassini.ui.note.NoteScreen
import org.cassini.ui.recording.RecordingScreen
import org.cassini.ui.settings.SettingsScreen
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.trash.TrashScreen
import org.cassini.ui.voices.SavedVoicesScreen
import java.util.Locale

/** The design slugs of the ten phone boards (docs/design/ui.md section 10.2), in board order. */
internal val PHONE_BOARDS: List<String> = listOf(
    "00-library",
    "01-recording",
    "02-transcribing",
    "03-read-and-listen",
    "04-name-a-speaker",
    "05-model-download",
    "06-note-actions-and-export",
    "07-saved-voices",
    "08-settings",
    "09-trash",
)

/**
 * The screen of the phone board [slug] with its fixture. The light, dark, Italian and font-scale tests all draw a
 * board through this function, so the four sets of goldens show the same content. The sheets of boards 04 and 06
 * render inline in `SheetBoardFrame` over the boards' plain scrim.
 */
@Composable
internal fun BoardContent(slug: String) {
    when (slug) {
        "00-library" -> LibraryScreen(BoardFixtures.library, onEvent = {})
        "01-recording" -> RecordingScreen(BoardFixtures.recording, onEvent = {})
        "02-transcribing" -> NoteScreen(BoardFixtures.transcribing, onEvent = {})
        "03-read-and-listen" -> NoteScreen(BoardFixtures.readAndListen, onEvent = {})
        "04-name-a-speaker" -> SheetBoardFrame { NameSpeakerSheetContent(BoardFixtures.nameSpeaker, onEvent = {}) }
        "05-model-download" -> ModelDownloadScreen(BoardFixtures.modelDownload, onEvent = {})
        "06-note-actions-and-export" -> SheetBoardFrame {
            NoteActionsSheetContent(BoardFixtures.noteActions, onAction = {})
        }
        "07-saved-voices" -> SavedVoicesScreen(BoardFixtures.savedVoices, onEvent = {})
        "08-settings" -> SettingsScreen(BoardFixtures.settings, onEvent = {})
        "09-trash" -> TrashScreen(BoardFixtures.trash, onEvent = {})
        else -> error("no phone board $slug")
    }
}

/** The test tag of the States column node that `StatesBoardTest` captures. */
internal const val STATES_COLUMN_TAG = "states-column"

/** The five columns of board 10 in board order: golden `10-states-col<n>` is column n. */
internal val STATES_COLUMNS: List<BannerGroup> = listOf(
    BannerGroup.TRUST,
    BannerGroup.RECORDING,
    BannerGroup.TRANSCRIPTION,
    BannerGroup.DOWNLOAD,
    BannerGroup.LIBRARY,
)

/**
 * One column of board 10 (docs/design/ui.md section 10.2), without outer margin: the full width (358 dp under the
 * test's qualifiers) on boardBg; the heading in `overline`, text3, uppercase, with its 4 dp bottom margin; then the
 * banners with 12 dp gaps (also 12 dp below the heading's margin). The height is the content height. The
 * dismissible kinds have no close button, as on the board (ui.md section 11, item 10).
 */
@Composable
internal fun StatesColumn(group: BannerGroup) {
    val c = CassiniTheme.colors
    Column(
        Modifier
            .testTag(STATES_COLUMN_TAG)
            .fillMaxWidth()
            .background(c.boardBg),
    ) {
        Text(
            text = BoardFixtures.stateHeadings.getValue(group).uppercase(Locale.ENGLISH),
            style = CassiniTheme.type.overline,
            color = c.text3,
            modifier = Modifier
                .padding(bottom = 4.dp)
                .semantics { heading() },
        )
        BoardFixtures.states.getValue(group).forEach { spec ->
            Spacer(Modifier.height(12.dp))
            Banner(spec, onAction = {})
        }
    }
}
