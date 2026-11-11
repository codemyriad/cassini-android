package io.codemyriad.cassini.data.notes

import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.format.MeetingTime
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.Origin
import io.codemyriad.cassini.data.db.LibraryRow
import io.codemyriad.cassini.data.db.NoteEntity
import java.time.Instant

/** A capture clock is authoritative; a document's created_at may only be its packaging/sorting fallback. */
fun NoteEntity.knownCaptureStart(): Instant? = if (origin == Origin.RECORDED) {
    captureStartedAt?.let(Instant::ofEpochMilli)
        ?: createdAt.takeIf { audioKind != AudioKind.DOCUMENT }?.let(Instant::ofEpochMilli)
} else {
    null
}

fun NoteEntity.recordingTime(): MeetingTime? = when {
    audioKind == AudioKind.DOCUMENT && !readableTime(documentState) -> null
    else -> MeetingTime.cached(recordedAt) ?: knownCaptureStart()?.let(MeetingTime::utc)
}

/** A supported, verified own file may omit its start; keep the independently captured UTC instead of seal time. */
internal fun NoteEntity.recordingTime(document: CassiniDocument): MeetingTime? {
    val time = document.meetingTime
    val capture = knownCaptureStart()
    return if (document.documentState == DocumentState.OK && time?.source != MeetingTime.UTC && capture != null) {
        MeetingTime.utc(capture)
    } else {
        time
    }
}

fun LibraryRow.recordingTime(): MeetingTime? = when {
    audioKind == AudioKind.DOCUMENT && !readableTime(documentState) -> null
    else -> MeetingTime.cached(recordedAt) ?: if (origin == Origin.RECORDED) {
        val start = captureStartedAt ?: createdAt.takeIf { audioKind != AudioKind.DOCUMENT }
        start?.let { MeetingTime.utc(Instant.ofEpochMilli(it)) }
    } else {
        null
    }
}

private fun readableTime(state: DocumentState?): Boolean = when (state) {
    DocumentState.OK, DocumentState.UNVERIFIED, DocumentState.STALE_AUDIO -> true
    else -> false
}
