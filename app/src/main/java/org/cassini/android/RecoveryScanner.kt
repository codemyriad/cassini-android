package org.cassini.android

import android.net.Uri
import android.util.Log
import org.cassini.core.format.OggRepair
import org.cassini.core.library.Session
import java.io.File
import java.util.UUID

/** Finishes live captures that a crash or kill cut short and catalogues them. The page repair is [OggRepair]. */
internal object RecoveryScanner {
    /** Repairs unfinished `*.rec.opus` captures other than [live] and catalogues those the library does not hold. */
    fun recover(filesDir: File, live: File?, name: String) {
        val documents = File(filesDir, "documents").listFiles().orEmpty().filter { it.name.endsWith(".rec.opus") && it != live }
        if (documents.isEmpty()) return
        val library = LibraryStore(filesDir)
        val known = library.load().mapNotNull { it.session.uri }.toSet()
        documents.sortedBy { it.lastModified() }.forEach { file ->
            val uri = Uri.fromFile(file).toString()
            try {
                if (uri in known) { if (!OggRepair.endsCleanly(file)) OggRepair.repair(file); return@forEach }
                val result = OggRepair.repair(file)
                if (result == null) { if (file.delete()) Log.w("Cassini", "Removed empty capture ${file.name}"); return@forEach }
                library.save(Session(uri = uri, libraryId = UUID.randomUUID().toString(), name = "$name.opus", durationMs = result.durationMs))
                Log.i("Cassini", "Recovered capture ${file.name}: ${result.durationMs} ms")
            } catch (error: Exception) { Log.w("Cassini", "Could not recover capture ${file.name}", error) }
        }
    }
}
