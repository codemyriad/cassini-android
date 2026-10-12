package org.cassini.android.voices

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
import org.cassini.data.voices.SavedVoices
import org.cassini.data.voices.VoiceEdit
import org.cassini.ui.voices.PersonUi
import org.cassini.ui.voices.SavedVoicesDialog
import org.cassini.ui.voices.SavedVoicesEvent
import org.cassini.ui.voices.SavedVoicesUiState

/**
 * Saved voices (board 07, features.md section 10; ui.md section 7): the people of `VoiceRepository.people()` in its
 * order (notes, then confirmed seconds, then name), the options menu, and the Rename, Forget and Forget everyone
 * dialogs. When `people.json` cannot be used the list is read-only and every edit is ignored.
 *
 * The open menu and the open dialog are in [saved] as ids, so they survive process death; the dialog's name and
 * count come from the list. [effects] carries the one navigation request (back).
 */
class SavedVoicesViewModel(
    private val saved: SavedStateHandle,
    private val ports: SavedVoicesPorts,
) : ViewModel() {
    private val effectChannel = Channel<Unit>(Channel.CONFLATED)

    /** Back was pressed: the route pops the screen. */
    val close: Flow<Unit> = effectChannel.receiveAsFlow()

    private val people: Flow<SavedVoices?> = ports.people().catch { error ->
        Log.e(TAG, "The saved voices cannot be read", error)
        emit(SavedVoices(emptyList(), problem = null))
    }

    /** Null until the first list is read. */
    val state: StateFlow<SavedVoicesUiState?> = combine(
        people,
        saved.getStateFlow<String?>(KEY_MENU, null),
        saved.getStateFlow<String?>(KEY_DIALOG, null),
        ::build,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun onEvent(event: SavedVoicesEvent) {
        val readOnly = state.value?.readOnly ?: true
        when (event) {
            SavedVoicesEvent.Back -> effectChannel.trySend(Unit)
            is SavedVoicesEvent.OpenMenu -> if (!readOnly) saved[KEY_MENU] = event.id
            SavedVoicesEvent.DismissMenu -> saved[KEY_MENU] = null
            is SavedVoicesEvent.Rename -> openDialog(readOnly, RENAME + event.id)
            is SavedVoicesEvent.Forget -> openDialog(readOnly, FORGET + event.id)
            SavedVoicesEvent.ForgetEveryone -> openDialog(readOnly, EVERYONE)
            is SavedVoicesEvent.RenameConfirmed -> {
                saved[KEY_DIALOG] = null
                if (!readOnly) edit("rename") { ports.rename(event.id, event.name) }
            }
            SavedVoicesEvent.ConfirmDialog -> confirm(readOnly)
            SavedVoicesEvent.DismissDialog -> saved[KEY_DIALOG] = null
        }
    }

    private fun openDialog(readOnly: Boolean, dialog: String) {
        saved[KEY_MENU] = null
        if (!readOnly) saved[KEY_DIALOG] = dialog
    }

    private fun confirm(readOnly: Boolean) {
        val dialog = saved.get<String>(KEY_DIALOG) ?: return
        saved[KEY_DIALOG] = null
        if (readOnly) return
        when {
            dialog == EVERYONE -> edit("forget everyone") { ports.forgetEveryone() }
            dialog.startsWith(FORGET) -> edit("forget") { ports.forget(dialog.removePrefix(FORGET)) }
        }
    }

    private fun edit(what: String, block: suspend () -> VoiceEdit) {
        viewModelScope.launch {
            val outcome = try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Log.e(TAG, "Cannot $what", error)
                null
            }
            if (outcome != VoiceEdit.Done) Log.w(TAG, "The $what edit ended with $outcome")
        }
    }

    private fun build(voices: SavedVoices?, menu: String?, dialog: String?): SavedVoicesUiState? {
        if (voices == null) return null
        val people = voices.voices.map { voice ->
            PersonUi(voice.id, voice.name, voice.notes, voice.confirmedSeconds.toLong(), voice.stale)
        }
        val readOnly = voices.readOnly
        return SavedVoicesUiState(
            people = people.toImmutableList(),
            readOnly = readOnly,
            menuFor = menu?.takeIf { id -> !readOnly && people.any { it.id == id } },
            dialog = if (readOnly) null else dialog(dialog, people),
        )
    }

    /** The dialog that [key] names, while its person is still in the list. */
    private fun dialog(key: String?, people: List<PersonUi>): SavedVoicesDialog? {
        fun person(prefix: String) = key?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
            ?.let { id -> people.firstOrNull { it.id == id } }
        return when {
            key == EVERYONE -> SavedVoicesDialog.ForgetEveryone(people.size).takeIf { people.isNotEmpty() }
            key == null -> null
            key.startsWith(RENAME) -> person(RENAME)?.let { SavedVoicesDialog.Rename(it.id, it.name) }
            else -> person(FORGET)?.let { SavedVoicesDialog.Forget(it.id, it.name) }
        }
    }

    private companion object {
        const val TAG = "CassiniVoices"
        const val STOP_TIMEOUT_MS = 5_000L
        const val KEY_MENU = "menu"
        const val KEY_DIALOG = "dialog"
        const val RENAME = "rename:"
        const val FORGET = "forget:"
        const val EVERYONE = "everyone"
    }
}
