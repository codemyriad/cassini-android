package org.cassini.android

/** Assigns saved people to a note's speakers: each person and each speaker at most once, best score first. */
internal object VoiceMatcher {
    fun match(speakers: Map<String, FloatArray>, voices: List<Voice>, model: String, t: Thresholds): Map<String, Match> {
        val usable = voices.filter { it.model == model }
        val scores = speakers.flatMap { (id, print) ->
            usable.filter { it.mean.size == print.size }.map { Triple(id, it.id, Voiceprints.cosine(print, it.mean)) }
        }
        val result = LinkedHashMap<String, Match>()
        val taken = HashSet<String>()
        for ((speaker, voice, score) in scores.sortedByDescending { it.third }) {
            if (score < t.suggest || speaker in result || voice in taken) continue
            // Automatic only when neither another person for this speaker nor another speaker for this person comes close.
            val rival = scores.filter { (s, v, _) -> (s == speaker) != (v == voice) && (s == speaker || v == voice) }.maxOfOrNull { it.third }
            val auto = score >= t.auto && (rival == null || score - rival >= t.margin)
            result[speaker] = Match(voice, score, if (auto) Match.State.AUTO else Match.State.SUGGESTED)
            taken.add(voice)
        }
        return result
    }
}
