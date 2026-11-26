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

/** D112 (L-87): the start the person set; it wins over every other start (LIBRARY-RULES R6.2). */
fun NoteEntity.userStart(): MeetingTime? = MeetingTime.started(startedAt)

fun NoteEntity.recordingTime(): MeetingTime? = userStart() ?: when {
    audioKind == AudioKind.DOCUMENT && !readableTime(documentState) -> null
    else -> MeetingTime.cached(recordedAt) ?: knownCaptureStart()?.let(MeetingTime::utc)
}

/**
 * The start [document] carries (the `recorded_at` cache). A supported, verified own file may omit its start; keep the
 * independently captured UTC instead of seal time, but never over a start the file states (`startedAt`, D112).
 */
internal fun NoteEntity.recordingTime(
    document: CassiniDocument,
    capture: Instant? = knownCaptureStart(),
): MeetingTime? {
    val time = document.meetingTime
    val stated = time?.source == MeetingTime.UTC || time?.source == MeetingTime.STARTED
    return if (document.documentState == DocumentState.OK && !stated && capture != null) {
        MeetingTime.utc(capture)
    } else {
        time
    }
}

/**
 * An OK document that does not carry the start the person set yet (D112): a write after a job, a failed write, or a
 * write that never ran. Its `recorded_at` cache holds what the file states, so no file is read to tell.
 */
fun NoteEntity.startNotInDocument(): Boolean {
    val user = userStart() ?: return false
    return audioKind == AudioKind.DOCUMENT && documentState == DocumentState.OK && MeetingTime.cached(recordedAt) != user
}

fun LibraryRow.recordingTime(): MeetingTime? = MeetingTime.started(startedAt) ?: when {
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

/** Provisional v1 clocks are never displayed or exported until the supported, verified file resolves them. */
internal data class CaptureProvenance(val start: Long?, val audioSha256: String?, val legacyCandidate: Long?)

internal fun CassiniDocument.packagingInstant(): Instant? = manifest?.optJSONObject("meeting")
    ?.optString("createdAtUtc")?.let {
        try {
            Instant.parse(it)
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }
    }

@Suppress("CyclomaticComplexMethod") // Independent identity, integrity and legacy-clock trust gates.
internal fun NoteEntity.captureProvenance(document: CassiniDocument): CaptureProvenance {
    val id = document.manifest?.optJSONObject("meeting")?.optString("id")?.takeIf { it.isNotEmpty() }
    val sha = document.manifest?.optJSONObject("integrity")?.optString("opusAudioSha256")?.takeIf { it.isNotEmpty() }
    val replaced = document.documentState == DocumentState.STALE_AUDIO ||
        (audioKind == AudioKind.DOCUMENT && meetingId != null && id != null && meetingId != id) ||
        (captureAudioSha256 != null && sha != null && captureAudioSha256 != sha)
    if (replaced) return CaptureProvenance(null, null, null)
    val verified = document.documentState == DocumentState.OK
    val stamp = document.packagingInstant()?.toEpochMilli()
    val legacy = legacyCaptureCandidate?.takeIf { candidate ->
        origin == Origin.RECORDED && verified && stamp != null && candidate != stamp
    }
    val start = knownCaptureStart()?.toEpochMilli() ?: legacy
    return CaptureProvenance(
        start,
        if (verified && start != null) sha else captureAudioSha256,
        legacyCaptureCandidate.takeUnless { verified },
    )
}
