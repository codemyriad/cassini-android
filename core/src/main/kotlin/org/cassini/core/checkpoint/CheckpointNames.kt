package org.cassini.core.checkpoint

import org.cassini.core.format.SpeakerMerges
import org.cassini.core.speakers.DefaultLabels
import org.cassini.core.speakers.Match
import org.json.JSONArray
import org.json.JSONObject

/**
 * `names.json` beside a checkpoint: the draft speaker state of a running or paused job (architecture section 3.6).
 *
 * The 0.1.0 members: [labels] (names the user gave), [merges] (speaker aliases, as [SpeakerMerges]), [remembered]
 * (speaker to the saved voice id to enrol at adopt; "" enrols a new voice), [automatic] (speaker to the name of its
 * AUTO live match) and [generation]. Added in 0.2: [live], the latest live match per speaker (AUTO, SUGGESTED or
 * CONFIRMED), and [rejected], the voices the user answered "Not X" to, per speaker.
 *
 * Every change goes through one of the pure reducers below ([rename], [remember], [confirm], [reject], [merge],
 * [applyLive]), so that the owner can keep one `AtomicReference` and change it with `updateAndGet`. A reducer takes
 * the speaker id as shown (it resolves [merges] itself) and never throws for an unknown speaker.
 *
 * Invariant kept by the reducers: a CONFIRMED [live] entry exists only while [remembered] holds the same voice.
 */
