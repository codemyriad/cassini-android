package io.codemyriad.cassini.notices

import io.codemyriad.cassini.core.Failure

/** A failed import, shown at the top until it is dismissed or the next import starts (features.md 13.5). */
data class ImportFailure(val failure: Failure, val neededBytes: Long = 0, val at: Long = 0)

/** The imports of the app now: the display names of the running ones and the last failure. */
data class ImportState(val running: List<String> = emptyList(), val failure: ImportFailure? = null)
