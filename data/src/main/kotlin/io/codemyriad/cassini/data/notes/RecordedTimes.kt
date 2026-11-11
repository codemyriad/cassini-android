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
internal fun NoteEntity.recordingTime(
    document: CassiniDocument,
    capture: Instant? = knownCaptureStart(),
): MeetingTime? {
    val time = document.meetingTime
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
