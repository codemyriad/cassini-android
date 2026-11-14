@file:Suppress("FunctionNaming", "MagicNumber") // Compose names and standard chip geometry.

package io.codemyriad.cassini.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.theme.CassiniTheme

/**
 * The Library's filter row at rest (L-34, D99; PARITY Phase 2 §3): Material filter chips `All` / `Starred`, single
 * choice, in a group named `library.filters.a11y`. Placement is PROVISIONAL, pending Alex in D-1019.
 */
@Composable
internal fun LibraryFilterRow(filter: LibraryFilterUi, onEvent: (LibraryEvent) -> Unit, modifier: Modifier = Modifier) {
    val group = stringResource(R.string.ui_library_filters_a11y)
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .selectableGroup()
            .semantics { contentDescription = group }
            .testTag("library-filters"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (choice in LibraryFilterUi.entries) {
            SearchChip(
                stringResource(
                    when (choice) {
                        LibraryFilterUi.ALL -> R.string.ui_library_filter_all
                        LibraryFilterUi.STARRED -> R.string.ui_library_filter_starred
                    },
                ),
                selected = choice == filter,
                onClick = { onEvent(LibraryEvent.SelectLibraryFilter(choice)) },
            )
        }
    }
}

/** `library.starred.empty`: `Starred` is chosen and no note has a star. */
@Composable
internal fun StarredEmpty(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.ui_library_starred_empty),
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp).testTag("starred-empty"),
        style = CassiniTheme.type.heroBody,
        color = CassiniTheme.colors.text2,
        textAlign = TextAlign.Center,
    )
}
