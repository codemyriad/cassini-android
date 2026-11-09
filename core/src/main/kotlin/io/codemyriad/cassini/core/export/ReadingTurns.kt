package io.codemyriad.cassini.core.export

import io.codemyriad.cassini.core.format.Word
import kotlin.math.abs

/** A viewer paragraph member. Indices always refer to the unchanged canonical word list. */
data class ReadingMember(
    val wordIndices: List<Int>,
    val interjectionSpeaker: String? = null,
    val crosstalk: Boolean = false,
)

/** One reading paragraph; a turn's paragraphs stay together even during competing speech. */
data class ReadingRow(
    val key: Int,
    val turnKey: Int,
    val speaker: String,
    val startMs: Long,
    val endMs: Long,
    val members: List<ReadingMember>,
    val over: List<String>,
    val continuation: Boolean,
    val crosstalk: Boolean,
)

/** A whole speaker run, including runs rendered as interjections (for statistics, never as extra rows). */
data class ReadingRun(
    val speaker: String,
    val startMs: Long,
    val endMs: Long,
    val audibleMs: Long,
    val wordIndices: List<Int>,
)

/** Display-only repaired spans; text, starts and canonical indices are unchanged. */
data class ReadingTranscript(val words: List<Word>, val rows: List<ReadingRow>, val runs: List<ReadingRun>)

/**
 * Kotlin port of gocassini 1111155: portable.ts readable grouping/merging, meetingExport timing repair,
 * overlap.ts buildTranscriptRows, and MeetingView's 60-second continuation rule. No Android dependencies.
 * Group in file order, repair display spans, then stable-sort BLOCKS, never the canonical words.
 */
object ReadingTurns {
    private const val HARD_GAP_MS = 4200L
    private const val SOFT_GAP_MS = 2200L
    private const val TARGET_WORDS = 96
    private const val TARGET_MS = 45_000L
    private const val MAX_WORDS = 140
    private const val MAX_MS = 90_000L
    private const val MIN_WORDS = 18
    private const val MIN_MS = 8000L
    private const val CONTINUATION_MS = 60_000L
    private const val MEDIAN_SAMPLE_WORDS = 8
    private const val FALLBACK_MEDIAN_MS = 250.0
    private const val FINAL_WORD_MS = 1000.0
    private const val MEDIAN_FACTOR = 4
    private const val SEAM_MS = 400L
    private const val SEAM_TOUCH_MS = 20L
    private const val INTERJECTION_MS = 2000L
    private const val SIDE_GAP_MS = 500L
    private const val OVERLAP_MS = 150L
    private const val KEY_DIGITS = 6

    private val sentenceEnd = Regex("[.!?][\"')\\]]*$")
    private val wordLike = Regex("[\\p{L}\\p{N}]")
    private val terminal = Regex("[.?!,;:…。．！？；：，、।॥؛؟][)\\]}\"'’”»›」』〉》]*$")

