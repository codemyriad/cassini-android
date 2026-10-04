package org.cassini.android

/**
 * Reconciles the words of two overlapping decode windows, ported from the desktop
 * pipeline's overlap dedup. Decoder timestamps shift by hundreds of milliseconds when
 * the same word is heard with left versus right context, so a hard midpoint splice can
 * duplicate a word or drop both copies. Instead an order-preserving alignment of equal
 * normalized words near the overlap removes only confirmed duplicates; one-sided and
 * lexically different words survive. All times are source-timeline milliseconds, so
 * nothing here depends on the sample rate.
 */
internal object SeamMerge {
    /** Widest accepted shift between two copies; the measured adjacent-decode shift is ~300 ms. */
    const val DUPLICATE_TOLERANCE_MS = 400L
    /** A lone repeated word is a duplicate only within this distance. */
    const val SINGLETON_TOLERANCE_MS = 200L
    /** Adjacent matches whose shifts agree this closely support each other up to the wider tolerance. */
    const val SHIFT_CONSISTENCY_MS = 100L

    /**
     * Appends [next] to [acc], removing words both windows decoded inside the overlap
     * `[windowStartMs, windowStartMs + overlapMs]`. Of two matched copies the one farther
     * from its own decode boundary, where the recognizer had more context, is kept; a copy
     * with duration beats a zero-length one. One-sided words can interleave, so the result
     * is stably re-sorted by start. [midpointOnDisagreement] selects the merged-fallback
     * policy: when both sides populate the overlap but nothing matches confidently, the
     * earlier decode owns starts before the overlap midpoint and the later one the rest.
     */
    fun merge(acc: List<Word>, next: List<Word>, firstWindow: Boolean, windowStartMs: Long, overlapMs: Long,
              midpointOnDisagreement: Boolean = false): List<Word> =
        merge(acc, next, firstWindow, windowStartMs, overlapMs, midpointOnDisagreement) { it }

    /**
     * Joins two decodes of a forced cut that both heard `[overlapStartMs, overlapStartMs + overlapMs]`,
     * centred on the cut. They agree on most words there, so the join is made at one agreed word, the
     * one nearest the cut: everything up to it comes from the earlier decode and everything after it
     * from the later one. Each reading is taken whole on its side, so a word the two decodes time or
     * split differently is neither doubled nor lost. Without an agreed word the join is the instant
     * nearest the cut at which neither decode is inside a word.
     */
    fun <T> splice(items: List<T>, more: List<T>, overlapStartMs: Long, overlapMs: Long, word: (T) -> Word): List<T> {
        if (items.isEmpty() || more.isEmpty()) return items + more
        val acc = items.map(word)
        val next = more.map(word)
        val overlapEndMs = overlapStartMs + overlapMs
        val cutMs = overlapStartMs + overlapMs / 2
        val aligned = align(acc, next, overlapStartMs, overlapEndMs, DUPLICATE_TOLERANCE_MS)
        // A lone pair further apart than the singleton tolerance is still the best evidence when nothing firmer exists.
        // Known trade-off: two distinct, quickly repeated words ("sì sì") inside the overlap can collapse into one
        // here, in exchange for never doubling a word the decodes timed 220-240 ms apart (measured on the phone).
        val agreed = confidentMatches(aligned, acc, next, overlapEndMs).ifEmpty { aligned }
        val anchor = agreed.minByOrNull { Math.abs((midpointMs(acc[it.acc]) + midpointMs(next[it.next])) / 2 - cutMs) }
        if (anchor != null) {
            // A copy with duration beats a zero-length one, as in merge: a word stamped in padding and clamped to the
            // timeline edge would never highlight, so the later decode's copy replaces it.
            val clamped = acc[anchor.acc].endMs <= acc[anchor.acc].startMs
            val replacement = next[anchor.next].endMs > next[anchor.next].startMs
            return if (clamped && replacement) items.subList(0, anchor.acc) + more.subList(anchor.next, more.size)
            else items.subList(0, anchor.acc + 1) + more.subList(anchor.next + 1, more.size)
        }
        val inside = { at: Long -> (acc + next).any { it.startMs < at && at < it.endMs } }
        val joinMs = (acc + next).flatMap { listOf(it.startMs, it.endMs) }.filter { it in overlapStartMs..overlapEndMs && !inside(it) }
            .minByOrNull { Math.abs(it - cutMs) } ?: cutMs
        return items.filterIndexed { i, _ -> midpointMs(acc[i]) < joinMs } + more.filterIndexed { i, _ -> midpointMs(next[i]) >= joinMs }
    }

