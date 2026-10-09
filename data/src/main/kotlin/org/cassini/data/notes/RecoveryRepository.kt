package org.cassini.data.notes

import org.cassini.core.library.AudioKind
import org.cassini.data.AppPaths
import org.cassini.data.CassiniDispatchers
import org.cassini.data.NoteLocks
import org.cassini.data.db.CassiniDatabase
import java.io.File

/**
 * Finishes the recordings a crash or kill left open (docs/architecture.md, sections 4.7 and 4.8 step 2). Runs once
 * per process start, before a new recording can begin.
 */
class RecoveryRepository(
    database: CassiniDatabase,
    private val paths: AppPaths,
    private val dispatchers: CassiniDispatchers,
    private val locks: NoteLocks,
    private val probe: AudioProbe = AudioProbe.Extractor,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val notes = database.notes()

    /**
     * Every LIVE_CAPTURE note except [activeNoteId] (the capture that is running now, if any) becomes PLAIN or
     * UNPLAYABLE. A `capture.opus` is repaired with `OggRepair` (whole pages kept, an end-of-stream page written) and
     * gets the duration of its last granule; a `capture.m4a` is PLAIN when [probe] opens it. Also repairs a finished
     * `capture.opus` note whose duration is unknown, the sign of a file that was not closed. Returns the ids of the
     * recovered live captures, oldest first, for the "recording recovered" notice.
     */
    suspend fun recover(activeNoteId: String?): List<String> {
        val recovered = mutableListOf<String>()
        for (row in notes.liveCaptures()) {
            if (row.id == activeNoteId) continue
            val (kind, durationMs) = locks.withNoteLock(row.id, dispatchers.io) {
                AudioFiles.finishCapture(File(paths.note(row.id), row.audioFile), probe)
            }
            if (notes.endCapture(row.id, kind, durationMs, clock()) > 0) recovered += row.id
        }
        for (row in notes.plainCapturesWithoutDuration()) {
            if (row.id == activeNoteId) continue
            val (kind, durationMs) = locks.withNoteLock(row.id, dispatchers.io) {
                AudioFiles.finishCapture(File(paths.note(row.id), row.audioFile), probe)
            }
            when {
                kind == AudioKind.UNPLAYABLE -> notes.markUnplayable(row.id, clock())
                durationMs > 0 -> notes.setDuration(row.id, durationMs, clock())
            }
        }
        return recovered
    }
}
