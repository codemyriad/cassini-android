@file:Suppress("FunctionNaming", "MagicNumber") // Compose names and standard chip geometry.

package io.codemyriad.cassini.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.CassiniMenu
import io.codemyriad.cassini.ui.components.CassiniMenuItem
import io.codemyriad.cassini.ui.theme.CassiniTheme

/** D14 menus; scrolling retains every control at large font sizes or with long stored speaker names. */
@Composable
internal fun SearchFiltersBar(filters: SearchFiltersUi, onEvent: (LibraryEvent) -> Unit) {
    var speakerMenu by remember { mutableStateOf(false) }
    var dateMenu by remember { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            val name = filters.speaker
            SearchChip(
                if (name == null) {
                    stringResource(R.string.ui_search_filter_speaker_none)
                } else {
                    stringResource(R.string.ui_search_filter_speaker).replace("{name}", name)
                },
                selected = name != null,
                onClick = { speakerMenu = true },
            )
            CassiniMenu(speakerMenu, onDismissRequest = { speakerMenu = false }) {
                CassiniMenuItem(stringResource(R.string.ui_search_filter_any_speaker), onClick = {
                    speakerMenu = false
                    onEvent(LibraryEvent.FilterSpeaker(null))
                })
                for (speaker in filters.speakers) {
                    CassiniMenuItem(speaker, onClick = {
                        speakerMenu = false
                        onEvent(LibraryEvent.FilterSpeaker(speaker))
                    })
                }
            }
        }
        Box {
            SearchChip(dateLabel(filters.date), filters.date != SearchDateUi.ANY, onClick = { dateMenu = true })
            CassiniMenu(dateMenu, onDismissRequest = { dateMenu = false }) {
                for (date in SearchDateUi.entries) {
                    CassiniMenuItem(dateLabel(date), onClick = {
                        dateMenu = false
                        onEvent(LibraryEvent.FilterDate(date))
                    })
                }
            }
        }
        SearchChip(
            stringResource(R.string.ui_search_filter_recorded_by_me),
            filters.recordedByMe,
            onClick = { onEvent(LibraryEvent.ToggleRecordedByMe) },
        )
    }
}

@Composable
private fun SearchChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = CassiniTheme.colors
    FilterChip(
        selected,
        onClick,
        label = { Text(text, style = CassiniTheme.type.fieldLabel) },
        modifier = Modifier.heightIn(min = 48.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surface,
            labelColor = colors.ink,
            selectedContainerColor = colors.accentFill,
            selectedLabelColor = colors.onAccentFill,
        ),
    )
}

@Composable
private fun dateLabel(date: SearchDateUi): String = stringResource(
    when (date) {
        SearchDateUi.ANY -> R.string.ui_search_filter_any_date
        SearchDateUi.TODAY -> R.string.ui_search_filter_date_today
        SearchDateUi.WEEK -> R.string.ui_search_filter_date_week
        SearchDateUi.MONTH -> R.string.ui_search_filter_date_month
    },
)
