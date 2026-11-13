@file:Suppress("FunctionNaming") // Composable position effect.

package io.codemyriad.cassini.ui.note

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/** Capture the old provider's position before adding or removing the metadata item. */
@Composable
internal fun MeetingFindPosition(searching: Boolean, reading: Boolean, list: LazyListState, firstTurn: String?) {
    val history = remember { FindPositionHistory(searching) }
    val reset = remember(searching, reading) {
        val changed = history.searching != searching
        history.searching = searching
        val first = list.layoutInfo.visibleItemsInfo.firstOrNull()?.key
        if (changed && searching) history.openedAtHeader = first == Int.MIN_VALUE || first == null
        reading && changed && if (searching) {
            history.openedAtHeader
        } else {
            history.openedAtHeader && (first == firstTurn || first == null) && list.firstVisibleItemScrollOffset == 0
        }
    }
    LaunchedEffect(searching, reading) {
        if (reset) list.scrollToItem(0, 0)
    }
}

private class FindPositionHistory(var searching: Boolean) {
    // Meetings opened directly from Library search also restore metadata when leaving their first result.
    var openedAtHeader = true
}
