package io.codemyriad.cassini.data.notes

import io.codemyriad.cassini.core.format.MeetingTime
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.Origin
import io.codemyriad.cassini.data.db.LibraryRow
import io.codemyriad.cassini.data.db.NoteEntity
import java.time.Instant

/** A live/plain own capture retains its trustworthy start. Documents rely only on their derived D95 field. */
fun NoteEntity.recordingTime(): MeetingTime? = recordingTime(recordedAt, audioKind, origin, createdAt)

fun LibraryRow.recordingTime(): MeetingTime? = recordingTime(recordedAt, audioKind, origin, createdAt)

private fun recordingTime(value: String?, kind: AudioKind, origin: Origin, order: Long): MeetingTime? =
    MeetingTime.cached(value) ?: if (kind != AudioKind.DOCUMENT && origin == Origin.RECORDED) {
        MeetingTime.utc(Instant.ofEpochMilli(order))
    } else {
        null
    }
