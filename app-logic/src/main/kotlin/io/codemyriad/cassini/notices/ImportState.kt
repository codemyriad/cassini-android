package io.codemyriad.cassini.notices

import io.codemyriad.cassini.core.Failure

/** A failed import, shown at the top until it is dismissed or the next import starts (features.md 13.5). */
data class ImportFailure(val failure: Failure, val neededBytes: Long = 0, val at: Long = 0)

/**
 * One import in the Library list (L-25, W4.5 and W4.6): the "Importing…" card while it runs, then, when it failed,
 * the card it keeps with "Needs attention" ([failure] set). In memory only (ALIGNMENT-PLAN W4.6): a failed card
 * goes away when it is dismissed, when the import error is dismissed or when the next import starts, and none
 * survives the process. [id] is unique in the process and keys the card.
 */
data class ImportCard(val id: Long, val name: String, val failure: Failure? = null) {
    val failed: Boolean get() = failure != null
}

/** The imports of the app now: their cards, oldest first, and the last failure for the import error banner. */
data class ImportState(val cards: List<ImportCard> = emptyList(), val failure: ImportFailure? = null) {
    /** The display names of the running imports, oldest first. */
    val running: List<String> get() = cards.filterNot { it.failed }.map { it.name }
}