data class CheckpointNames(
    val labels: Map<String, String>,
    val merges: Map<String, String>,
    val remembered: Map<String, String> = emptyMap(),
    val automatic: Map<String, String> = emptyMap(),
    /** VoiceStore's generation when [remembered] was chosen; a later "forget all" invalidates them. */
    val generation: Long = 0,
    val live: Map<String, Match> = emptyMap(),
    val rejected: Map<String, Set<String>> = emptyMap(),
) {
    /**
     * Whether the job should still try to match [speakerId] live: it has no CONFIRMED match, no user label, and it was
     * not merged into another speaker.
     */
    fun openForLive(speakerId: String): Boolean = speakerId !in merges && !guarded(speakerId)

    /**
     * The `excluded` argument of `VoiceMatcher.match` for [speakers]: each speaker's [rejected] voices, plus the voices
     * CONFIRMED for another speaker (a person names one speaker).
     */
    fun excluded(speakers: Collection<String>): Map<String, Set<String>> {
        val confirmed = live.filterValues { it.state == Match.State.CONFIRMED }
        return speakers.associateWith { id ->
            rejected[id].orEmpty() + confirmed.filterKeys { it != id }.values.map { it.voiceId }
        }.filterValues { it.isNotEmpty() }
    }

    /**
     * The user named [speakerId] [name] in the sheet. A blank name, or one that is a placeholder
     * ([DefaultLabels.isDefault]), removes the label and the remember choice, so the speaker is "Speaker N" again.
     * The automatic name and a live match that is not CONFIRMED are dropped: the user's name wins over them. Whether a
     * CONFIRMED voice stays bound is the caller's choice, through [remember].
     */
    fun rename(speakerId: String, name: String): CheckpointNames {
        val id = resolve(speakerId)
        val trimmed = name.trim()
        val live = live.filterNot { (key, match) -> key == id && match.state != Match.State.CONFIRMED }
        val cleared = copy(automatic = automatic - id, live = live)
        return if (trimmed.isEmpty() || DefaultLabels.isDefault(id, trimmed)) {
            cleared.copy(labels = labels - id).remember(id, null, generation)
        } else {
            cleared.copy(labels = labels + (id to trimmed))
        }
    }

    /**
     * The Remember choice for [speakerId]: [voiceId] is the saved voice to enrol at adopt ("" for a new voice, null
     * for none). When [generation] differs from the stored one, the voices were forgotten since the earlier choices,
     * so those are dropped first (0.1.0 rule). A CONFIRMED live match to another voice is dropped.
     */
    fun remember(speakerId: String, voiceId: String?, generation: Long): CheckpointNames {
        val id = resolve(speakerId)
        val previous = if (generation == this.generation) remembered else emptyMap()
        val remembered = if (voiceId == null) previous - id else previous + (id to voiceId)
        val live = live.filter { (key, match) ->
            match.state != Match.State.CONFIRMED || remembered[key] == match.voiceId
        }
        return copy(remembered = remembered, generation = generation, live = live)
    }

    /**
     * "Yes, Luca" (or the sheet bound to a saved voice): [speakerId] is the saved voice [voiceId] called [name].
     * Sets the label, the remember choice (with [generation]), and a CONFIRMED live match with [score]; drops the
     * automatic name and a rejection of that voice. Another speaker's unconfirmed live match to the same voice is
     * dropped, because a person names one speaker.
     */
    fun confirm(speakerId: String, voiceId: String, name: String, generation: Long, score: Float): CheckpointNames {
        val id = resolve(speakerId)
        val base = remember(id, voiceId, generation)
        val others = base.live.filter { (key, match) ->
            key != id && match.voiceId == voiceId && match.state != Match.State.CONFIRMED
        }.keys
        val rejections = base.rejected[id].orEmpty() - voiceId
        return base.copy(
            labels = base.labels + (id to name.trim()),
            automatic = base.automatic - id - others,
            live = base.live - others + (id to Match(voiceId, score, Match.State.CONFIRMED)),
            rejected = if (rejections.isEmpty()) base.rejected - id else base.rejected + (id to rejections),
        )
    }

    /**
     * "Not Luca": [voiceId] is never suggested for [speakerId] again. A live match to it, its automatic name and a
     * remember choice of it are dropped. When [voiceName] is given, a label or automatic name equal to it goes too, so
     * the speaker is "Speaker N" again. Nothing is unenrolled: a draft has enrolled nothing yet.
     */
    fun reject(speakerId: String, voiceId: String, voiceName: String? = null): CheckpointNames {
        val id = resolve(speakerId)
        val matched = live[id]?.voiceId == voiceId
        val named = voiceName != null && automatic[id] == voiceName
        val base = if (remembered[id] == voiceId) remember(id, null, generation) else this
        return base.copy(
            labels = if (voiceName != null && base.labels[id] == voiceName) base.labels - id else base.labels,
            automatic = if (matched || named) base.automatic - id else base.automatic,
            live = if (matched) base.live - id else base.live,
            rejected = base.rejected + (id to base.rejected[id].orEmpty() + voiceId),
        )
    }

    /**
     * Shows everything [source] said as [target] ([SpeakerMerges.merge]; throws when they are already one speaker).
     * The source's label, remember choice, automatic name, live match and rejections go. [targetLabel], when given,
     * becomes the target's label (0.1.0 kept the target's shown name that way).
     */
    fun merge(source: String, target: String, targetLabel: String? = null): CheckpointNames {
        val from = resolve(source)
        val into = resolve(target)
        val merged = SpeakerMerges.merge(merges, source, target)
        val labels = (labels - from).let { if (targetLabel.isNullOrBlank()) it else it + (into to targetLabel.trim()) }
        return copy(
            labels = labels,
            merges = merged,
            remembered = remembered - from,
            automatic = automatic - from,
            live = live - from,
            rejected = rejected - from,
        )
    }

    /**
     * One round of live voice matching: [matches] is `VoiceMatcher.match` over every speaker with a print, so a
     * speaker without an entry has no match now. [names] maps voice ids to names, for the AUTO matches' [automatic]
     * names. Speakers that are not [openForLive] keep their state. A match is ignored when the user rejected that
     * voice for that speaker, when the voice is CONFIRMED for another speaker, when it is a note-local voice
     * ([NOTE_VOICE_PREFIX]: the label already carries the name) or when it is neither AUTO nor SUGGESTED.
     *
     * A round computed from the state before a user action gives the same result applied before or after it.
     */
    fun applyLive(matches: Map<String, Match>, names: Map<String, String>): CheckpointNames {
        val confirmed = live.values.filter { it.state == Match.State.CONFIRMED }.map { it.voiceId }.toSet()
        val accepted = matches.filter { (id, match) ->
            openForLive(id) && match.voiceId !in rejected[id].orEmpty() && match.voiceId !in confirmed &&
                !match.voiceId.startsWith(NOTE_VOICE_PREFIX) &&
                (match.state == Match.State.AUTO || match.state == Match.State.SUGGESTED)
        }
        val autoNames = accepted.filterValues { it.state == Match.State.AUTO }
            .mapNotNull { (id, match) -> names[match.voiceId]?.let { id to it } }
        return copy(
            automatic = automatic.filterKeys { !openForLive(it) } + autoNames,
            live = live.filterKeys { !openForLive(it) } + accepted,
        )
    }

    private fun guarded(id: String) = live[id]?.state == Match.State.CONFIRMED || id in labels

    private fun resolve(id: String) = SpeakerMerges.resolve(id, merges)

    companion object {
        /** The members 0.1.0 writes. */
        val KEYS = setOf("labels", "merges", "remembered", "automatic", "generation")

        /** The members 0.2 adds; they are written only when not empty. */
        val OPTIONAL_KEYS = setOf("live", "rejected")

        /** Ids of the note-local voices that carry names over in Transcribe again; never a live match. */
        const val NOTE_VOICE_PREFIX = "note:"

        fun decode(json: JSONObject) = CheckpointNames(
            CheckpointData.strings(json.getJSONObject("labels")),
            CheckpointData.strings(json.getJSONObject("merges")),
            CheckpointData.strings(json.optJSONObject("remembered") ?: JSONObject()),
            CheckpointData.strings(json.optJSONObject("automatic") ?: JSONObject()),
            json.optLong("generation", 0),
            live(json.optJSONObject("live") ?: JSONObject()),
            rejected(json.optJSONObject("rejected") ?: JSONObject()),
        )

        fun encode(names: CheckpointNames): JSONObject = JSONObject().put("labels", JSONObject(names.labels))
            .put("merges", JSONObject(names.merges)).put("remembered", JSONObject(names.remembered))
            .put("automatic", JSONObject(names.automatic)).put("generation", names.generation)
            .apply {
                if (names.live.isNotEmpty()) put("live", json(names.live))
                if (names.rejected.isNotEmpty()) put("rejected", rejectedJson(names.rejected))
            }

        private fun live(json: JSONObject): Map<String, Match> = json.keys().asSequence().associateWith { id ->
            val match = json.getJSONObject(id)
            Match(
                match.getString("voice"),
                match.getDouble("score").toFloat(),
                Match.State.valueOf(match.getString("state")),
            )
        }

        private fun json(live: Map<String, Match>): JSONObject = JSONObject().also { json ->
            live.forEach { (id, match) ->
                json.put(
                    id,
                    JSONObject().put("voice", match.voiceId)
                        // The float's shortest decimal form, so 0.61f is written as 0.61.
                        .put("score", match.score.toString().toDouble())
                        .put("state", match.state.name),
                )
            }
        }

        private fun rejected(json: JSONObject): Map<String, Set<String>> = json.keys().asSequence().associateWith { id ->
            val voices = json.getJSONArray(id)
            (0 until voices.length()).mapTo(LinkedHashSet()) { voices.getString(it) }
        }

        private fun rejectedJson(rejected: Map<String, Set<String>>): JSONObject = JSONObject().also { json ->
            rejected.forEach { (id, voices) -> json.put(id, JSONArray(voices.toList())) }
        }
    }
}
