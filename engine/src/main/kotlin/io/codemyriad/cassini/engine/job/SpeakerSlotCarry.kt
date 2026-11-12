package io.codemyriad.cassini.engine.job

import io.codemyriad.cassini.core.checkpoint.CheckpointNames
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.MAX_SAFE_INTEGER
import io.codemyriad.cassini.core.format.SpeakerMerges
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.format.speakersByFirstSpeech
import io.codemyriad.cassini.core.speakers.Match
import io.codemyriad.cassini.core.speakers.SpeakerPrint

/** Resolve identities before assigning unmatched raw speakers, so draft merges cannot renumber their targets. */
internal fun speakerSlotsForRun(
    existing: CassiniDocument?,
    words: List<Word>,
    names: CheckpointNames,
    matches: Map<String, Match>,
    previous: Map<String, SpeakerPrint>,
): Map<String, Long> {
    val ordered = words.speakersByFirstSpeech()
    if (existing == null) return ordered.withIndex().associate { it.value to it.index.toLong() }
    val manifest = existing.manifest!!
    val speakers = manifest.getJSONArray("speakers")
    val oldSlots = (0 until speakers.length()).associate {
        val id = speakers.getJSONObject(it).getString("id")
        id to existing.speakerColourIndex(id)!!
    }
    val byVoice = previous.entries.filter { it.key in oldSlots && it.value.match?.carriesIdentity() == true }
        .groupBy { it.value.match!!.voiceId }.mapNotNull { (voice, entries) ->
            entries.singleOrNull()?.let { voice to oldSlots.getValue(it.key) }
        }.toMap()
    val carried = ordered.mapNotNull { id ->
        val target = SpeakerMerges.resolve(id, names.merges)
        val match = (names.live[target] ?: matches[target])?.takeIf { it.carriesIdentity() }
        val slot = match?.voiceId?.let { voice ->
            if (voice.startsWith(CheckpointNames.NOTE_VOICE_PREFIX)) {
                oldSlots[voice.removePrefix(CheckpointNames.NOTE_VOICE_PREFIX)]
            } else {
                byVoice[voice]
            }
        }
        slot?.let { id to it }
    }.toMap()
    val speech = manifest.optJSONObject("provenance")?.optJSONObject("speechToText")
    val firstSeparation = carried.isEmpty() && existing.variants.all { variant ->
        variant.transcript?.words?.map { it.speaker }?.distinct()?.size == 1 &&
            speech?.optJSONObject(variant.id)?.has("x-speakerDiarization") != true
    }
    val occupied = if (firstSeparation) mutableSetOf() else oldSlots.values.toMutableSet()
    var next = if (firstSeparation) 0L else oldSlots.values.maxOrNull()?.let {
        if (it == MAX_SAFE_INTEGER) 0 else it + 1
    } ?: 0L
    return ordered.associateWith { id ->
        carried[id] ?: run {
            while (next in occupied) next = if (next == MAX_SAFE_INTEGER) 0 else next + 1
            next.also { occupied += it; next = if (it == MAX_SAFE_INTEGER) 0 else it + 1 }
        }
    }
}

private fun Match.carriesIdentity(): Boolean = state == Match.State.AUTO || state == Match.State.CONFIRMED
