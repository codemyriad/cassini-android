package io.codemyriad.cassini.documents

import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.export.ExportNames
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.data.db.NoteEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal data class ExposedDocument(val id: String, val name: String, val mime: String, val size: Long?, val modified: Long?)

/** Lists portable documents; only a newly created import alias can resolve an original plain-audio note. */
internal class DocumentCopies(private val graph: AppGraph) {
    suspend fun list(): List<ExposedDocument> = graph.awaitCatalogue().database.notes().all()
        .filter { it.trashedAt == null && it.audioKind == AudioKind.DOCUMENT }
        .mapNotNull { row -> metadata("note:${row.id}", row).takeIf { audio(row).isFile } }

    suspend fun row(id: String): NoteEntity {
        if (!ImportHandles.uuid(id)) throw FileNotFoundException("Unknown note")
        val row = graph.awaitCatalogue().database.notes().note(id)
        if (row == null || row.trashedAt != null || row.audioKind == AudioKind.LIVE_CAPTURE) {
            throw FileNotFoundException("Note unavailable")
        }
        return row
    }

    fun metadata(documentId: String, row: NoteEntity): ExposedDocument {
        val file = audio(row)
        val extension = row.audioFile.substringAfterLast('.', "opus")
        val mime = if (row.audioKind == AudioKind.DOCUMENT) {
            "audio/ogg"
        } else {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }
        return ExposedDocument(
            documentId,
            ExportNames.file(row.title, extension),
            mime,
            file.length(),
            file.lastModified(),
        )
    }

    suspend fun snapshot(id: String, directory: File, reserve: (Long) -> Unit): File {
        val catalogue = graph.awaitCatalogue()
        catalogue.documents.retitle(id)
        val context = currentCoroutineContext()
        return graph.locks.withNoteLock(id, graph.dispatchers.io) {
            val row = catalogue.database.notes().noteBlocking(id)
                ?: throw FileNotFoundException("Note unavailable")
            if (row.trashedAt != null || row.audioKind == AudioKind.LIVE_CAPTURE) {
                throw FileNotFoundException("Note unavailable")
            }
            val source = audio(row)
            val size = source.length()
            reserve(size)
            val target = File(directory, UUID.randomUUID().toString())
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    copyBytes(input, output, size) { context.ensureActive() }
                }
            }
            target
        }
    }

    private fun copyBytes(input: InputStream, output: OutputStream, size: Long, active: () -> Unit) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            active()
            val n = input.read(buffer)
            if (n < 0) break
            copied += n
            if (copied > size) throw IOException("Document changed")
            output.write(buffer, 0, n)
        }
        if (copied != size) throw IOException("Document was truncated")
    }

    private fun audio(row: NoteEntity) = File(graph.paths.note(row.id), row.audioFile)
}
