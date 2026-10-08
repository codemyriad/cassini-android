package org.cassini.android

import android.net.Uri
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Finishes live captures that a crash or kill cut short: keeps every whole page, then marks the last one end-of-stream. */
internal object RecoveryScanner {
    /** [durationMs] of the kept audio; [repaired] is false when the file already ended cleanly. */
    data class Result(val durationMs: Long, val repaired: Boolean)

    /** Null when no audio packet survives. Stops at the first page with a bad CRC, sequence, serial or granule. */
    fun repair(file: File): Result? = RandomAccessFile(file, "rw").use { raf ->
        val length = raf.length()
        var offset = 0L; var seq = 0; var serial = 0; var packets = 0; var open = false
        var preSkip = -1; var granule = -1L
        var keepStart = -1L; var keepEnd = -1L; var keepGranule = 0L
        val header = ByteArray(27 + 255)
        while (offset + 27 <= length) {
            raf.seek(offset); raf.readFully(header, 0, 27)
            if (String(header, 0, 4, Charsets.US_ASCII) != "OggS" || header[4] != 0.toByte()) break
            val count = header[26].toInt() and 255
            if (offset + 27 + count > length) break
            raf.readFully(header, 27, count)
            var size = 0
            repeat(count) { size += header[27 + it].toInt() and 255 }
            if (offset + 27 + count + size > length) break
            val page = header.copyOf(27 + count + size)
            raf.readFully(page, 27 + count, size)
            val flags = page[5].toInt() and 255
            val crc = OggOpus.u32(page, 22)
            for (i in 22..25) page[i] = 0
            if (OggOpus.crc(page) != crc) break
            if (seq == 0) serial = OggOpus.u32(page, 14)
            if (OggOpus.u32(page, 14) != serial || OggOpus.u32(page, 18) != seq || (flags and 2 != 0) != (seq == 0)) break
            if ((flags and 1 != 0) != open) break
            var cursor = 27 + count
            repeat(count) { i ->
                val lace = page[27 + i].toInt() and 255
                if (packets == 0 && preSkip < 0 && lace >= 12) preSkip = OggOpus.u16(page, cursor + 10)
                cursor += lace
                open = lace == 255
                if (!open) packets++
            }
            val pageGranule = OggOpus.i64(page, 6)
            if (!open && count > 0) {
                if (pageGranule < granule) break
                granule = pageGranule
                if (packets > 2) { keepStart = offset; keepEnd = offset + page.size; keepGranule = pageGranule }
            }
            offset += page.size; seq++
            if (flags and 4 != 0) {
                if (keepEnd != offset) return@use null
                return@use Result(duration(keepGranule, preSkip), false)
            }
        }
        if (keepEnd < 0 || preSkip < 0) return@use null
        val page = ByteArray((keepEnd - keepStart).toInt())
        raf.seek(keepStart); raf.readFully(page)
        page[5] = (page[5].toInt() or 4).toByte()
        for (i in 22..25) page[i] = 0
        OggOpus.put32(page, 22, OggOpus.crc(page))
        raf.seek(keepStart); raf.write(page); raf.setLength(keepEnd); raf.fd.sync()
        Result(duration(keepGranule, preSkip), true)
    }

    private fun duration(granule: Long, preSkip: Int) = (granule - preSkip).coerceAtLeast(0) * 1000 / 48000

    /** Repairs unfinished `*.rec.opus` captures other than [live] and catalogues those the library does not hold. */
    fun recover(filesDir: File, live: File?, name: String) {
        val documents = File(filesDir, "documents").listFiles().orEmpty().filter { it.name.endsWith(".rec.opus") && it != live }
        if (documents.isEmpty()) return
        val library = LibraryStore(filesDir)
        val known = library.load().mapNotNull { it.session.uri }.toSet()
        documents.sortedBy { it.lastModified() }.forEach { file ->
            val uri = Uri.fromFile(file).toString()
            try {
                val result = repair(file)
                if (uri in known) return@forEach
                if (result == null) { if (file.delete()) Log.w("Cassini", "Removed empty capture ${file.name}"); return@forEach }
                library.save(Session(uri = uri, libraryId = UUID.randomUUID().toString(), name = "$name.opus", durationMs = result.durationMs))
                Log.i("Cassini", "Recovered capture ${file.name}: ${result.durationMs} ms")
            } catch (error: Exception) { Log.w("Cassini", "Could not recover capture ${file.name}", error) }
        }
    }
}
