package org.cassini.android

import org.cassini.core.speakers.Voice
import org.cassini.core.speakers.VoiceCodec
import java.io.File
import java.io.IOException

/** Writes a whole file through a synced temporary, so a crash leaves the old or the new content. */
internal fun writeAtomically(file: File, text: String) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.tmp")
    temporary.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
    if (!temporary.renameTo(file)) { temporary.delete(); throw IOException("Could not replace $file") }
}

/** filesDir/voices.json. One instance per process; the worker reads while the main thread writes. The format is [VoiceCodec]. */
internal class VoiceStore(filesDir: File, private val warn: (String, Throwable) -> Unit = { m, e -> android.util.Log.w("Cassini", m, e) }) {
    private val file = File(filesDir, "voices.json")
    private val generationFile = File(filesDir, "voices.generation")

    /** Advances on "forget all", so remember choices made before it are not enrolled after it. */
    @Synchronized fun generation(): Long = try { if (generationFile.exists()) generationFile.readText().trim().toLong() else 0 }
        catch (error: Exception) { warn("Voice generation unreadable", error); -1 }

    @Synchronized fun load(): List<Voice> = try {
        if (!file.exists()) emptyList() else VoiceCodec.decode(file.readText())
    } catch (error: Exception) { warn("Saved voices unreadable", error); emptyList() }

    /** Adds a confirmed print to [voiceId], or creates a person when it is null or unknown. */
    @Synchronized fun enrol(voiceId: String?, name: String, model: String, embedding: FloatArray, seconds: Double, now: Long): Voice {
        val (voices, voice) = VoiceCodec.enrol(load(), voiceId, name, model, embedding, seconds, now)
        save(voices)
        return voice
    }

    @Synchronized fun rename(voiceId: String, name: String): Boolean {
        val voices = load()
        if (voices.none { it.id == voiceId }) return false
        save(voices.map { if (it.id == voiceId) it.copy(name = name) else it }); return true
    }

    @Synchronized fun forget(voiceId: String): Boolean {
        val voices = load()
        if (voices.none { it.id == voiceId }) return false
        save(voices.filter { it.id != voiceId }); return true
    }

    @Synchronized fun clear() { writeAtomically(generationFile, (maxOf(generation(), 0) + 1).toString()); file.delete() }

    /** Undoes one wrong enrolment; the last one forgets the person and returns true. */
    @Synchronized fun unenrol(voiceId: String, embedding: FloatArray): Boolean {
        val voices = load()
        val voice = voices.firstOrNull { it.id == voiceId } ?: return false
        if (voice.count <= 1) return forget(voiceId)
        val undone = VoiceCodec.unenrol(voice, embedding) ?: return false
        save(voices.map { if (it.id == voiceId) undone else it })
        return false
    }

    private fun save(voices: List<Voice>) = writeAtomically(file, VoiceCodec.encode(voices))

    companion object { const val VERSION = VoiceCodec.VERSION; const val MAX_COUNT_WEIGHT = VoiceCodec.MAX_COUNT_WEIGHT }
}
