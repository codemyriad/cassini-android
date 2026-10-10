package org.cassini.ui.note

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.LocalTime

/** The trust state of a document (features.md section 3.3): the chip, its icon and the matching banner. */
enum class TrustUi { VERIFIED, NOT_VERIFIED, AUDIO_CHANGED, UNREADABLE, NEWER_FORMAT }

/**
 * The Document info dialog (no board; features.md section 6). The note's facts, then the selected version's:
 * [language] (BCP 47 tag), "Transcribed on this phone in <[processingMs]> · <[realtimeFactor]>× realtime",
 * [model], "Speakers told apart on this phone" or "All words shown as one voice" ([speakersToldApart]), "N names
 * from saved voices" ([namesFromVoices]), "Resumed from <[resumedFromMs]>". Null values hide their row. [readError]
 * is the read error of an UNREADABLE or INVALID_METADATA document; [meetingId] is shown selectable in mono.
 */
@Immutable
data class InfoUi(
    val trust: TrustUi,
    val title: String,
    val recordedDate: LocalDate,
    val recordedTime: LocalTime,
    val durationMs: Long,
    val versions: Int,
    val speakers: Int,
    val language: String? = null,
    val processingMs: Long? = null,
    val realtimeFactor: Int? = null,
    val model: String? = null,
    val speakersToldApart: Boolean? = null,
    val namesFromVoices: Int = 0,
    val resumedFromMs: Long? = null,
    val fileBytes: Long,
    val meetingId: String? = null,
    val readError: String? = null,
)
