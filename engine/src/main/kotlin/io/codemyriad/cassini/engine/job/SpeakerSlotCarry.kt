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
    val speakers = existing.manifest!!.getJSONArray("speakers")
    val oldSlots = (0 until speakers.length()).associate {
        val id = speakers.getJSONObject(it).getString("id")
        id to existing.speakerColourIndex(id)!!
    }
    val carried = carriedSlots(ordered, oldSlots, names, matches, previous)
    val firstSeparation = carried.isEmpty() && existing.isUndifferentiated()
    return allocateUnmatched(ordered, carried, if (firstSeparation) emptySet() else oldSlots.values.toSet())
}

private fun carriedSlots(
    ordered: List<String>,
    oldSlots: Map<String, Long>,
    names: CheckpointNames,
    matches: Map<String, Match>,
    previous: Map<String, SpeakerPrint>,
): Map<String, Long> {
    val byVoice = previous.entries.filter { it.key in oldSlots && it.value.match?.carriesIdentity() == true }
        .groupBy { it.value.match!!.voiceId }.mapNotNull { (voice, entries) ->
            entries.singleOrNull()?.let { voice to oldSlots.getValue(it.key) }
        }.toMap()
    return ordered.mapNotNull { id ->
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
}

private fun CassiniDocument.isUndifferentiated(): Boolean {
    val speech = manifest!!.optJSONObject("provenance")?.optJSONObject("speechToText")
    return variants.all { variant ->
        variant.transcript?.words?.map { it.speaker }?.distinct()?.size == 1 &&
            speech?.optJSONObject(variant.id)?.has("x-speakerDiarization") != true
    }
}

private fun allocateUnmatched(
    ordered: List<String>,
    carried: Map<String, Long>,
    occupiedSlots: Set<Long>,
): Map<String, Long> {
    val occupied = occupiedSlots.toMutableSet()
    var next = occupied.maxOrNull()?.let { if (it == MAX_SAFE_INTEGER) 0 else it + 1 } ?: 0L
    return ordered.associateWith { id ->
        carried[id] ?: run {
            while (next in occupied) next = if (next == MAX_SAFE_INTEGER) 0 else next + 1
            next.also {
                occupied += it
                next = if (it == MAX_SAFE_INTEGER) 0 else it + 1
            }
        }
    }
}

private fun Match.carriesIdentity(): Boolean = state == Match.State.AUTO || state == Match.State.CONFIRMED
