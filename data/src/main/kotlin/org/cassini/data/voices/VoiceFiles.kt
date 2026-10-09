package org.cassini.data.voices

import android.util.AtomicFile
import android.util.Log
import org.cassini.core.speakers.NoteVoiceCodec
import org.cassini.core.speakers.SpeakerPrint
import org.cassini.core.speakers.Voice
import org.cassini.core.speakers.VoiceCodec
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Why a voice file is kept as it is and not written (docs/architecture.md, section 4.5). */
enum class VoiceProblem {
    /** The file was written by a newer Cassini (its `version` is higher than the codec knows). It stays, read-only. */
    NEWER,

    /** The file could not be read (an I/O error). It stays as it is, read-only, and the next start tries again. */
    UNREADABLE,

    /** The file was not valid JSON for its codec. It was renamed `<name>.corrupt-<epoch ms>` and reads as empty. */
    DAMAGED,
}

/** What reading one voice file gave. */
internal sealed interface FileRead<out T> {
    data object Missing : FileRead<Nothing>

    data class Loaded<T>(val value: T) : FileRead<T>

    /** The file stays in place and must not be written ([VoiceProblem.NEWER] or [VoiceProblem.UNREADABLE]). */
    data class Kept(val problem: VoiceProblem) : FileRead<Nothing>

    /** The file did not parse and was renamed to [renamedTo] (null when the rename failed too). */
    data class Damaged(val renamedTo: File?) : FileRead<Nothing>
}

/** The model and prints of one `voices/notes/<noteId>.json` (NoteVoiceCodec version 2). */
internal data class NotePrints(val model: String, val prints: Map<String, SpeakerPrint>)

/**
 * A draft choice to enrol at adopt: the speaker's print goes to the saved voice [voiceId] called [name]. When [isNew]
 * is set, [voiceId] is an id reserved for a new person, so a repeated adopt never creates a second person.
 */
data class Confirmation(val voiceId: String, val name: String, val isNew: Boolean)

/**
 * `voices/pending/<noteId>.json`: the final prints of a sealed job awaiting adopt. It is a NoteVoiceCodec version 2
 * file with two more members, `generation` (the voice generation the job matched against) and `confirmations` (the
 * draft's Remember choices by speaker id), which the codec ignores.
 */
internal data class PendingPrints(
    val model: String,
    val prints: Map<String, SpeakerPrint>,
    val generation: Long,
    val confirmations: Map<String, Confirmation>,
)

/** Reads and writes the files under `voices/`. The caller holds the file's lock. */
// One reader and one writer per file kind, plus the shared AtomicFile helpers.
@Suppress("TooManyFunctions")
internal class VoiceFiles(private val clock: () -> Long) {
    fun readPeople(file: File): FileRead<List<Voice>> = read(file) { text ->
        try {
            VoiceCodec.decode(text)
        } catch (error: VoiceCodec.UnsupportedVersion) {
            throw if (error.found > VoiceCodec.VERSION) NewerFile() else error
        }
    }

    fun writePeople(file: File, voices: List<Voice>) = writeText(file, VoiceCodec.encode(voices))

    fun readNote(file: File): FileRead<NotePrints> = read(file) { text ->
        decodeNote(text).let { NotePrints(it.first, it.second) }
    }

    fun writeNote(file: File, prints: NotePrints) = writeText(file, NoteVoiceCodec.encode(prints.model, prints.prints))

    fun readPending(file: File): FileRead<PendingPrints> = read(file) { text ->
        val (model, prints) = decodeNote(text)
        val json = JSONObject(text)
        val confirmations = json.optJSONObject(CONFIRMATIONS) ?: JSONObject()
        PendingPrints(
            model = model,
            prints = prints,
            // A file without a generation never matches the current one, so it is dropped without merging.
            generation = json.optLong(GENERATION, NO_GENERATION),
            confirmations = confirmations.keys().asSequence().associateWith { speaker ->
                val entry = confirmations.getJSONObject(speaker)
                Confirmation(entry.getString(VOICE), entry.getString(NAME), entry.optBoolean(NEW, false))
            },
        )
    }

