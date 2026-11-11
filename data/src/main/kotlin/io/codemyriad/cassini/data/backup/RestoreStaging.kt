package io.codemyriad.cassini.data.backup

import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Potentially library-sized deletion is exclusively backup preparation or startup work. */
internal object RestoreStaging {
    private val names = Regex("restore-[a-f0-9-]{36}")

    fun isStage(name: String): Boolean = names.matches(name)

    fun cleanupOrphans(staging: File) {
        if (Files.notExists(staging.toPath())) return
        try {
            Files.newDirectoryStream(staging.toPath()).use { directories ->
                for (path in directories) {
                    cleanupOrphan(path)
                }
            }
        } catch (error: IOException) {
            Log.w("LibraryRestore", "Unused restore staging cleanup deferred", error)
        }
    }

    private fun cleanupOrphan(path: Path) {
        if (isStage(path.fileName.toString())) deleteStage(path.toFile())
    }

    /** Only this feature's private staging tree, never a caller's library or model root. */
    fun deleteStage(stage: File) {
        require(isStage(stage.name))
        if (!stage.exists()) return
        Files.walk(stage.toPath()).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }
}
