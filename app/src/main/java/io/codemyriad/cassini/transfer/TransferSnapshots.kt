package io.codemyriad.cassini.transfer

import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.audio.Limits
import io.codemyriad.cassini.core.export.ExportNames
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.data.db.NoteEntity
import io.codemyriad.cassini.data.notes.DocumentRepository
import io.codemyriad.cassini.data.notes.startNotInDocument
import io.codemyriad.cassini.data.notes.userStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import kotlin.coroutines.CoroutineContext

internal data class TransferSnapshot(val name: String, val file: File, val crc: Long)

/** One locked, immutable copy at a time. CRC comes from the same pass that copies the bytes. */
internal class TransferSnapshots(private val graph: AppGraph) {
    suspend fun copy(id: String, scratch: File, maxBytes: Long): TransferSnapshot = withContext(graph.dispatchers.io) {
        val catalogue = graph.awaitCatalogue()
        val before = catalogue.database.notes().note(id)
        if (before?.titleDirty == true && before.audioKind == AudioKind.DOCUMENT && before.documentState == DocumentState.OK) {
            catalogue.documents.retitle(id)
        }
        // A start set in "Set date and time" (D112) that the file does not carry yet goes into the copy too.
        if (catalogue.database.notes().note(id)?.startNotInDocument() == true) catalogue.documents.writeStart(id)
        val context = currentCoroutineContext()
        graph.locks.withNoteLock(id, graph.dispatchers.io) {
            val row = catalogue.database.notes().noteBlocking(id) ?: throw IOException("Note unavailable")
            if (row.trashedAt != null || row.audioKind in setOf(AudioKind.LIVE_CAPTURE, AudioKind.UNPLAYABLE)) {
                throw IOException("Document unavailable")
            }
            val target = File(scratch, UUID.randomUUID().toString())
            val source = File(graph.paths.note(id), row.audioFile)
            val pending = pendingStart(id, row, catalogue.documents)
            val crc = if (pending != null) {
                stageWithStartedAt(source, pending, target, maxBytes, context)
            } else {
                copyBytes(source, target, maxBytes, context)
            }
            val name = ExportNames.file(row.title, row.audioFile.substringAfterLast('.', "opus"))
            TransferSnapshot(name, target, crc)
        }
    }

    private data class PendingStart(val document: CassiniDocument, val startedAt: String)

    private fun pendingStart(id: String, row: NoteEntity, documents: DocumentRepository): PendingStart? {
        val start = row.takeIf { it.audioKind == AudioKind.DOCUMENT }?.userStart()
        val document = start?.let { documents.readBlocking(id) }
        return if (start != null && document?.documentState == DocumentState.OK && document.meetingTime != start) {
            PendingStart(document, start.value)
        } else {
            null
        }
    }

    /** Stage the pending start into [target] without mutating [source] (e.g. while a job holds the file). */
    private fun stageWithStartedAt(
        source: File,
        pending: PendingStart,
        target: File,
        maxBytes: Long,
        context: CoroutineContext,
    ): Long {
        context.ensureActive()
        if (source.length() > maxBytes || (target.parentFile?.usableSpace ?: 0L) < Limits.MIN_FREE_BYTES) {
            throw IOException("Not enough space for the document")
        }
        var ok = false
        try {
            CassiniDocument.withStartedAt(source, pending.document, pending.startedAt, target)
            if (target.length() > maxBytes || target.usableSpace < Limits.MIN_FREE_BYTES) {
                throw IOException("Not enough space for the document")
            }
            return crc32(target, context).also { ok = true }
        } finally {
            if (!ok) target.delete()
        }
    }

    private fun crc32(file: File, context: CoroutineContext): Long {
        val crc = CRC32()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                context.ensureActive()
                val n = input.read(buffer)
                context.ensureActive()
                if (n < 0) break
                crc.update(buffer, 0, n)
            }
        }
        return crc.value
    }

    private fun copyBytes(source: File, target: File, maxBytes: Long, context: CoroutineContext): Long {
        return source.inputStream().use { input ->
            target.outputStream().use { output -> copyTo(input, output, target, maxBytes, context) }
        }
    }

    private fun copyTo(input: InputStream, output: OutputStream, target: File, maxBytes: Long, context: CoroutineContext): Long {
        val crc = CRC32()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        var nextSpaceCheck = 0L
        while (true) {
            context.ensureActive()
            val n = input.read(buffer)
            context.ensureActive()
            if (n < 0) break
            copied += n
            if (copied >= nextSpaceCheck || copied > maxBytes) {
                nextSpaceCheck = copied + SPACE_CHECK_BYTES
                if (copied > maxBytes || target.usableSpace < Limits.MIN_FREE_BYTES) {
                    throw IOException("Not enough space for the document")
                }
            }
            output.write(buffer, 0, n)
            crc.update(buffer, 0, n)
        }
        return crc.value
    }

    private companion object {
        const val SPACE_CHECK_BYTES = 8L shl 20
    }
}