    fun splice(acc: List<Word>, next: List<Word>, overlapStartMs: Long, overlapMs: Long): List<Word> =
        splice(acc, next, overlapStartMs, overlapMs) { it }

    /** The same merge over items that carry a word, such as decoder words with their extension cap. */
    fun <T> merge(items: List<T>, more: List<T>, firstWindow: Boolean, windowStartMs: Long, overlapMs: Long,
                  midpointOnDisagreement: Boolean = false, word: (T) -> Word): List<T> {
        if (firstWindow || items.isEmpty()) return items + more
        if (more.isEmpty()) return items
        val acc = items.map(word)
        val next = more.map(word)
        val overlapEndMs = windowStartMs + overlapMs
        val matches = confidentMatches(align(acc, next, windowStartMs, overlapEndMs, DUPLICATE_TOLERANCE_MS), acc, next, overlapEndMs)
        if (midpointOnDisagreement && matches.isEmpty() &&
            hasWordInOverlap(acc, windowStartMs, overlapEndMs) && hasWordInOverlap(next, windowStartMs, overlapEndMs)) {
            // One owner per instant: the earlier decode keeps starts before the overlap midpoint, the later one the rest.
            val cutMs = windowStartMs + overlapMs / 2
            var kept = items.size
            while (kept > 0 && acc[kept - 1].startMs >= cutMs) kept--
            return items.subList(0, kept) + more.filterIndexed { i, _ -> next[i].startMs >= cutMs }
        }
        val dropAcc = BooleanArray(acc.size)
        val dropNext = BooleanArray(next.size)
        for (match in matches) {
            val old = acc[match.acc]
            val new = next[match.next]
            val oldHasDuration = old.endMs > old.startMs
            val newHasDuration = new.endMs > new.startMs
            if (oldHasDuration != newHasDuration) {
                if (newHasDuration) dropAcc[match.acc] = true else dropNext[match.next] = true
                continue
            }
            val oldContext = maxOf(0L, overlapEndMs - midpointMs(old))
            val newContext = maxOf(0L, midpointMs(new) - windowStartMs)
            if (newContext > oldContext) dropAcc[match.acc] = true else dropNext[match.next] = true
        }
        val merged = items.filterIndexed { i, _ -> !dropAcc[i] } + more.filterIndexed { i, _ -> !dropNext[i] }
        return merged.sortedBy { word(it).startMs }
    }

    /** True when [words] positively populate the overlap; touching a boundary does not count. */
    internal fun hasWordInOverlap(words: List<Word>, overlapStartMs: Long, overlapEndMs: Long): Boolean = words.any { word ->
        when {
            word.endMs < word.startMs || normalize(word.text).isEmpty() -> false
            word.endMs == word.startMs -> word.startMs in overlapStartMs..overlapEndMs
            else -> word.endMs > overlapStartMs && word.startMs < overlapEndMs
        }
    }

    internal data class Match(val acc: Int, val next: Int)
    private class Ref(val index: Int, val normal: String, val timestamp: Long)
    private data class Cell(val matches: Int = 0, val distance: Long = 0, val step: Char = '\u0000')

    /**
     * Maximum-count alignment of equal normalized words, ties broken by the smaller total
     * start distance; on a full tie a match beats a skip, and skipping [acc] beats skipping [next].
     */
    internal fun align(acc: List<Word>, next: List<Word>, overlapStartMs: Long, overlapEndMs: Long, toleranceMs: Long): List<Match> {
        val left = refs(acc, overlapStartMs, overlapEndMs, toleranceMs)
        val right = refs(next, overlapStartMs, overlapEndMs, toleranceMs)
        if (left.isEmpty() || right.isEmpty()) return emptyList()
        val dp = Array(left.size + 1) { arrayOfNulls<Cell>(right.size + 1) }
        dp[0][0] = Cell()
        for (i in 1..left.size) dp[i][0] = dp[i - 1][0]!!.copy(step = 'l')
        for (j in 1..right.size) dp[0][j] = dp[0][j - 1]!!.copy(step = 'r')
        for (i in 1..left.size) for (j in 1..right.size) {
            var best = dp[i - 1][j]!!.copy(step = 'l')
            if (better(dp[i][j - 1]!!, best)) best = dp[i][j - 1]!!.copy(step = 'r')
            val distance = Math.abs(left[i - 1].timestamp - right[j - 1].timestamp)
            if (left[i - 1].normal == right[j - 1].normal && distance <= toleranceMs) {
                val diagonal = dp[i - 1][j - 1]!!
                val matched = Cell(diagonal.matches + 1, diagonal.distance + distance, 'm')
                if (betterOrEqual(matched, best)) best = matched
            }
            dp[i][j] = best
        }
        val matches = ArrayList<Match>()
        var i = left.size
        var j = right.size
        while (i > 0 || j > 0) when (dp[i][j]!!.step) {
            'm' -> { matches += Match(left[i - 1].index, right[j - 1].index); i--; j-- }
            'l' -> i--
            'r' -> j--
            else -> { i = 0; j = 0 }
        }
        return matches.asReversed()
    }