    // Viewer buildTranscriptRows, in reference order.
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
    fun of(
        words: List<Word>,
        endsBoundedByAudio: Boolean = false,
        lowConfidenceWords: Set<Int> = emptySet(),
    ): ReadingTranscript {
        val grouped = readableBlocks(words)
        val effective = if (endsBoundedByAudio) words else repair(words, grouped)
        val ordered = grouped.map { block ->
            if (effective === words || block.indices.none { effective[it] !== words[it] }) {
                block
            } else {
                block.copy(end = maxOf(block.start, minOf(block.end, block.indices.maxOf { effective[it].endMs })))
            }
        }.sortedBy { it.start }
        val spans = ordered.associateWith { block ->
            merge(
                block.indices.mapNotNull { index ->
                    effective[index].let { if (it.endMs > it.startMs) Span(it.startMs, it.endMs) else null }
                },
            ).ifEmpty { if (block.end > block.start) listOf(Span(block.start, block.end)) else emptyList() }
        }
        val runs = buildRuns(ordered, spans)
        val nested = nest(runs, spans)
        val over = competing(runs, nested, spans)
        val positions = ordered.withIndex().associate { it.value.key to it.index }
        val rows = ArrayList<ReadingRow>()
        for (run in runs) {
            if (run in nested) continue
            val children = nested.filterValues { it.host === run }.keys.sortedBy { it.start }
            val members = run.blocks.flatMap { block ->
                listOf(Part(listOf(block))) + children.filter { nested.getValue(it).before === block }
                    .map { Part(it.blocks, it.speaker) }
            }
            val paragraphs = ArrayList<MutableList<Part>>()
            var current = ArrayList<Part>()
            var previous: Block? = null
            for (member in members) {
                if (member.speaker == null && previous != null && current.lastOrNull()?.speaker == null &&
                    positions.getValue(member.blocks.first().key) == positions.getValue(previous.key) + 1
                ) {
                    paragraphs += current
                    current = ArrayList()
                }
                current += member
                if (member.speaker == null) previous = member.blocks.single()
            }
            if (current.isNotEmpty()) paragraphs += current
            paragraphs.forEachIndexed { index, paragraph ->
                val blocks = paragraph.flatMap { it.blocks }
                val own = paragraph.filter { it.speaker == null }.flatMap { it.blocks }.flatMap { it.indices }
                val start = blocks.minOf { it.start }
                val prior = rows.lastOrNull()
                rows += ReadingRow(
                    key = if (index == 0) run.blocks.first().key else blocks.first().key,
                    turnKey = run.blocks.first().key,
                    speaker = run.speaker,
                    startMs = start,
                    endMs = blocks.maxOf { it.end },
                    members = paragraph.map { part ->
                        val indices = part.blocks.flatMap { it.indices }
                        ReadingMember(indices, part.speaker, part.speaker != null && indices.all { it in lowConfidenceWords })
                    },
                    over = if (index == 0) over[run].orEmpty() else emptyList(),
                    continuation = prior != null && prior.speaker == run.speaker && start - prior.endMs <= CONTINUATION_MS,
                    crosstalk = own.isNotEmpty() && own.all { it in lowConfidenceWords },
                )
            }
        }
        return ReadingTranscript(
            effective,
            rows,
            runs.map { run ->
                ReadingRun(run.speaker, run.start, run.end, total(run.spans(spans)), run.blocks.flatMap { it.indices })
            },
        )
    }

    private data class Block(val speaker: String, val start: Long, val end: Long, val indices: List<Int>) {
        val key: Int get() = indices.first()
    }
    private data class Span(val start: Long, val end: Long)
    private data class Part(val blocks: List<Block>, val speaker: String? = null)
    private class Run(
        val speaker: String,
        val blocks: MutableList<Block>,
        val start: Long,
        var end: Long,
        val attested: Boolean,
        var lastPosition: Int,
    ) {
        fun spans(spans: Map<Block, List<Span>>): List<Span> = merge(blocks.flatMap { spans.getValue(it) })
    }
    private data class Nest(val host: Run, val before: Block)

    // Exact portable.ts grouping and small-block merge predicates.
    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    private fun readableBlocks(words: List<Word>): List<Block> {
        val grouped = ArrayList<Block>()
        var from = 0
        while (from < words.size) {
            var to = from + 1
            while (to < words.size) {
                val first = words[from]
                val previous = words[to - 1]
                val next = words[to]
                val gap = maxOf(0, next.startMs - previous.endMs)
                val target = to - from >= TARGET_WORDS || previous.endMs - first.startMs >= TARGET_MS
                if (next.speaker != first.speaker || gap > HARD_GAP_MS || next.endMs - first.startMs > MAX_MS ||
                    to - from + 1 > MAX_WORDS || (target && (sentenceEnd.containsMatchIn(previous.text) || gap >= SOFT_GAP_MS))
                ) {
                    break
                }
                to++
            }
            grouped += Block(words[from].speaker, words[from].startMs, words[to - 1].endMs, (from until to).toList())
            from = to
        }
        val merged = ArrayList<Block>()
        for (block in grouped) {
            val prior = merged.lastOrNull()
            val small = prior != null && (
                prior.indices.size < MIN_WORDS || block.indices.size < MIN_WORDS ||
                    prior.end - prior.start < MIN_MS || block.end - block.start < MIN_MS
                )
            if (prior != null && prior.speaker == block.speaker && block.start - prior.end <= HARD_GAP_MS &&
                prior.indices.size + block.indices.size <= MAX_WORDS && block.end - prior.start <= MAX_MS && small
            ) {
                merged[merged.lastIndex] = prior.copy(end = block.end, indices = prior.indices + block.indices)
            } else {
                merged += block
            }
        }
        return merged
    }

