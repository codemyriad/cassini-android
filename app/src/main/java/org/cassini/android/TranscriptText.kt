package org.cassini.android

/**
 * The text of a transcript and where each word sits in it, built by appending. A running job adds
 * its settled words in pieces, so the cost of showing them grows with the new words only, never
 * with the whole transcript. [label] heads each change of speaker; null shows no headers.
 */
internal class TranscriptText(private val label: ((Word) -> String)? = null) {
    val text = StringBuilder()
    var size = 0; private set
    private var starts = LongArray(256)
    private var ends = LongArray(256)
    private var first = IntArray(256)
    private var last = IntArray(256)
    private var previous: Word? = null

    /** Appends [words] after those already here; returns the text offset where they begin. */
    fun append(words: List<Word>): Int {
        val from = text.length
        for (word in words) {
            text.append(separator(previous, word))
            grow()
            first[size] = text.length
            text.append(word.text)
            last[size] = text.length
            starts[size] = word.startMs; ends[size] = word.endMs
            size++
            previous = word
        }
        return from
    }

    /** What [words] would add after the words here, without adding them: a provisional tail. */
    fun tail(words: List<Word>): String {
        val out = StringBuilder()
        var before = previous
        for (word in words) { out.append(separator(before, word)).append(word.text); before = word }
        return out.toString()
    }

    private fun separator(before: Word?, word: Word): String {
        val out = StringBuilder()
        if (before != null) out.append(if (before.speaker != word.speaker || before.text.lastOrNull() in ENDS) "\n\n" else " ")
        if (label != null && (before == null || before.speaker != word.speaker)) out.append(label.invoke(word)).append("\n")
        return out.toString()
    }

    private fun grow() {
        if (size < starts.size) return
        val capacity = starts.size * 2
        starts = starts.copyOf(capacity); ends = ends.copyOf(capacity)
        first = first.copyOf(capacity); last = last.copyOf(capacity)
    }

    /** The characters of word [index]: start inclusive, end exclusive. */
    fun start(index: Int) = first[index]
    fun end(index: Int) = last[index]
    fun startMs(index: Int) = starts[index]

    /** The word spoken at [timeMs], or -1: a binary search over the word starts. */
    fun wordAt(timeMs: Long): Int {
        var low = 0
        var high = size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (starts[middle] <= timeMs) { found = middle; low = middle + 1 } else high = middle - 1
        }
        // Canonical order is not strictly by time: a few earlier words may still cover the instant.
        var hit = -1
        for (index in found downTo maxOf(0, found - LOOK_BACK)) if (timeMs < ends[index]) hit = index
        return hit
    }

    companion object {
        private val ENDS = listOf('.', '?', '!')
        private const val LOOK_BACK = 8
    }
}
