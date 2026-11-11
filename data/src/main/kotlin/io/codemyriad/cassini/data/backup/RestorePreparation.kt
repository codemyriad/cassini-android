package io.codemyriad.cassini.data.backup

import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.data.notes.NoteFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Keep received bytes immutable so an interrupted catalogue rebuild can always start again. */
internal object RestorePreparation {
    fun create(stage: File, manifest: BackupManifest): File {
        val prepared = File(stage, "prepared")
        if (Files.exists(prepared.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(prepared.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
        Files.createDirectories(prepared.toPath())
        for (entry in manifest.files) {
            val original = File(File(stage, "incoming"), entry.path)
            val destination = File(prepared, entry.path)
            Files.createDirectories(destination.parentFile!!.toPath())
            if (entry.path == "cassini.db") {
                // Room migration and cache repair must never modify the received catalogue used for a retry.
                Files.copy(original.toPath(), destination.toPath())
                NoteFiles.syncFile(destination)
            } else {
                // The staging and live roots share internal storage. Notes/voices are read-only during repair.
                Files.createLink(destination.toPath(), original.toPath())
            }
            NoteFiles.syncDirectory(destination.parentFile!!)
        }
        Files.walk(prepared.toPath()).use { paths ->
            paths.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .forEach { NoteFiles.syncDirectory(it.toFile()) }
        }
        NoteFiles.syncDirectory(stage)
        return prepared
    }
}
