package org.cassini.data.notes

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** File names inside `notes/<id>/` and the durable write steps (docs/architecture.md, section 3.2). */
internal object NoteFiles {
    const val DOCUMENT = "document.opus"
    const val DOCUMENT_TMP = "document.opus.tmp"
    const val SEALED = "sealed.opus"
    const val SEALED_MARKER = "sealed"
    const val TMP_SUFFIX = ".tmp"

    /** A capture or imported file that a sealed document replaces at adopt: `capture.opus`, `source.mp3`, … */
    private val previousAudio = Regex("(capture|source)\\.[a-z0-9]{1,5}")

    fun isPreviousAudio(name: String): Boolean = previousAudio.matches(name) && !name.endsWith(TMP_SUFFIX)

    /** Flushes [file]'s data to the storage device. */
    fun syncFile(file: File) {
        RandomAccessFile(file, "rw").use { it.fd.sync() }
    }

    /**
     * Flushes [dir]'s entries, so a rename inside it survives a power cut. Best effort: a file system that refuses to
     * open a directory still renamed atomically.
     */
    fun syncDirectory(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            syncDirectoryWithOs(dir)
        }
    }

    private fun syncDirectoryWithOs(dir: File) {
        try {
            val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (_: ErrnoException) {
            // Nothing more to try; the rename itself is atomic.
        }
    }

    /** Renames [source] over [target] in one step (rename(2) replaces an existing target). */
    fun replace(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}
