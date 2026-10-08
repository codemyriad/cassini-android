package org.cassini.android

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** Owned immutable files: a session points at a document, never at an external recording grant. */
internal class DocumentStore(private val context: Context) {
    private val directory = File(context.filesDir, "documents").also { it.mkdirs() }
    /** The owned copy is named by its content, so importing the same file again finds it instead of adding another. */
    fun import(uri: Uri): Pair<File, CassiniDocument> {
        val source = File(directory, "${UUID.randomUUID()}.part")
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            (context.contentResolver.openInputStream(uri) ?: throw UserFacingException(Failure.OPEN)).use { input ->
                source.outputStream().use { copy(input, it, hash) }
            }
            val doc = CassiniDocument.read(source)
            val owned = File(directory, "${OggOpus.hex(hash.digest())}.${if (doc.state == "plain-audio") "input" else "opus"}")
            if (owned.exists()) source.delete() else check(source.renameTo(owned))
            return owned to doc
        } catch (error: Throwable) { source.delete(); throw error }
    }
    fun create(uri: Uri, audio: () -> PcmAudio, transcript: Transcript, name: String, processing: JSONObject,
               existing: CassiniDocument?, speakerLabels: Map<String, String> = emptyMap()): Pair<File, CassiniDocument> {
        val id = UUID.randomUUID()
        val source = File(directory, "$id.src")
        val temporary = File(directory, "$id.tmp")
        val output = File(directory, "$id.opus")
        try {
            // Sound Ogg Opus is kept packet for packet; anything else is re-encoded at 16 kHz Opus instead.
            val opus = (context.contentResolver.openInputStream(uri) ?: throw UserFacingException(Failure.OPEN)).use { input ->
                val head = ByteArray(OggOpus.HEAD_BYTES); var got = 0
                while (got < head.size) { val n = input.read(head, got, head.size - got); if (n < 0) break; got += n }
                if (!OggOpus.looksLikeOpus(head)) return@use false
                source.outputStream().use { it.write(head); copy(input, it, null, got.toLong()) }
                try { OggOpus.scan(source).digest(); true } catch (_: Exception) { false }
            }
            if (!opus) OpusEncoder.encode(audio(), source)
            requireUser(directory.usableSpace >= source.length() + Limits.MIN_FREE_BYTES, Failure.SPACE)
            val doc = CassiniDocument.create(source, transcript, name.substringBeforeLast('.'), processing, existing, speakerLabels, temporary)
            check(temporary.renameTo(output))
            return output to doc
        } finally { source.delete(); temporary.delete() }
    }
    /** Bounded by [Limits.MAX_IMPORT_BYTES] and the space left on the device rather than by the heap. */
    private fun copy(input: InputStream, output: OutputStream, hash: MessageDigest?, start: Long = 0) {
        val buffer = ByteArray(65536); var total = start
        while (true) {
            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
            val size = input.read(buffer); if (size < 0) break
            if (total / (16 shl 20) != (total + size) / (16 shl 20))
                requireUser(directory.usableSpace >= Limits.MIN_FREE_BYTES, Failure.SPACE)
            total += size; requireUser(total <= Limits.MAX_IMPORT_BYTES, Failure.LARGE)
            hash?.update(buffer, 0, size); output.write(buffer, 0, size)
        }
        output.flush()
    }
}
