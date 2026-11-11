package io.codemyriad.cassini.transfer

import android.content.Context
import android.net.Uri
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.data.notes.ArchiveRejected
import io.codemyriad.cassini.data.notes.ImportSource
import io.codemyriad.cassini.data.notes.ImportSummary
import io.codemyriad.cassini.data.notes.NoteArchives
import io.codemyriad.cassini.data.useCancellable
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.InputStream

/** Feeds individual files and streamed archive entries through the ordinary digest-deduplicating importer. */
internal class ArchiveImports(context: Context, private val graph: AppGraph) {
    private val source = ImportSource.of(context.contentResolver)
    private val archives = NoteArchives()
    suspend fun importOne(uri: Uri, scratch: File, progressChanged: (ImportSummary) -> Unit): ImportSummary {
        var progress = ImportSummary()
        return try {
            val name = source.describe(uri).displayName.orEmpty()
            val input = source.open(uri)?.buffered() ?: return ImportSummary(failed = 1)
            val zip = input.useCancellable { looksLikeZip(it) }
            if (zip || name.endsWith(".zip", ignoreCase = true)) {
                source.open(uri)?.useCancellable { archive ->
                    archives.read(archive, scratch, {
                        progress = it
                        progressChanged(it)
                    }) { file, entryName ->
                        graph.awaitCatalogue().imports.import(Uri.fromFile(file), entryName)
                    }
                } ?: ImportSummary(failed = 1)
            } else {
                ImportSummary().add(graph.awaitCatalogue().imports.import(uri))
            }
        } catch (error: ArchiveRejected) {
            progress + error.summary + ImportSummary(failed = 1, archiveFailures = setOf(error.reason))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            // Third-party content providers can throw runtime exceptions as well as IO failures.
            android.util.Log.w("CassiniImport", "Cannot import a selected file", error)
            progress + ImportSummary(failed = 1)
        }
    }

    private fun looksLikeZip(input: InputStream): Boolean {
        val magic = ByteArray(ZIP_MAGIC_BYTES)
        var n = 0
        while (n < ZIP_MAGIC_BYTES) {
            val read = input.read(magic, n, ZIP_MAGIC_BYTES - n)
            if (read < 0) break
            n += read
        }
        return n == ZIP_MAGIC_BYTES && java.nio.ByteBuffer.wrap(magic).int in setOf(LOCAL_HEADER, END_HEADER, ZIP64_END_HEADER)
    }

    private companion object {
        const val ZIP_MAGIC_BYTES = 4
        const val LOCAL_HEADER = 0x504b0304
        const val END_HEADER = 0x504b0506
        const val ZIP64_END_HEADER = 0x504b0606
    }
}
