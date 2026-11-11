package io.codemyriad.cassini.data.backup

import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.notes.NoteFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Keep received bytes immutable so an interrupted catalogue rebuild can always start again. */
internal object RestorePreparation {
    fun create(stage: File): AppPaths {
        val prepared = File(stage, "prepared")
        if (Files.exists(prepared.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(prepared.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
        Files.createDirectories(prepared.toPath())
        val incoming = File(stage, "incoming")
        val original = File(incoming, "cassini.db")
        val destination = File(prepared, "cassini.db")
        if (Files.exists(original.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            // Room migration and cache repair must never modify the received catalogue used for a retry.
            Files.copy(original.toPath(), destination.toPath())
            NoteFiles.syncFile(destination)
        }
        NoteFiles.syncDirectory(prepared)
        NoteFiles.syncDirectory(stage)
        return AppPaths(incoming, File(stage, "cache"), database = destination)
    }
}
