package org.cassini.data

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 in lower-case hex, the form `notes.source_sha256` stores. */
object Sha256 {
    private const val BUFFER_BYTES = 64 * 1024

    fun of(file: File): String = file.inputStream().use { input ->
        MessageDigest.getInstance("SHA-256").also { update(it, input) }.let(::hex)
    }

    /** Feeds all of [input] into [digest]. */
    fun update(digest: MessageDigest, input: InputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        var n = input.read(buffer)
        while (n >= 0) {
            digest.update(buffer, 0, n)
            n = input.read(buffer)
        }
    }

    fun hex(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }
}
