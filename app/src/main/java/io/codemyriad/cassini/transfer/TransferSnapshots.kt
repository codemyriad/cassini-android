package io.codemyriad.cassini.transfer

import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.audio.Limits
import io.codemyriad.cassini.core.export.ExportNames
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.library.AudioKind
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.CRC32

internal data class TransferSnapshot(val name: String, val file: File, val crc: Long)

/** One locked, immutable copy at a time. CRC comes from the same pass that copies the bytes. */
internal class TransferSnapshots(private val graph: AppGraph) {
    suspend fun copy(id: String, scratch: File, maxBytes: Long): TransferSnapshot = withContext(graph.dispatchers.io) {
        val catalogue = graph.awaitCatalogue()
        val before = catalogue.database.notes().note(id)
        if (before?.titleDirty == true && before.audioKind == AudioKind.DOCUMENT && before.documentState == DocumentState.OK) {
            catalogue.documents.retitle(id)
        }
        val context = currentCoroutineContext()
        graph.locks.withNoteLock(id, graph.dispatchers.io) {
            val row = catalogue.database.notes().noteBlocking(id) ?: throw IOException("Note unavailable")
            if (row.trashedAt != null || row.audioKind in setOf(AudioKind.LIVE_CAPTURE, AudioKind.UNPLAYABLE)) {
                throw IOException("Document unavailable")
            }
            val target = File(scratch, UUID.randomUUID().toString())
            val crc = CRC32()
            File(graph.paths.note(id), row.audioFile).inputStream().use { input ->
                target.outputStream().use { output ->
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
                            if (copied > maxBytes || scratch.usableSpace < Limits.MIN_FREE_BYTES) {
                                throw IOException("Not enough space for the document")
                            }
                        }
                        output.write(buffer, 0, n)
                        crc.update(buffer, 0, n)
                    }
                }
            }
            TransferSnapshot(ExportNames.file(row.title, row.audioFile.substringAfterLast('.', "opus")), target, crc.value)
        }
    }

    private companion object {
        const val SPACE_CHECK_BYTES = 8L shl 20
    }
}
