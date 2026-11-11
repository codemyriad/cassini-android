package io.codemyriad.cassini.documents

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/** Durable aliases keep a createDocument URI valid after its bytes become a deduplicated note. Worker threads only. */
internal class ImportHandles(private val directory: File) {
    data class Handle(val name: String, val mime: String, val target: String? = null, val writing: Boolean = false)
    private var count = 0

    init {
        check(directory.isDirectory || directory.mkdirs())
        for (file in directory.listFiles().orEmpty()) {
            if (file.extension != "json") continue
            val handle = runCatching { decode(file) }.getOrNull()
            if (handle?.target == null) file.delete() else count++
        }
    }

    @Synchronized fun create(name: String, mime: String): String {
        if (count >= MAX_HANDLES) throw IOException("Too many document handles")
        val id = UUID.randomUUID().toString()
        write(id, Handle(name, mime))
        count++
        return id
    }

    @Synchronized fun get(id: String): Handle = decode(file(id))

    @Synchronized fun claim(id: String): Handle {
        val handle = get(id)
        if (handle.target != null || handle.writing) throw FileNotFoundException("Document is read-only")
        write(id, handle.copy(writing = true))
        return handle
    }

    @Synchronized fun complete(id: String, target: String) { write(id, get(id).copy(target = target, writing = false)) }

    @Synchronized fun discard(id: String) {
        if (file(id).delete()) count--
    }

    private fun file(id: String): File {
        if (!uuid(id)) throw FileNotFoundException("Unknown document")
        return File(directory, "$id.json")
    }

    private fun decode(file: File): Handle {
        if (!file.isFile || file.length() > MAX_RECORD_BYTES) throw FileNotFoundException("Unknown document")
        val value = JSONObject(String(AtomicFile(file).readFully()))
        return Handle(
            value.getString("name"),
            value.getString("mime"),
            value.optString("target").ifEmpty { null },
            value.optBoolean("writing"),
        )
    }

    private fun write(id: String, handle: Handle) {
        val bytes = JSONObject().put("name", handle.name).put("mime", handle.mime)
            .put("target", handle.target.orEmpty()).put("writing", handle.writing).toString().toByteArray()
        val atomic = AtomicFile(file(id))
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: IOException) {
            atomic.failWrite(output)
            throw error
        }
    }

    companion object {
        private const val MAX_HANDLES = 4_096
        private const val MAX_RECORD_BYTES = 2_048L
        fun uuid(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    }
}
