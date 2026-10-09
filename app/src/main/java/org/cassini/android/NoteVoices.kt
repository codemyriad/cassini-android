package org.cassini.android

import org.cassini.core.speakers.Match
import org.cassini.core.speakers.NoteVoiceCodec
import org.cassini.core.speakers.SpeakerPrint
import java.io.File

/**
 * filesDir/voiceprints/<meetingId>.json: each note's speaker prints and match state keyed by "<variantId>/<speakerId>",
 * never part of a document. The format is [NoteVoiceCodec].
 */
internal class NoteVoiceStore(filesDir: File, private val warn: (String, Throwable) -> Unit = { m, e -> android.util.Log.w("Cassini", m, e) }) {
    private val directory = File(filesDir, "voiceprints")

    private fun file(meetingId: String): File = File(directory, NoteVoiceCodec.fileName(meetingId))

    /** One transcript variant's prints by speaker id; diarization reuses ids like spk_1 in every variant. */
    @Synchronized fun load(meetingId: String, variantId: String): Map<String, SpeakerPrint> =
        read(file(meetingId))?.second.orEmpty().filterKeys { it.startsWith(prefix(variantId)) }.mapKeys { it.key.removePrefix(prefix(variantId)) }

    /** Stores a variant's prints, replacing that variant's earlier ones; other variants stay. A print from another model resets the file. */
    @Synchronized fun merge(meetingId: String, variantId: String, model: String, prints: Map<String, SpeakerPrint>) {
        if (prints.isEmpty()) return
        val existing = read(file(meetingId))?.takeIf { it.first == model }?.second.orEmpty().filterKeys { !it.startsWith(prefix(variantId)) }
        write(meetingId, model, existing + prints.mapKeys { prefix(variantId) + it.key })
    }

    @Synchronized fun setMatch(meetingId: String, variantId: String, speakerId: String, match: Match?) {
        val (model, prints) = read(file(meetingId)) ?: return
        val key = prefix(variantId) + speakerId
        val print = prints[key] ?: return
        write(meetingId, model, prints + (key to print.copy(match = match)))
    }

    @Synchronized fun delete(meetingId: String) { file(meetingId).delete() }

    /** Removes a forgotten person's matches from every note. Worker thread: it scans all notes. */
    @Synchronized fun forget(voiceId: String) {
        for (note in directory.listFiles().orEmpty().filter { it.extension == "json" }) {
            val (model, prints) = read(note) ?: continue
            if (prints.values.none { it.match?.voiceId == voiceId }) continue
            write(note.nameWithoutExtension, model, prints.mapValues { (_, p) -> if (p.match?.voiceId == voiceId) p.copy(match = null) else p })
        }
    }

    /** How many notes carry an applied (automatic or confirmed) match for each person. Worker thread: it scans all notes. */
    @Synchronized fun noteCounts(): Map<String, Int> = directory.listFiles().orEmpty().filter { it.extension == "json" }
        .flatMap { note -> read(note)?.second?.values.orEmpty().mapNotNull { p -> p.match?.takeIf { it.state == Match.State.AUTO || it.state == Match.State.CONFIRMED }?.voiceId }.distinct() }
        .groupingBy { it }.eachCount()

    @Synchronized fun clear() { directory.deleteRecursively() }

    private fun prefix(variantId: String): String = NoteVoiceCodec.prefix(variantId)

    private fun read(file: File): Pair<String, Map<String, SpeakerPrint>>? = try {
        if (!file.exists()) null else NoteVoiceCodec.decode(file.readText())
    } catch (error: Exception) { warn("Note voiceprints unreadable: ${file.name}", error); null }

    private fun write(meetingId: String, model: String, prints: Map<String, SpeakerPrint>) {
        writeAtomically(file(meetingId), NoteVoiceCodec.encode(model, prints))
    }

    companion object { const val VERSION = NoteVoiceCodec.VERSION }
}
