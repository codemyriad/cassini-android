package io.codemyriad.cassini.data.backup

import android.util.AtomicFile
import io.codemyriad.cassini.data.notes.NoteFiles
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

/** Bounded metadata only: restore completion never needs to inspect the payload to commit this pointer. */
internal class RestoreJournal(private val staging: File) {
    val file = File(staging, "applying.json")

    fun write(value: JSONObject) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            val payload = value.toString()
            val wrapper = JSONObject().put("payload", payload).put("sha256", checksum(payload))
            stream.write(wrapper.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            NoteFiles.syncDirectory(staging)
        } catch (error: IOException) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun read(): JSONObject? {
        val bytes = ByteArray(MAX_JOURNAL_BYTES + 1)
        val size = try {
            AtomicFile(file).openRead().use { input ->
                readBounded(input, bytes)
            }
        } catch (error: FileNotFoundException) {
            if (Files.notExists(file.toPath()) && Files.notExists(File(file.path + ".bak").toPath())) return null
            throw error
        }
        require(size < bytes.size) { "Oversized restore journal" }
        val wrapper = JSONObject(String(bytes, 0, size, Charsets.UTF_8))
        val payload = wrapper.getString("payload")
        require(checksum(payload) == wrapper.getString("sha256")) { "Damaged restore journal" }
        return JSONObject(payload)
    }

    fun receivedStage(): File? {
        val value = read() ?: return null
        return if (value.getString("phase") == "received") {
            require(value.opt("version") == 1 && value.optBoolean("deferred"))
            val name = value.getString("stage")
            require(RestoreStaging.isStage(name))
            File(staging, name)
        } else {
            null
        }
    }

    private fun readBounded(input: InputStream, bytes: ByteArray): Int {
        var used = 0
        while (used < bytes.size) {
            val count = input.read(bytes, used, bytes.size - used)
            if (count < 0) break
            used += count
        }
        return used
    }

    private companion object {
        const val MAX_JOURNAL_BYTES = 4096

        fun checksum(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