    fun writePending(file: File, pending: PendingPrints) {
        val json = JSONObject(NoteVoiceCodec.encode(pending.model, pending.prints))
            .put(GENERATION, pending.generation)
        if (pending.confirmations.isNotEmpty()) {
            json.put(
                CONFIRMATIONS,
                JSONObject().also { all ->
                    pending.confirmations.forEach { (speaker, choice) ->
                        all.put(
                            speaker,
                            JSONObject().put(VOICE, choice.voiceId).put(NAME, choice.name).put(NEW, choice.isNew),
                        )
                    }
                },
            )
        }
        writeText(file, json.toString())
    }

    /** The generation in [file]; 0 when there is none. A file that does not hold a number also reads as 0. */
    fun readGeneration(file: File): Long {
        if (!exists(file)) return 0
        return try {
            String(AtomicFile(file).readFully(), Charsets.UTF_8).trim().toLong()
        } catch (error: IOException) {
            Log.w(TAG, "Cannot read $file", error)
            0
        } catch (error: NumberFormatException) {
            Log.w(TAG, "Cannot parse $file", error)
            0
        }
    }

    fun writeGeneration(file: File, generation: Long) = writeText(file, generation.toString())

    /** Whether [file] or the backup of an interrupted legacy `AtomicFile` write exists. */
    fun exists(file: File): Boolean = file.isFile || File(file.path + BACKUP).isFile

    /** Deletes [file] and its `AtomicFile` leftovers; false when one of them is still there. */
    fun delete(file: File): Boolean = listOf(file, File(file.path + BACKUP), File(file.path + NEW_SUFFIX))
        .all { !it.exists() || it.delete() }

    /** Writes [text] through [AtomicFile]: a new file, fsync, then a rename over the old one. */
    fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        var written = false
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            written = true
        } finally {
            if (!written) atomic.failWrite(output)
        }
    }

    private fun decodeNote(text: String): Pair<String, Map<String, SpeakerPrint>> = try {
        NoteVoiceCodec.decode(text)
    } catch (error: NoteVoiceCodec.UnsupportedVersion) {
        throw if (error.newer) NewerFile() else error
    }

    private fun <T> read(file: File, decode: (String) -> T): FileRead<T> {
        if (!exists(file)) return FileRead.Missing
        val text = try {
            String(AtomicFile(file).readFully(), Charsets.UTF_8)
        } catch (error: IOException) {
            Log.w(TAG, "Cannot read $file", error)
            null
        }
        return if (text == null) FileRead.Kept(VoiceProblem.UNREADABLE) else parse(file, text, decode)
    }

    private fun <T> parse(file: File, text: String, decode: (String) -> T): FileRead<T> = try {
        FileRead.Loaded(decode(text))
    } catch (_: NewerFile) {
        Log.w(TAG, "$file is from a newer Cassini; it stays read-only")
        FileRead.Kept(VoiceProblem.NEWER)
    } catch (error: JSONException) {
        FileRead.Damaged(setAside(file, error))
    } catch (error: IllegalArgumentException) {
        // Also an older version, a bad enum name or a mean whose size is not its dim.
        FileRead.Damaged(setAside(file, error))
    }

    /** Renames a damaged [file] to `<name>.corrupt-<epoch ms>`; it is never deleted, so nothing is lost. */
    private fun setAside(file: File, error: Exception): File? {
        val target = File(file.path + CORRUPT + clock())
        Log.w(TAG, "Cannot parse $file; renaming it to ${target.name}", error)
        File(file.path + BACKUP).takeIf { it.exists() && !file.exists() }?.renameTo(file)
        return target.takeIf { file.renameTo(it) }
    }

    /** A file whose version is newer than its codec. */
    private class NewerFile : RuntimeException()

    companion object {
        const val CORRUPT = ".corrupt-"
        const val NO_GENERATION = -1L
        private const val BACKUP = ".bak"
        private const val NEW_SUFFIX = ".new"
        private const val GENERATION = "generation"
        private const val CONFIRMATIONS = "confirmations"
        private const val VOICE = "voice"
        private const val NAME = "name"
        private const val NEW = "new"
        private const val TAG = "VoiceFiles"
    }
}
