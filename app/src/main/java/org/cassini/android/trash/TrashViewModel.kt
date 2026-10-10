package org.cassini.android.trash

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.cassini.android.library.LibraryMapping
import org.cassini.core.library.DayGroups
import org.cassini.core.library.TrashPolicy
import org.cassini.data.db.TrashRow
import org.cassini.ui.trash.TrashBackTo
import org.cassini.ui.trash.TrashDialog
import org.cassini.ui.trash.TrashEvent
import org.cassini.ui.trash.TrashItemUi
import org.cassini.ui.trash.TrashUiState
import java.time.Clock
import java.time.Instant

/**
 * Trash (board 09, features.md section 8; ui.md section 7). The cards come from `NoteRepository.trash()` (oldest
 * deletion first, so the soonest to expire is on top); "Deleted 2 Oct · 23 days left" uses [TrashPolicy] at the
 * [clock]'s time and zone. On open the notes whose 30 days are over are deleted first (architecture 4.10).
 *
 * Restore puts the note back (a queued note goes to the back of the queue). Delete now and Empty ask first; the open
 * dialog is in [saved]. [backTo] is the screen under Trash, for the back button's label.
 *
 * The "moved to trash" snackbar of board 09 is a fixture-only state (features.md section 8): the app shows it on the
 * Library, so here [TrashUiState.snackbar] stays null and Undo and the snackbar timeout have nothing to do.
 */
class TrashViewModel(
    private val saved: SavedStateHandle,
    private val ports: TrashPorts,
    private val backTo: TrashBackTo,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val effectChannel = Channel<Unit>(Channel.CONFLATED)

    /** Back was pressed: the route pops the screen. */
    val close: Flow<Unit> = effectChannel.receiveAsFlow()

    private val rows: Flow<List<TrashRow>> = ports.trash().catch { error ->
        Log.e(TAG, "Trash cannot be read", error)
        emit(emptyList())
    }

    /** Null until the first rows are read. */
    val state: StateFlow<TrashUiState?> = combine(rows, saved.getStateFlow<String?>(KEY_DIALOG, null), ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        guarded("delete the expired notes") { ports.purgeExpired(Instant.now(clock), clock.zone) }
    }

    fun onEvent(event: TrashEvent) {
        when (event) {
            TrashEvent.Back -> effectChannel.trySend(Unit)
            TrashEvent.Empty -> if (state.value?.items?.isNotEmpty() == true) saved[KEY_DIALOG] = EMPTY
            is TrashEvent.Restore -> guarded("restore a note") { ports.restore(event.id) }
            is TrashEvent.DeleteNow -> saved[KEY_DIALOG] = DELETE + event.id
            TrashEvent.ConfirmDialog -> confirm()
            TrashEvent.DismissDialog -> saved[KEY_DIALOG] = null
            // Fixture-only snackbar (board 09): the app never shows it here.
            TrashEvent.Undo, TrashEvent.SnackbarTimeout -> Unit
        }
    }

    private fun confirm() {
        val dialog = saved.get<String>(KEY_DIALOG) ?: return
        saved[KEY_DIALOG] = null
        if (dialog == EMPTY) {
            guarded("empty the trash") { ports.emptyTrash() }
        } else {
            guarded("delete a note") { ports.deleteNow(dialog.removePrefix(DELETE)) }
        }
    }

    private fun build(rows: List<TrashRow>, dialog: String?): TrashUiState {
        val now = Instant.now(clock)
        val zone = clock.zone
        val items = rows.map { row ->
            TrashItemUi(
                id = row.id,
                title = row.title,
                durationMs = row.durationMs,
                deletedOn = LibraryMapping.dayLabel(DayGroups.key(row.trashedAt, now, zone)),
                daysLeft = TrashPolicy.daysLeft(row.trashedAt, now, zone),
            )
        }
        return TrashUiState(items = items.toImmutableList(), backTo = backTo, dialog = dialog(dialog, items))
    }

    /** The dialog that [key] names, while its notes are still in Trash. */
    private fun dialog(key: String?, items: List<TrashItemUi>): TrashDialog? = when {
        key == null -> null
        key == EMPTY -> TrashDialog.EmptyAll(items.size).takeIf { items.isNotEmpty() }
        else -> items.firstOrNull { it.id == key.removePrefix(DELETE) }?.let { TrashDialog.Delete(it.id, it.title) }
    }

    private fun guarded(what: String, block: suspend () -> Any) {
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Log.e(TAG, "Cannot $what", error)
            }
        }
    }

    private companion object {
        const val TAG = "CassiniTrash"
        const val STOP_TIMEOUT_MS = 5_000L
        const val KEY_DIALOG = "dialog"
        const val DELETE = "delete:"
        const val EMPTY = "empty"
    }
}
