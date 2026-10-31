package org.cassini.data.checkpoint

import org.cassini.core.audio.Limits
import org.cassini.core.checkpoint.CheckpointState
import org.cassini.core.checkpoint.WordLog
import org.cassini.core.checkpoint.WordLogRef
import org.cassini.core.format.Word
import org.cassini.data.notes.NoteFiles
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * `work/words.log` of checkpoint version 2 (docs/architecture.md section 3.5): the settled words of a running job,
 * appended once each. A save appends the words that settled since the last save, fsyncs the log, and only then
 * writes `checkpoint.json` with the new prefix ([WordLogRef]). Thus a checkpoint never names bytes that are not on
 * the disk, and the bytes that each window writes do not grow with the recording.
 *
 * The first save of a note in a process writes the whole log again, through a temporary file and a rename: the
 * process does not know yet whether the file holds the prefix of these words. The written prefix of an older
 * checkpoint stays the same, because settled words never change. A save whose words do not continue the log (a
 * new run of the note) also writes it again. Callers hold the note's lock.
 *
 * [save] writes the log and returns a [Pending] prefix. The caller writes `checkpoint.json` with it and then calls
 * [commit]. If that write fails, the cursor stays where it was: the next save writes over the extra bytes.
 */
internal class WordLogFile {
    /** What this process last committed for each note. */
    private val cursors = ConcurrentHashMap<String, Cursor>()

    private class Cursor(val ref: WordLogRef, val last: Word?)

    /** A prefix that [save] wrote to the log and that a checkpoint can name; [commit] makes it the cursor. */
    class Pending internal constructor(val ref: WordLogRef, internal val last: Word?)

    /** Writes the settled words of [state] to the log of [noteId] in [work] and returns the prefix to commit. */
    fun save(noteId: String, work: File, state: CheckpointState): Pending {
        val chunks = state.recognition.chunks
        val words = chunks.words
        val log = File(work, WorkFiles.WORD_LOG)
        val cursor = cursors[noteId]?.takeIf { continues(it, words, log) }
        val from = cursor?.ref?.count ?: 0
        val settled = WordLog.settled(words, chunks.start * MS_PER_SECOND / Limits.ASR_RATE, from)
        val ref = when {
            cursor != null && settled == from -> cursor.ref
            cursor != null -> append(work, log, cursor.ref, WordLog.lines(words, from, settled), settled)
            else -> rewrite(work, log, WordLog.lines(words, 0, settled), settled)
        }
        return Pending(ref, words.getOrNull(ref.count - 1))
    }

    /** The checkpoint that names [pending] is on the disk: the next save continues from it. */
    fun commit(noteId: String, pending: Pending) {
        cursors[noteId] = Cursor(pending.ref, pending.last)
    }

    /** Forgets what this process committed for [noteId]: its work directory went, or a new run starts. */
    fun forget(noteId: String) {
        cursors.remove(noteId)
    }

    /** The cursor is still the prefix of [words]: same last word, and the log still holds its bytes. */
    private fun continues(cursor: Cursor, words: List<Word>, log: File): Boolean {
        val count = cursor.ref.count
        return count <= words.size && (count == 0 || words[count - 1] == cursor.last) && log.length() >= cursor.ref.bytes
    }

    private fun append(work: File, log: File, base: WordLogRef, lines: ByteArray, count: Int): WordLogRef {
        val created = !log.exists()
        RandomAccessFile(log, "rw").use { file ->
            // Bytes past the committed prefix are the rest of a write that failed: write over them.
            file.setLength(base.bytes)
            file.seek(base.bytes)
            file.write(lines)
            file.fd.sync()
        }
        // A new file needs its directory entry on the disk too.
        if (created) NoteFiles.syncDirectory(work)
        return WordLogRef(count, base.bytes + lines.size)
    }

    private fun rewrite(work: File, log: File, lines: ByteArray, count: Int): WordLogRef {
        if (count == 0) {
            if (log.exists() && !log.delete()) throw IOException("Cannot delete $log")
            return WordLogRef.EMPTY
        }
        val temporary = File(work, WorkFiles.WORD_LOG + TEMPORARY)
        FileOutputStream(temporary).use { output ->
            output.write(lines)
            output.fd.sync()
        }
        if (!temporary.renameTo(log)) throw IOException("Cannot rename $temporary")
        NoteFiles.syncDirectory(work)
        return WordLogRef(count, lines.size.toLong())
    }

    companion object {
        private const val MS_PER_SECOND = 1000L
        private const val TEMPORARY = ".tmp"

        /**
         * The words of the `wordLog` prefix of the checkpoint [json] in [work]. A prefix that cannot be a log (more
         * than 2 GiB) throws [IllegalArgumentException], as damaged content does.
         */
        fun read(work: File, json: JSONObject): List<Word> {
            val ref = CheckpointState.wordLog(json)
            if (ref.count == 0) return emptyList()
            val log = File(work, WorkFiles.WORD_LOG)
            require(ref.bytes <= Int.MAX_VALUE) { "Word log prefix of ${ref.bytes} bytes" }
            val bytes = ByteArray(ref.bytes.toInt())
            RandomAccessFile(log, "r").use { it.readFully(bytes) }
            return WordLog.read(bytes, ref.count)
        }

        /** The length of `words.log` in [work], for `CheckpointState.Expected`. */
        fun length(work: File): Long = File(work, WorkFiles.WORD_LOG).length()
    }
}
