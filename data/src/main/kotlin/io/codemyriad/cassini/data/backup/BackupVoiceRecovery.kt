package io.codemyriad.cassini.data.backup

import android.util.AtomicFile
import io.codemyriad.cassini.core.backup.BackupManifest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Restore committed AtomicFile data before enumerating a quiescent library, including legacy backup-only files. */
internal object BackupVoiceRecovery {
    fun recover(root: File) {
        val bases = HashSet<File>()
        for (name in listOf("voices", "voices/notes")) collect(root, File(root, name), bases)
        for (base in bases) {
            val backup = File(base.path + ".bak")
            checkFile(base)
            checkFile(backup)
            AtomicFile(base).openRead().use { /* openRead performs the platform's atomic recovery. */ }
            // Legacy AtomicFile logs rename failures instead of throwing. Never accept its truncated base.
            if (!Files.notExists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Committed voice recovery did not finish")
            }
        }
    }

    private fun collect(root: File, directory: File, bases: MutableSet<File>) {
        if (Files.notExists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Unsafe voice directory" }
        Files.list(directory.toPath()).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                val file = iterator.next()
                val relative = root.toPath().relativize(file).toString().replace(File.separatorChar, '/')
                val base = relative.removeSuffix(".bak")
                if (BackupManifest.admits(base)) {
                    checkFile(file.toFile())
                    bases += File(root, base)
                }
            }
        }
    }

    private fun checkFile(file: File) {
        if (!Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Unsafe atomic voice file" }
        }
    }
}
