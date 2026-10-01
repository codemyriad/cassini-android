package org.cassini.android

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Owned immutable files: a session points at a document, never at an external recording grant. */
internal class DocumentStore(private val context: Context) {
    private val directory = File(context.filesDir, "documents").also { it.mkdirs() }
    /** The owned copy is named by its content, so importing the same file again finds it instead of adding another. */
    fun import(uri: Uri): Pair<File, CassiniDocument> {
        val source = File(directory, "${UUID.randomUUID()}.part")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireUser(input != null, Failure.OPEN)
                source.outputStream().use { output ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) {
                        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
                        val size = input!!.read(buffer); if (size < 0) break
                        total += size; requireUser(total <= OggOpus.MAX_FILE_BYTES, Failure.LARGE)
                        output.write(buffer, 0, size)
                    }
                }
            }
            val bytes = source.readBytes()
            val doc = CassiniDocument.read(bytes)
            val owned = File(directory, "${CassiniPayload.sha(bytes)}.${if (doc.state == "plain-audio") "input" else "opus"}")
            if (owned.exists()) source.delete() else check(source.renameTo(owned))
            return owned to doc
        } catch (error: Throwable) { source.delete(); throw error }
    }
    fun create(uri: Uri, audio: PcmAudio, transcript: Transcript, name: String, processing: JSONObject,
               existing: CassiniDocument?): Pair<File, CassiniDocument> {
        val original = context.contentResolver.openInputStream(uri)?.use { input ->
            // Imports and current portable documents are already bounded; legacy external sessions may not be.
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                requireUser(out.size().toLong() + n <= OggOpus.MAX_FILE_BYTES, Failure.LARGE)
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: throw UserFacingException(Failure.OPEN)
        val opus = try { OggOpus.read(original).also { it.digest() }; original } catch (_: Exception) { OpusEncoder.encode(audio) }
        val bytes = CassiniDocument.create(opus, transcript, name.substringBeforeLast('.'), processing, existing)
        val temporary = File(directory, "${UUID.randomUUID()}.tmp")
        val output = File(directory, "${UUID.randomUUID()}.opus")
        try {
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            check(temporary.renameTo(output))
            return output to CassiniDocument.read(bytes)
        } finally { temporary.delete() }
    }
}
