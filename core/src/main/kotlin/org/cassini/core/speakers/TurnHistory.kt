package org.cassini.core.speakers

/**
 * The speaker turns of a streaming diarization on the recording clock, window after window. Windows overlap, so the
 * turns of a later window replace those of the earlier one from [SpeakerBudget.TURN_OVERLAP_MS] into it.
 */
class TurnHistory(restored: List<SpeakerTurn> = emptyList()) {
    private val history = restored.toMutableList()

    val turns: List<SpeakerTurn> get() = history.toList()

    /** Adds the turns [found] in the window that starts at [startMs], each relative to that start. */
    fun add(found: List<SpeakerTurn>, startMs: Long) {
        val cut = if (startMs == 0L) 0 else startMs + SpeakerBudget.TURN_OVERLAP_MS
        for (index in history.indices.reversed()) {
            val old = history[index]
            if (old.startMs >= cut) {
                history.removeAt(index)
            } else if (old.endMs > cut) history[index] = old.copy(endMs = cut)
        }
        history.addAll(
            found.map {
                it.copy(startMs = maxOf(cut, startMs + it.startMs), endMs = startMs + it.endMs)
            }.filter { it.endMs > it.startMs }
        )
    }
}
