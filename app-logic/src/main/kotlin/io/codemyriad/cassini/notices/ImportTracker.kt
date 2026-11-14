package io.codemyriad.cassini.notices

import io.codemyriad.cassini.core.Failure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * The imports of the app (features.md sections 1 and 13.5): ACTION_VIEW and ACTION_SEND start one here, and the
 * Library's picker reports its files here, so the Library's import cards and the import errors show for either. An
 * import runs on [scope] and outlives the screen that started it. [describe] gives the display name of a uri for its
 * card, [run] imports it.
 *
 * A new import clears the last failure ("shown until dismissed or the next import"); a failed one sets it.
 *
 * Each import also has an [ImportCard] in [ImportState.cards] (L-25, W4.5 and W4.6): "Importing…" while it runs; a
 * failed one keeps its card with "Needs attention" under the same rule as the failure, or until [dismissCard]. The
 * Library picker's files (`BulkTransfers`) add theirs through [begin] and [finish].
 */
@Suppress("LongParameterList") // Platform adapters, result classification and deterministic clock inputs.
class ImportTracker<T : Any>(
    private val scope: CoroutineScope,
    private val describe: suspend (String) -> String?,
    private val run: suspend (String) -> T,
    private val rejected: (T) -> ImportFailure?,
    private val report: (String, Exception) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fallbackName: () -> String = { "" },
) {
    private val mutable = MutableStateFlow(ImportState())
    private val ids = AtomicLong()

    val state: StateFlow<ImportState> = mutable.asStateFlow()

    /** Imports [uri]. The result is null when the import threw; that is shown as "Can't open this file". */
    fun start(uri: String): Deferred<T?> = scope.async {
        val name = try {
            describe(uri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            report("Cannot describe an imported file", error)
            null
        } ?: fallbackName()
        val card = ImportCard(ids.incrementAndGet(), name)
        mutable.update { ImportState(cards = it.cards.filterNot(ImportCard::failed) + card) }
        val outcome = try {
            run(uri)
        } catch (cancelled: CancellationException) {
            remove(card.id)
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            report("Import failed", error)
            null
        }
        val failure = if (outcome == null) {
            ImportFailure(Failure.OPEN, at = clock())
        } else {
            rejected(outcome)?.copy(at = clock())
        }
        mutable.update { state ->
            state.copy(cards = state.cards.settle(card.id, failure?.failure), failure = failure ?: state.failure)
        }
        outcome
    }

    /**
     * The "Importing…" card of an import that runs elsewhere (a file of the Library picker), named [name]; it does
     * not clear an earlier failure, so the failed cards of the same selection stay. Returns the id for [finish].
     */
    fun begin(name: String): Long {
        val card = ImportCard(ids.incrementAndGet(), name.ifEmpty { fallbackName() })
        mutable.update { it.copy(cards = it.cards + card) }
        return card.id
    }

    /**
     * The import [id] of [begin] ended: its card goes away, or with [failure] it stays with "Needs attention". The
     * import error banner is not set: the picker reports its own summary.
     */
    fun finish(id: Long, failure: Failure? = null) {
        mutable.update { it.copy(cards = it.cards.settle(id, failure)) }
    }

    /** The user dismissed the failed card [id]. A running card stays. */
    fun dismissCard(id: Long) {
        mutable.update { state -> state.copy(cards = state.cards.filterNot { it.id == id && it.failed }) }
    }

    private fun remove(id: Long) {
        mutable.update { state -> state.copy(cards = state.cards.filterNot { it.id == id }) }
    }

    /** Without [failure] the card [id] goes away; with it the card stays, failed, where it was. */
    private fun List<ImportCard>.settle(id: Long, failure: Failure?): List<ImportCard> =
        if (failure == null) filterNot { it.id == id } else map { if (it.id == id) it.copy(failure = failure) else it }

    /**
     * A file another app handed over could not be imported at all (Android refused to pass its read grant on): the
     * banner shows [failure], as for a failed import.
     */
    fun refuse(failure: Failure) {
        mutable.update { it.copy(failure = ImportFailure(failure, at = clock())) }
    }

    /** The user closed the import error banner, or a new selection of the picker starts: failed cards go too. */
    fun dismissFailure() {
        mutable.update { state -> ImportState(cards = state.cards.filterNot(ImportCard::failed)) }
    }
}
