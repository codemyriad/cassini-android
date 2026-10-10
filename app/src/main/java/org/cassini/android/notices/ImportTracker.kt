package org.cassini.android.notices

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.cassini.core.Failure
import org.cassini.data.notes.ImportOutcome

/**
 * The imports of the app (features.md sections 1 and 13.5): the Library's picker and ACTION_VIEW both start one here,
 * so the "Importing <name>…" banner and the import errors show for either. An import runs on [scope] and outlives
 * the screen that started it. [describe] gives the display name of a uri for the banner, [run] imports it.
 *
 * A new import clears the last failure ("shown until dismissed or the next import"); a failed one sets it.
 */
class ImportTracker(
    private val scope: CoroutineScope,
    private val describe: suspend (String) -> String?,
    private val run: suspend (String) -> ImportOutcome,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fallbackName: () -> String = { "" },
) {
    private val mutable = MutableStateFlow(ImportState())

    val state: StateFlow<ImportState> = mutable.asStateFlow()

    /** Imports [uri]. The result is null when the import threw; that is shown as "Can't open this file". */
    fun start(uri: String): Deferred<ImportOutcome?> = scope.async {
        val name = try {
            describe(uri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.w(TAG, "Cannot describe an imported file", error)
            null
        } ?: fallbackName()
        mutable.update { it.copy(running = it.running + name, failure = null) }
        val outcome = try {
            run(uri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.w(TAG, "Import failed", error)
            null
        } finally {
            mutable.update { it.copy(running = it.running - name) }
        }
        val failure = when (outcome) {
            is ImportOutcome.Rejected -> ImportFailure(outcome.failure, outcome.neededBytes, clock())
            null -> ImportFailure(Failure.OPEN, at = clock())
            is ImportOutcome.Created, is ImportOutcome.Existing -> null
        }
        if (failure != null) mutable.update { it.copy(failure = failure) }
        outcome
    }

    /** The user closed the import error banner. */
    fun dismissFailure() {
        mutable.update { it.copy(failure = null) }
    }

    private companion object {
        const val TAG = "CassiniImport"
    }
}
