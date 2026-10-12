package org.cassini.android.nav

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.cassini.data.notes.ImportOutcome

/**
 * The ACTION_VIEW imports whose notes the navigation opens when they end (docs/design/ui.md section 9). Each import
 * runs on [scope], not in the composition: an activity that is recreated meanwhile (a rotation, a language change)
 * still opens the note, and a later deep link does not wait for the import. [ready] holds the note ids to open; the
 * navigation [take]s each one when it has opened it.
 */
class ViewedImports(private val scope: CoroutineScope, private val import: (String) -> Deferred<ImportOutcome?>) {
    private val pending = MutableStateFlow<List<String>>(emptyList())

    val ready: StateFlow<List<String>> = pending.asStateFlow()

    /** Imports [uri]; a failed import only shows its Library banner, and nothing opens. */
    fun start(uri: String) {
        scope.launch {
            val noteId = when (val outcome = import(uri).await()) {
                is ImportOutcome.Created -> outcome.noteId
                is ImportOutcome.Existing -> outcome.noteId
                else -> null.also { Log.w(TAG, "Import of a viewed file ended with $outcome") }
            }
            if (noteId != null) pending.update { it + noteId }
        }
    }

    /** The navigation opened [noteId]. */
    fun take(noteId: String) = pending.update { it - noteId }

    private companion object {
        const val TAG = "CassiniNav"
    }
}
