package org.cassini.data.checkpoint

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** File names inside `notes/<id>/work/` and the atomic JSON writes (docs/architecture.md, sections 3.2 and 3.5). */
internal object WorkFiles {
    const val CHECKPOINT = "checkpoint.json"
    const val NAMES = "names.json"
    const val AUDIO_PART = "audio.opus.part"
    const val CLIPS = "clips.pcm"
    const val WORD_LOG = "words.log"
    const val SEALED = "sealed.opus"
    const val SEALED_MARKER = "sealed"

    /** The suffix of the backup that the legacy `AtomicFile` write path keeps while it writes. */
    private const val BACKUP_SUFFIX = ".bak"

    /** Whether [file] or the backup of an interrupted legacy write exists (an unfinished `.new` file does not count). */
    fun exists(file: File): Boolean = file.isFile || File(file.path + BACKUP_SUFFIX).isFile

    /** The text of [file] through [AtomicFile], which recovers from an interrupted write. */
    fun read(file: File): String = String(AtomicFile(file).readFully(), Charsets.UTF_8)

    /**
     * Writes [json] to [file] through [AtomicFile]: a new file, fsync, then a rename over the old one. A crash at any
     * point leaves the old content or the new content, never a mix.
     */
    fun write(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        var written = false
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            written = true
        } finally {
            // Any failure (also an unchecked one) drops the new file and keeps the old content.
            if (!written) atomic.failWrite(output)
        }
    }
}
