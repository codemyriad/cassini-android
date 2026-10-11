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
 */
internal class WordLogFile {
    /** What this process last committed for each note. */
    private val cursors = ConcurrentHashMap<String, Cursor>()

    private class Cursor(val ref: WordLogRef, val last: Word?)

    /** Writes the settled words of [state] to the log of [noteId] in [work] and returns the prefix to commit. */
    fun save(noteId: String, work: File, state: CheckpointState): WordLogRef {
        val chunks = state.recognition.chunks
        val words = chunks.words
        val log = File(work, WorkFiles.WORD_LOG)
        val cursor = cursors[noteId]?.takeIf { continues(it, words, log) }
        val from = cursor?.ref?.count ?: 0
        val settled = WordLog.settled(words, chunks.start * MS_PER_SECOND / Limits.ASR_RATE, from)
        val ref = when {
            cursor != null && settled == from -> cursor.ref
            cursor != null -> append(log, cursor.ref, WordLog.lines(words, from, settled), settled)
            else -> rewrite(work, log, WordLog.lines(words, 0, settled), settled)
        }
        cursors[noteId] = Cursor(ref, words.getOrNull(ref.count - 1))
        return ref
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

    private fun append(log: File, base: WordLogRef, lines: ByteArray, count: Int): WordLogRef {
        RandomAccessFile(log, "rw").use { file ->
            // Bytes past the committed prefix are the rest of a write that failed: write over them.
            file.setLength(base.bytes)
            file.seek(base.bytes)
            file.write(lines)
            file.fd.sync()
        }
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

        /** The words of the `wordLog` prefix of the checkpoint [json] in [work]; empty for version 1. */
        fun read(work: File, json: JSONObject): List<Word> {
            val ref = CheckpointState.wordLog(json)
            if (ref.count == 0) return emptyList()
            val log = File(work, WorkFiles.WORD_LOG)
            val bytes = ByteArray(Math.toIntExact(ref.bytes))
            RandomAccessFile(log, "r").use { it.readFully(bytes) }
            return WordLog.read(bytes, ref.count)
        }

        /** The length of `words.log` in [work], for `CheckpointState.Expected`. */
        fun length(work: File): Long = File(work, WorkFiles.WORD_LOG).length()
    }
}
