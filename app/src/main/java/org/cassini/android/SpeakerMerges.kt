package org.cassini.android

import org.json.JSONObject

/** Recording-local aliases. Recognition keeps its native IDs; display and sealing resolve them. */
internal object SpeakerMerges {
    fun resolve(id: String, aliases: Map<String, String>): String {
        var current = id
        val visited = mutableSetOf<String>()
        while (current in aliases) {
            require(visited.add(current)) { "Speaker merge cycle" }
            current = aliases.getValue(current)
        }
        return current
    }

    fun merge(aliases: Map<String, String>, source: String, target: String): Map<String, String> {
        val from = resolve(source, aliases)
        val into = resolve(target, aliases)
        require(from != into) { "Speakers are already merged" }
        val next = aliases + (from to into)
        return next.mapValues { (_, id) -> resolve(id, next) }
    }

    fun apply(transcript: Transcript, aliases: Map<String, String>): Transcript =
        if (aliases.isEmpty()) transcript else transcript.copy(words = transcript.words.map {
            it.copy(speaker = resolve(it.speaker, aliases))
        })

    fun processing(previous: JSONObject?, aliases: Map<String, String>, sourceVariant: String? = null): JSONObject =
        JSONObject(previous?.toString() ?: "{}").apply {
            put("x-speakerMerges", JSONObject(aliases))
            put("x-speakerAssignmentEditedBy", "user")
            sourceVariant?.let { put("x-derivedFromTranscript", it) }
        }

    /** Merge selected clips without increasing the per-speaker voiceprint budget. */
    fun windows(windows: Map<String, List<PrintWindow>>, aliases: Map<String, String>): Map<String, List<PrintWindow>> {
        val result = linkedMapOf<String, MutableList<PrintWindow>>()
        val used = mutableMapOf<String, Int>()
        for ((id, spans) in windows) {
            val speaker = resolve(id, aliases)
            for (span in spans) {
                val remaining = (Voiceprints.MAX_SECONDS_PER_SPEAKER * Limits.ASR_RATE).toInt() - (used[speaker] ?: 0)
                val end = minOf(span.endSample, span.startSample + remaining)
                if (end - span.startSample < Voiceprints.MIN_SEGMENT_MS * Limits.ASR_RATE / 1000) continue
                result.getOrPut(speaker) { mutableListOf() }.add(PrintWindow(span.startSample, end))
                used[speaker] = (used[speaker] ?: 0) + end - span.startSample
            }
        }
        return result
    }
}
