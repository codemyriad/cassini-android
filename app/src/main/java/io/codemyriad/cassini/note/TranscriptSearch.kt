package io.codemyriad.cassini.note

import io.codemyriad.cassini.ui.components.TurnUi
import io.codemyriad.cassini.ui.components.turnText
import io.codemyriad.cassini.ui.note.TranscriptMatch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** Adapts UI turns to the plain-JVM search and its source character ranges. */
object TranscriptSearch {
    fun matches(turns: List<TurnUi>, query: String): ImmutableList<TranscriptMatch> =
        TextSearch.matches(turns, query, { it.id }, { turnText(it.words) })
            .map { TranscriptMatch(it.turnId, it.range) }.toImmutableList()
}
