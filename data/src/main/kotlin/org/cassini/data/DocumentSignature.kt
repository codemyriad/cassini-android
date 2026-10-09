package org.cassini.data

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The signature that keys every cache of a note's document (`notes.document_signature`, the verification result
 * and the word cache): `<length>:<mtime>:<sha256>`, where the hash covers the first and the last [EDGE_BYTES] of the
 * file. A rewrite of the same size whose mtime was restored still changes the hash of the Ogg pages it touched at
 * either end (the OpusTags page with the transcript is at the start, the final granule at the end).
 *
 * Reading 128 KiB takes a few milliseconds, so checking a note on open stays cheap however long the audio is. It is
 * not the checkpoint signature (`length:mtime`, `CheckpointState`), which keeps the 0.1.0 format.
 */
object DocumentSignature {
    const val EDGE_BYTES = 64 * 1024

    /** The signature of [file], or null when the file does not exist. */
    fun of(file: File): String? {
        if (!file.isFile) return null
        val mtime = file.lastModified()
        val digest = MessageDigest.getInstance("SHA-256")
        val length = RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val head = ByteArray(minOf(length, EDGE_BYTES.toLong()).toInt())
            input.readFully(head)
            digest.update(head)
            val tailStart = maxOf(0L, length - EDGE_BYTES)
            val tail = ByteArray((length - tailStart).toInt())
            input.seek(tailStart)
            input.readFully(tail)
            digest.update(tail)
            length
        }
        return "$length:$mtime:${digest.digest().joinToString("") { "%02x".format(it) }}"
    }
}
