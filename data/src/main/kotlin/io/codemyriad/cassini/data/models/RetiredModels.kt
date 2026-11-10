package io.codemyriad.cassini.data.models

import android.util.Log
import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.core.models.ModelDescriptor
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** D86: reclaim the retired model only after the replacement has its durable verified marker. */
internal fun removeRetiredParakeet(root: File) {
    try {
        if (!ModelCatalog.PARAKEET.ready(root)) return
        val previous = File(
            root,
            "${ModelDescriptor.MODELS_DIRECTORY}/${ModelCatalog.LEGACY_PARAKEET_DIST_ID}",
        ).toPath()
        if (!Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) return
        // No FOLLOW_LINKS: a symlink in the retired install never removes its target.
        Files.walkFileTree(
            previous,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    } catch (error: IOException) {
        // A valid replacement stays usable; refresh retries any leftover cleanup.
        Log.w("RetiredModels", "Could not remove retired Parakeet install", error)
    }
}
