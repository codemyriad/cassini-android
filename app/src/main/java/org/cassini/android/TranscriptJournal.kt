package org.cassini.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Append-only checkpoint of settled ASR output, one JSON line per settled piece, so a killed job
 * resumes after the last piece instead of starting over. The first line is a header naming what
 * produced the words; a journal whose header differs from the current run (other audio, model,
 * cutting or policy) is discarded rather than mixed in.
 *
 * Each record is synced before the next piece starts. A crash mid-write leaves at most one torn
 * last line, which [load] cuts off.
 */
class TranscriptJournal(val file: File) {
    data class Header(
        val digest: String, val model: String, val precision: String, val revision: String,
        val vadModel: String, val cutting: String, val policy: String, val sampleRate: Int = Limits.ASR_RATE,
    ) {
        fun json(): JSONObject = JSONObject().put("v", VERSION).put("digest", digest).put("model", model)
            .put("precision", precision).put("revision", revision).put("vadModel", vadModel)
            .put("cutting", cutting).put("policy", policy).put("sampleRate", sampleRate)

        companion object {
            fun fromJson(json: JSONObject): Header? = if (json.optInt("v") != VERSION) null else Header(
                json.getString("digest"), json.getString("model"), json.getString("precision"),
                json.getString("revision"), json.getString("vadModel"), json.getString("cutting"),
                json.getString("policy"), json.getInt("sampleRate"))
        }
    }

    /** What a previous run settled: [words] in order, final up to sample [settledEnd]. */
    internal data class State(val words: List<TimedWord>, val settledEnd: Long)

    private var out: FileOutputStream? = null

    /**
     * Returns what the journal holds for [header], or null when it is missing, unreadable or for
     * another run, in which case the file is deleted. A torn tail is truncated in place.
     */
    internal fun load(header: Header): State? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        var good = 0
        var lineStart = 0
        var found: Header? = null
        val words = ArrayList<TimedWord>()
        var end = 0L
        while (lineStart < bytes.size) {
            val newline = bytes.indexOf('\n'.code.toByte(), lineStart)
            if (newline < 0) break
            val parsed = try {
                val json = JSONObject(String(bytes, lineStart, newline - lineStart, Charsets.UTF_8))
                if (found == null) {
                    found = Header.fromJson(json)
                    if (found != header) { discard(); return null }
                } else {
                    val recordEnd = json.getLong("end")
                    require(recordEnd >= end)
                    val items = json.getJSONArray("words")
                    val record = (0 until items.length()).map { i ->
                        val w = items.getJSONArray(i)
                        TimedWord(Word(w.getString(0), w.getLong(1), w.getLong(2), w.getString(3)), w.getLong(4))
                    }
                    words += record
                    end = recordEnd
                }
                true
            } catch (_: Exception) { false }
            if (!parsed) break
            lineStart = newline + 1
            good = lineStart
        }
        if (found == null) { discard(); return null }
        if (good < bytes.size) RandomAccessFile(file, "rw").use { it.setLength(good.toLong()) }
        return State(words, end)
    }

    private fun ByteArray.indexOf(value: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == value) return i
        return -1
    }

    /** Opens for appending. A fresh journal (or one [load] rejected) gets [header] first. */
    fun open(header: Header) {
        check(out == null)
        file.parentFile?.mkdirs()
        val fresh = !file.exists() || file.length() == 0L
        out = FileOutputStream(file, true)
        if (fresh) writeLine(header.json())
    }

    /** Records [words] as settled, final up to sample [end], and syncs before returning. */
    internal fun append(words: List<TimedWord>, end: Long) {
        val items = JSONArray()
        words.forEach { t ->
            items.put(JSONArray().put(t.word.speaker).put(t.word.startMs).put(t.word.endMs).put(t.word.text).put(t.capMs))
        }
        writeLine(JSONObject().put("end", end).put("words", items))
    }

    private fun writeLine(json: JSONObject) {
        val stream = checkNotNull(out)
        stream.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
        stream.flush()
        stream.fd.sync()
    }

    fun close() { out?.close(); out = null }

    fun discard() { close(); file.delete() }

    companion object { const val VERSION = 1 }
}
