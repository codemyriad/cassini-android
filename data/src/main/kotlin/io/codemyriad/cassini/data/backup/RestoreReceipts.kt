package io.codemyriad.cassini.data.backup

import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.data.notes.NoteFiles
import java.io.File
import java.io.FileOutputStream
import java.io.Reader
import java.nio.file.Files
import java.nio.file.LinkOption

/** A durable hash receipt for each completely received file; never rebuilt inside onRestoreFinished. */
internal object RestoreReceipts {
    private const val NAME = "receipts.tsv"
    private const val MAX_LINE = 512
    private const val COLUMNS = 3
    private val hash = Regex("[0-9a-f]{64}")

    fun record(stage: File, entry: BackupManifest.Entry) {
        val row = "${entry.path}\t${entry.bytes}\t${entry.sha256}\n"
        require(row.length <= MAX_LINE)
        FileOutputStream(File(stage, NAME), true).use { output ->
            output.write(row.toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
        NoteFiles.syncDirectory(stage)
    }

    fun verify(stage: File, manifest: BackupManifest) {
        val entries = ArrayList<BackupManifest.Entry>()
        val receipts = File(stage, NAME)
        require(Files.isRegularFile(receipts.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Unsafe restore receipts" }
        receipts.bufferedReader(Charsets.US_ASCII).use { reader ->
            while (true) {
                val line = readLine(reader) ?: break
                require(entries.size <= BackupManifest.MAX_ENTRIES) { "Too many restore receipts" }
                val columns = line.split('\t')
                require(columns.size == COLUMNS) { "Invalid restore receipt" }
                val (path, size, sha) = columns
                val bytes = size.toLong()
                require(path == BackupManifest.MANIFEST || BackupManifest.admits(path)) { "Invalid receipt path" }
                require(bytes in 0..BackupManifest.MAX_FILE_BYTES && hash.matches(sha)) { "Invalid receipt size/hash" }
                entries += BackupManifest.Entry(path, bytes, sha)
            }
        }
        val inventory = File(File(stage, "incoming"), BackupManifest.MANIFEST)
        val expected = manifest.files + BackupManifest.Entry(
            BackupManifest.MANIFEST,
            inventory.length(),
            BackupManifest.sha256(inventory),
        )
        require(entries.sortedBy { it.path } == expected.sortedBy { it.path }) {
            "Incomplete or changed restore receipts"
        }
    }

    private fun readLine(reader: Reader): String? {
        val row = StringBuilder()
        while (true) {
            val next = reader.read()
            if (next < 0) {
                require(row.isEmpty()) { "Truncated restore receipt" }
                return null
            }
            if (next == '\n'.code) return row.toString()
            require(row.length < MAX_LINE) { "Oversized restore receipt" }
            row.append(next.toChar())
        }
    }
}
