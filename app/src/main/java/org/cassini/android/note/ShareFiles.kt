package org.cassini.android.note

import java.io.File
import java.util.UUID

/**
 * The files that Share hands to other apps (coordination D53): each share writes into a fresh
 * `cacheDir/share/<uuid>/<name>`, so the file keeps its export name and no two shares collide. The receiving app may
 * read it later (a mail draft attaches at send time), so a share folder is deleted only when it is older than
 * [KEEP_MS], at the next share. Android may also clear the cache when storage runs low.
 */
class ShareFiles(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    /** A new empty file named [name] in a fresh folder, after the folders older than [KEEP_MS] are deleted. */
    fun target(name: String): File {
        val now = clock()
        root.listFiles().orEmpty().filter { now - it.lastModified() > KEEP_MS }.forEach { it.deleteRecursively() }
        val folder = File(root, UUID.randomUUID().toString())
        check(folder.mkdirs()) { "Cannot create $folder" }
        return File(folder, name)
    }

    companion object {
        /** The cache folder that the FileProvider shares (`res/xml/share_paths.xml`). */
        const val FOLDER = "share"

        /** How long a shared file stays for the app that received it: one day. */
        const val KEEP_MS = 24L * 60 * 60 * 1000
    }
}