    // candidateRuns and budgetsBySpeaker over canonical one-word atoms.
    @Suppress("CyclomaticComplexMethod")
    private fun repair(words: List<Word>, blocks: List<Block>): List<Word> {
        val candidates = ArrayList<List<Int>>()
        for (block in blocks) {
            var index = 0
            while (index < block.indices.size) {
                if (!wordLike.containsMatchIn(words[block.indices[index]].text)) {
                    index++
                    continue
                }
                var end = index + 1
                while (end < block.indices.size && !wordLike.containsMatchIn(words[block.indices[end]].text)) end++
                val indices = block.indices.subList(index, end)
                if (terminal.containsMatchIn(indices.joinToString("") { words[it].text })) candidates += indices
                index = end
            }
        }
        val excluded = candidates.flatten().toHashSet()
        val durations = words.indices.filter {
            it !in excluded && wordLike.containsMatchIn(words[it].text) && words[it].endMs > words[it].startMs
        }.groupBy { words[it].speaker }
            .mapValues { (_, indices) -> indices.map { words[it].endMs - words[it].startMs } }
        fun median(values: List<Long>): Double {
            val sorted = values.sorted()
            return (sorted[(sorted.size - 1) / 2].toDouble() + sorted[sorted.size / 2]) / 2
        }
        val all = durations.values.flatten()
        val meeting = if (all.size >= MEDIAN_SAMPLE_WORDS) median(all) else FALLBACK_MEDIAN_MS
        val budgets = durations.mapValues { (_, own) ->
            val reference = if (own.size >= MEDIAN_SAMPLE_WORDS) median(own) else meeting
            maxOf(FINAL_WORD_MS, MEDIAN_FACTOR * reference).toLong()
        }
        val fallback = maxOf(FINAL_WORD_MS, MEDIAN_FACTOR * meeting).toLong()
        val effective = words.toMutableList()
        for (candidate in candidates) {
            val first = candidate.first()
            val budget = budgets[words[first].speaker] ?: fallback
            val start = candidate.minOf { words[it].startMs }
            if (candidate.maxOf { words[it].endMs } - start <= budget) continue
            for (index in candidate) {
                val word = words[index]
                effective[index] = word.copy(endMs = maxOf(word.startMs, minOf(word.endMs, start + budget)))
            }
        }
        return effective
    }

    // All four gates in viewer buildTurnRuns must hold.
    @Suppress("ComplexCondition")
    private fun buildRuns(ordered: List<Block>, spans: Map<Block, List<Span>>): List<Run> {
        val runs = ArrayList<Run>()
        val open = HashMap<String, Run>()
        for ((position, block) in ordered.withIndex()) {
            val audible = spans.getValue(block)
            val run = open[block.speaker]
            if (run != null && audible.isNotEmpty() && run.attested && abs(audible.first().start - run.end) <= SEAM_MS &&
                continuity(run, position, ordered, spans)
            ) {
                run.blocks += block
                run.end = maxOf(run.end, audible.last().end)
                run.lastPosition = position
            } else {
                val fresh = Run(
                    block.speaker,
                    arrayListOf(block),
                    audible.firstOrNull()?.start ?: block.start,
                    audible.lastOrNull()?.end ?: block.end,
                    audible.isNotEmpty(),
                    position,
                )
                runs += fresh
                open[block.speaker] = fresh
            }
        }
        return runs
    }

