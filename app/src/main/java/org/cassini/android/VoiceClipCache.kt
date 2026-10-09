package org.cassini.android

/** At most 45 seconds per speaker, on disk; enough for naming, independent of meeting duration. */
internal class VoiceClipCache(private val audio: PcmFile, restored: org.json.JSONObject? = null) {
    private val selected = linkedMapOf<String, MutableList<PrintWindow>>()
    private val used = mutableMapOf<String, Int>()
    init {
        restored?.keys()?.forEach { id ->
            val list = restored.getJSONArray(id)
            selected[id] = (0 until list.length()).map { i ->
                val v = list.getJSONArray(i); PrintWindow(v.getInt(0), v.getInt(1)).also { require(it.startSample >= 0 && it.endSample > it.startSample && it.endSample <= audio.sampleCount) }
            }.toMutableList()
            used[id] = selected.getValue(id).sumOf { it.endSample - it.startSample }
        }
        require(selected.size <= 8 && used.values.all { it <= 45 * Limits.ASR_RATE })
    }
    fun checkpoint() = org.json.JSONObject().also { json -> selected.forEach { (id, list) ->
        json.put(id, org.json.JSONArray(list.map { org.json.JSONArray(listOf(it.startSample, it.endSample)) }))
    } }
    val windows: Map<String, List<PrintWindow>> get() = selected.mapValues { it.value.toList() }
    fun accept(chunk: PcmAudio, turns: List<SpeakerTurn>, words: List<Word>, startMs: Long) {
        val candidates = Voiceprints.select(turns, words, chunk.sampleRate, chunk.samples.size)
        for ((speaker, windows) in candidates) {
            if (speaker !in selected && selected.size >= 8) continue
            var remaining = (Voiceprints.MAX_SECONDS_PER_SPEAKER * chunk.sampleRate).toInt() - (used[speaker] ?: 0)
            for (window in windows) {
                val start = maxOf(window.startSample, if (startMs > 0) chunk.sampleRate else 0)
                val end = minOf(window.endSample, start + remaining)
                if (end - start < Voiceprints.MIN_SEGMENT_MS * chunk.sampleRate / 1000) continue
                val offset = audio.sampleCount
                val samples = chunk.samples.copyOfRange(start, end)
                audio.append(samples, samples.size)
                selected.getOrPut(speaker) { mutableListOf() }.add(PrintWindow(offset, audio.sampleCount))
                used[speaker] = (used[speaker] ?: 0) + samples.size
                remaining -= samples.size
                if (remaining <= 0) break
            }
        }
    }
}
