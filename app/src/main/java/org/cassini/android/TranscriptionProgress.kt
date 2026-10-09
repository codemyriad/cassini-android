package org.cassini.android

/** UI-thread state: completed audio controls the bar; stages never reset it. Times are elapsed ms. */
internal class TranscriptionProgress(initialTotalMs: Long) {
    enum class Stage { READING, PREPARING, WORDS, SPEAKERS, MATCHING, SAVING }
    data class Snapshot(val doneMs: Long, val totalMs: Long, val percent: Int?, val words: Int,
                        val stage: Stage, val startMs: Long, val endMs: Long, val remainingMs: Long?, val remainingLowMs: Long? = null, val remainingHighMs: Long? = null)
    private var totalMs = initialTotalMs.coerceAtLeast(0)
    private var doneMs = 0L
    private var words = 0
    private var percent = 0
    private var completions = 0
    private var completedAtMs = 0L
    private var rate: Double? = null
    private var deviation = 0.0
    private var stage = Stage.READING
    private var stageAtMs = 0L
    private var startMs = 0L
    private var endMs = 0L
    private var shownStage = Stage.READING
    private var shownStartMs = 0L
    private var shownEndMs = 0L

    fun restore(done: Long, total: Long, count: Int, elapsedMs: Long) {
        total(total); doneMs = done; words = count; completedAtMs = elapsedMs
        // Reloading the model on resume is another cold start; exclude that first completion.
        completions = 0
    }

    fun total(value: Long) { if (value > 0) totalMs = value }

    fun stage(value: Stage, elapsedMs: Long, start: Long = 0, end: Long = 0) {
        if (stage != value) stageAtMs = elapsedMs
        stage = value; startMs = start; endMs = end
    }

    fun complete(done: Long, total: Long, count: Int, elapsedMs: Long) {
        total(total)
        if (done > doneMs) {
            // The first chunk includes model loading; use subsequent whole chunks to predict pace.
            if (completions > 0 && elapsedMs > completedAtMs) {
                val measured = (elapsedMs - completedAtMs).toDouble() / (done - doneMs)
                deviation = rate?.let { deviation * .8 + kotlin.math.abs(measured - it) * .2 } ?: 0.0
                rate = rate?.let { it * .8 + measured * .2 } ?: measured
            }
            completions++
            doneMs = done
            completedAtMs = elapsedMs
        }
        words = count
    }

    fun snapshot(elapsedMs: Long): Snapshot {
        // Reading/encoding can take only milliseconds between native calls. Avoid flashing that stage.
        if (elapsedMs - stageAtMs >= 400 || stage == shownStage) {
            shownStage = stage; shownStartMs = startMs; shownEndMs = endMs
        }
        val fraction = if (totalMs > 0) ((doneMs * 100 / totalMs).coerceIn(0, 100)).toInt() else null
        if (fraction != null) percent = maxOf(percent, fraction)
        val remaining = if (totalMs > 0 && doneMs >= totalMs) 0L
            else if (completions >= 3 && totalMs > doneMs) rate?.let {
                ((totalMs - doneMs) * it - (elapsedMs - completedAtMs)).toLong().coerceAtLeast(5000)
            } else null
        return Snapshot(doneMs, totalMs, fraction?.let { percent }, words, shownStage, shownStartMs, shownEndMs, remaining,
            remaining?.takeIf { it > 0 }?.let { (it * (1 - uncertainty())).toLong().coerceAtLeast(5000) },
            remaining?.takeIf { it > 0 }?.let { (it * (1 + uncertainty())).toLong() })
    }
    private fun uncertainty() = maxOf(.15, (deviation / (rate ?: 1.0)) * 2).coerceAtMost(.75)

    /** Honest approximate estimates, rounded up instead of displaying unstable second-level precision. */
    companion object {
        fun roundedRemaining(value: Long): Long {
            val step = when { value >= 300000 -> 60000L; value >= 60000 -> 10000L; else -> 5000L }
            return if (value <= 0) 0 else ((value + step - 1) / step) * step
        }
    }
}