    // Skip same-speaker or unattested intervening blocks as in the reference.
    @Suppress("LoopWithTooManyJumpStatements", "ReturnCount")
    private fun continuity(run: Run, position: Int, ordered: List<Block>, spans: Map<Block, List<Span>>): Boolean {
        if (position == run.lastPosition + 1) return true
        var attested = false
        val before = spans.getValue(run.blocks.last())
        val after = spans.getValue(ordered[position])
        for (index in run.lastPosition + 1 until position) {
            val between = ordered[index]
            if (between.speaker == run.speaker) continue
            val speech = spans.getValue(between)
            if (speech.isEmpty()) continue
            attested = true
            val touching = speech.any { a ->
                (before + after).any { b -> maxOf(a.start, b.start) - minOf(a.end, b.end) <= SEAM_TOUCH_MS }
            }
            if (touching) return true
        }
        return !attested
    }

    // Viewer bestHostFor gates/tie-break.
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "LoopWithTooManyJumpStatements")
    private fun nest(runs: List<Run>, spans: Map<Block, List<Span>>): Map<Run, Nest> {
        val candidates = LinkedHashMap<Run, Nest>()
        for (run in runs) {
            if (!run.attested || total(run.spans(spans)) > INTERJECTION_MS) continue
            var best: Nest? = null
            var bestEnd = Long.MIN_VALUE
            var bestSpan = -1L
            for (host in runs) {
                if (host === run || !host.attested || host.speaker == run.speaker || run.end > host.end) continue
                val beforeIndex = host.blocks.indexOfLast {
                    spans.getValue(it).firstOrNull()?.start?.let { start -> start < run.start } == true
                }
                val before = host.blocks.getOrNull(beforeIndex) ?: continue
                val after = host.blocks.getOrNull(beforeIndex + 1) ?: continue
                val beforeEnd = spans.getValue(before).lastOrNull()?.end ?: continue
                val afterStart = spans.getValue(after).firstOrNull()?.start ?: continue
                val startingInside = host.blocks.count {
                    spans.getValue(it).firstOrNull()?.start?.let { start -> start > run.start && start <= run.end } == true
                }
                if (startingInside > 1 ||
                    run.start - beforeEnd > SIDE_GAP_MS || afterStart - run.end > SIDE_GAP_MS
                ) {
                    continue
                }
                val duration = host.end - host.start
                if (beforeEnd > bestEnd || (beforeEnd == bestEnd && duration > bestSpan)) {
                    best = Nest(host, before)
                    bestEnd = beforeEnd
                    bestSpan = duration
                }
            }
            if (best != null) candidates[run] = best
        }
        return candidates.filterValues { it.host !in candidates }
    }

    private fun competing(runs: List<Run>, nested: Map<Run, Nest>, spans: Map<Block, List<Span>>): Map<Run, List<String>> {
        data class Collision(val left: Run, val right: Run, val ms: Long)
        val collisions = ArrayList<Collision>()
        val audible = runs.associateWith { it.spans(spans) }
        for ((index, left) in runs.withIndex()) {
            for (right in runs.drop(index + 1)) {
                if (left.speaker == right.speaker || nested[left]?.host === right || nested[right]?.host === left) continue
                val ms = audible.getValue(left).sumOf { a ->
                    audible.getValue(right).sumOf { b -> maxOf(0, minOf(a.end, b.end) - maxOf(a.start, b.start)) }
                }
                if (ms >= OVERLAP_MS) collisions += Collision(left, right, ms)
            }
        }
        val over = HashMap<Run, MutableList<String>>()
        val largestFirst = collisions.sortedWith(
            compareByDescending<Collision> { it.ms }.thenBy {
                it.left.blocks.first().key.toString().padStart(KEY_DIGITS, '0')
            },
        )
        for (collision in largestFirst) {
            over.getOrPut(collision.left) { arrayListOf() }.add(collision.right.speaker)
            over.getOrPut(collision.right) { arrayListOf() }.add(collision.left.speaker)
        }
        return over.mapValues { it.value.distinct() }
    }

    private fun merge(intervals: List<Span>): List<Span> {
        val merged = ArrayList<Span>()
        for (span in intervals.sortedBy { it.start }) {
            val prior = merged.lastOrNull()
            if (prior != null && span.start <= prior.end) {
                merged[merged.lastIndex] = Span(prior.start, maxOf(prior.end, span.end))
            } else {
                merged += span
            }
        }
        return merged
    }

    private fun total(spans: List<Span>): Long = spans.sumOf { it.end - it.start }
}
