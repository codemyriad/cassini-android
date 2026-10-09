package org.cassini.data.notes

import org.cassini.core.format.CassiniDocument
import java.io.File

/**
 * Reads and verifies a document file: `CassiniDocument.readChecked` with the audio digest. Every full read of a
 * note's document goes through this seam, so tests can count them (the FAST NOTE OPENING rule: opening a note whose
 * document did not change never reads it).
 */
fun interface DocumentReader {
    fun read(file: File): CassiniDocument

    companion object {
        /** The production reader: headers, payloads and the digest of every Opus packet. */
        val Verified = DocumentReader { file -> CassiniDocument.readChecked(file, verify = true) }
    }
}

/**
 * The last [capacity] documents read, keyed by `DocumentSignature`. A signature names one version of a file, so a hit
 * is the same bytes; the entries are shared and must not be changed (the core writers copy the manifest first).
 */
internal class DocumentLru(private val capacity: Int = CAPACITY) {
    private val entries = object : LinkedHashMap<String, CassiniDocument>(capacity, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CassiniDocument>?): Boolean =
            size > capacity
    }

    @Synchronized
    fun get(signature: String): CassiniDocument? = entries[signature]

    @Synchronized
    fun put(signature: String, document: CassiniDocument) {
        entries[signature] = document
    }

    companion object {
        const val CAPACITY = 4
        private const val LOAD_FACTOR = 0.75f
    }
}