    private fun refs(words: List<Word>, overlapStartMs: Long, overlapEndMs: Long, toleranceMs: Long): List<Ref> =
        words.mapIndexedNotNull { i, word ->
            if (word.endMs < word.startMs || word.endMs < overlapStartMs - toleranceMs || word.startMs > overlapEndMs + toleranceMs) null
            else normalize(word.text).takeIf { it.isNotEmpty() }?.let { Ref(i, it, word.startMs) }
        }

    /**
     * Narrows the permissive alignment so two distinct, rapidly repeated words are not
     * collapsed merely because they share text. A lone pair must start within
     * [SINGLETON_TOLERANCE_MS]; the wider shift is accepted only for an adjacent run with a
     * consistent shift, or when replacing a zero-length token clamped at the overlap end.
     */
    internal fun confidentMatches(matches: List<Match>, acc: List<Word>, next: List<Word>, overlapEndMs: Long): List<Match> {
        if (matches.isEmpty()) return emptyList()
        val keep = BooleanArray(matches.size) { i ->
            val old = acc[matches[i].acc]
            Math.abs(old.startMs - next[matches[i].next].startMs) <= SINGLETON_TOLERANCE_MS ||
                (old.startMs == overlapEndMs && old.endMs == overlapEndMs)
        }
        for (i in 0 until matches.size - 1) {
            val left = matches[i]
            val right = matches[i + 1]
            if (right.acc != left.acc + 1 || right.next != left.next + 1) continue
            val leftShift = next[left.next].startMs - acc[left.acc].startMs
            val rightShift = next[right.next].startMs - acc[right.acc].startMs
            if (Math.abs(leftShift - rightShift) <= SHIFT_CONSISTENCY_MS) { keep[i] = true; keep[i + 1] = true }
        }
        return matches.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * Comparison key for an overlap word: per-code-point simple lowercase (Go's
     * `unicode.ToLower`, not locale or context-sensitive `lowercase()`), curly single quotes
     * folded to `'`, edge spaces and bracketing punctuation trimmed, then trailing dots, so
     * "morning." equals "morning," while "C++", "dell'", ".NET" and "3.14" keep their marks.
     * Empty when no letter or number remains.
     */
    internal fun normalize(text: String): String {
        val mapped = StringBuilder(text.length)
        text.codePoints().forEach { cp ->
            mapped.appendCodePoint(if (cp == 0x2018 || cp == 0x2019) '\''.code else Character.toLowerCase(cp))
        }
        var s = mapped.toString()
        var start = 0
        while (start < s.length) {
            val cp = s.codePointAt(start)
            if (!trimmed(cp)) break
            start += Character.charCount(cp)
        }
        var end = s.length
        while (end > start) {
            val cp = s.codePointBefore(end)
            if (!trimmed(cp)) break
            end -= Character.charCount(cp)
        }
        s = s.substring(start, end).trimEnd('.')
        var hasLetterOrNumber = false
        s.codePoints().forEach { cp -> if (Character.isLetter(cp) || isNumber(cp)) hasLetterOrNumber = true }
        return if (hasLetterOrNumber) s else ""
    }

    private const val TRIMMED_PUNCTUATION = ",!?;:\"\u201C\u201D()[]{}<>"

    private fun trimmed(cp: Int) = goIsSpace(cp) || (cp < 0x10000 && TRIMMED_PUNCTUATION.indexOf(cp.toChar()) >= 0)

    /** Go's `unicode.IsSpace` (the White_Space property): unlike Java it includes NBSP and excludes U+001C..U+001F. */
    private fun goIsSpace(cp: Int) = when (cp) {
        in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /** Go's `unicode.IsNumber` is category N, which also covers Nl and No (`²`, `½`), not only digits. */
    private fun isNumber(cp: Int) = when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    internal fun midpointMs(word: Word): Long =
        if (word.endMs <= word.startMs) word.startMs else word.startMs + (word.endMs - word.startMs) / 2

    private fun better(candidate: Cell, current: Cell) = candidate.matches > current.matches ||
        (candidate.matches == current.matches && candidate.distance < current.distance)

    private fun betterOrEqual(candidate: Cell, current: Cell) = candidate.matches > current.matches ||
        (candidate.matches == current.matches && candidate.distance <= current.distance)
}
