package org.cassini.core.models

import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Checks an installed model against its pins once per process, before the first native load (coordination D16
 * section 4, through D53.1). [ModelDescriptor.ready] compares only the sizes and the marker, so a file damaged after
 * its install (a bad flash block, a restore, a hand edit) would otherwise reach the native runtime. The first [require]
 * of a model hashes each of its files; a model that passed is not hashed again while the process lives.
 *
 * On a mismatch, or a file that cannot be read, the marker and every damaged file are deleted, so the model shows as
 * missing and the next install downloads those files again, and [require] fails with [Failure.VERIFY]. A model without
 * its marker is not installed: [require] leaves it to the native seam, which reports it missing ([Failure.MODEL]).
 * [digest] hashes a file (`ArtifactFetcher.digest`, which stops on interruption with [Failure.CANCELLED]).
 */
class ModelCheck(private val digest: (File) -> String = { ArtifactFetcher.digest(it) }) {
    private val passed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** One model is hashed at a time: two seams that open together do not hash the same files twice. */
    private val lock = Any()

    /** Fails with [Failure.VERIFY], after deleting the marker and the damaged files, unless [model] matches its pins. */
    fun require(model: ModelDescriptor, modelRoot: File) {
        val home = model.directory(modelRoot)
        val key = home.absolutePath
        if (key in passed) return
        synchronized(lock) {
            val marker = File(home, model.markerFile)
            // Checked again under the lock: another seam may have hashed it meanwhile. No marker: not installed.
            if (key in passed || !marker.isFile) return@synchronized
            val damaged = model.artifacts.filterNot { artifact -> intact(File(home, artifact.name), artifact) }
            if (damaged.isNotEmpty()) {
                marker.delete()
                damaged.forEach { File(home, it.name).delete() }
                val names = damaged.joinToString { it.name }
                throw UserFacingException(Failure.VERIFY, "${model.directory} no longer matches its pins: $names")
            }
            passed += key
        }
    }

    /**
     * Whether [file] has [artifact]'s size and SHA-256. A file that cannot be read (EIO from a bad flash block) is
     * damaged too; a cancel ([Failure.CANCELLED]) still ends the check.
     */
    private fun intact(file: File, artifact: Artifact): Boolean = try {
        file.length() == artifact.size && digest(file) == artifact.sha256
    } catch (_: IOException) {
        false
    }

    /** Whether [model] passed in this process (for tests and diagnostics). */
    fun passed(model: ModelDescriptor, modelRoot: File): Boolean = model.directory(modelRoot).absolutePath in passed
}
