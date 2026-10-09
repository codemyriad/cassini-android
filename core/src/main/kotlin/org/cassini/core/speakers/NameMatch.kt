package org.cassini.core.speakers

import java.text.Normalizer
import java.util.Locale

/**
 * Name comparison and the voice list of the Name a speaker sheet (features.md section 4, items 3 and 4).
 *
 * Names are compared after [normalise]: "Élodie", " elodie " and "ELODIE" are the same name.
 */
object NameMatch {
    private val combiningMarks = Regex("\\p{Mn}+")
    private val whitespace = Regex("\\s+")

    /** One row of "Saved voices, closest first". [score] is null for a stale voice or without a print. */
    data class Ranked(val voice: Voice, val score: Float?, val stale: Boolean, val likely: Boolean)

    /**
     * [name] trimmed, with runs of white space as one space, decomposed (NFD) without combining marks, and case
     * folded (upper case, then lower case, so that "ß" and "SS" agree). Locale independent.
     */
    fun normalise(name: String): String {
        val collapsed = name.trim().replace(whitespace, " ")
        val stripped = Normalizer.normalize(collapsed, Normalizer.Form.NFD).replace(combiningMarks, "")
        return stripped.uppercase(Locale.ROOT).lowercase(Locale.ROOT)
    }

    /** The first word of [name], normalised. */
    fun firstWord(name: String): String = normalise(name).substringBefore(' ')

    /**
     * The saved voices that [typed] may mean: the normalised typed name equals a voice's full name or its first
     * word. "Anna" matches "Anna Ricci"; "Anna Ricci" does not match "Anna". A blank name matches nothing.
     */
    fun samePersonCandidates(typed: String, voices: List<Voice>): List<Voice> {
        val name = normalise(typed)
        if (name.isEmpty()) return emptyList()
        return voices.filter { normalise(it.name) == name || firstWord(it.name) == name }
    }

    /** The candidate to ask about: the highest score (a missing score is lowest), ties in [alphabetical] order. */
    fun pick(candidates: List<Voice>, scores: Map<String, Float>): Voice? =
        candidates.sortedWith(
            compareByDescending<Voice> { scores[it.id] ?: Float.NEGATIVE_INFINITY }.then(alphabetical),
        ).firstOrNull()

    /** [samePersonCandidates] then [pick]. */
    fun samePerson(typed: String, voices: List<Voice>, scores: Map<String, Float>): Voice? =
        pick(samePersonCandidates(typed, voices), scores)

    /**
     * The saved voices for the sheet. With a [print]: current voices by cosine score (highest first, ties in
     * [alphabetical] order), then stale voices ([VoiceStaleness]) in alphabetical order without a score. Without a
     * print: current voices, then stale voices, each in alphabetical order. Only the first row can be "Likely", and
     * only when its score is at least [suggest] (`Thresholds.suggest`, 0.50 for the pinned model).
     */
    fun rank(print: FloatArray?, voices: List<Voice>, suggest: Float): List<Ranked> {
        val (stale, current) = voices.partition { VoiceStaleness.isStale(it) }
        val scored = current.map { voice ->
            val score = print?.takeIf { it.size == voice.mean.size }?.let { Voiceprints.cosine(it, voice.mean) }
            Ranked(voice, score, stale = false, likely = false)
        }.sortedWith(
            compareByDescending<Ranked> { it.score ?: Float.NEGATIVE_INFINITY }.thenBy(alphabetical) { it.voice },
        )
        val rows = scored + stale.sortedWith(alphabetical).map { Ranked(it, null, stale = true, likely = false) }
        val top = rows.firstOrNull()
        val topScore = top?.score
        return if (top != null && topScore != null && topScore >= suggest) {
            listOf(top.copy(likely = true)) + rows.drop(1)
        } else {
            rows
        }
    }

    /** Alphabetical by normalised name, then by the name as written, then by id, so the order is total. */
    val alphabetical: Comparator<Voice> =
        compareBy<Voice> { normalise(it.name) }.thenBy { it.name }.thenBy { it.id }
}
