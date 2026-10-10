package org.cassini.android.note

import org.cassini.core.export.ExportNames
import org.cassini.core.format.DocumentState
import org.cassini.core.library.AudioKind
import org.cassini.data.db.NoteDetails
import org.cassini.data.notes.TranscriptExport
import org.cassini.data.notes.TranscriptFormat
import org.cassini.ui.note.ExportKind
import org.cassini.ui.note.NoteAction

/** One "Save a copy" file: the picker's MIME type and suggested name. */
data class ExportFile(val kind: ExportKind, val mime: String, val name: String)

/**
 * The exports of features.md section 6: what the system picker gets (`ACTION_CREATE_DOCUMENT` with a MIME type and
 * an `ExportNames.file` name) and the transcript request for the `:core` writers (the selected variant, with display
 * labels: names verbatim, placeholders as the localized "Speaker N").
 */
object NoteExports {
    const val MIME_TEXT = "text/plain"
    const val MIME_SRT = "application/x-subrip"
    const val MIME_VTT = "text/vtt"
    const val MIME_DOCUMENT = "audio/ogg"

    /** The picker request for [kind] of note [details]; null when [kind] is not available for it. */
    fun file(details: NoteDetails, kind: ExportKind): ExportFile? {
        val note = details.note
        val document = note.audioKind == AudioKind.DOCUMENT
        return when (kind) {
            ExportKind.DOCUMENT -> if (document) {
                ExportFile(kind, MIME_DOCUMENT, ExportNames.file(note.title, "opus"))
            } else {
                val ext = note.audioFile.substringAfterLast('.', "opus")
                ExportFile(kind, NoteMapping.audioMime(ext), ExportNames.file(note.title, ext))
            }
            ExportKind.TEXT -> transcript(details, kind, MIME_TEXT, TranscriptFormat.TXT)
            ExportKind.SRT -> transcript(details, kind, MIME_SRT, TranscriptFormat.SRT)
            ExportKind.VTT -> transcript(details, kind, MIME_VTT, TranscriptFormat.VTT)
        }
    }

    private fun transcript(details: NoteDetails, kind: ExportKind, mime: String, format: TranscriptFormat): ExportFile? {
        val name = ExportNames.file(details.note.title, format.extension)
        return if (textExportEnabled(details)) ExportFile(kind, mime, name) else null
    }

    /** Text and subtitles need a readable transcript (features.md section 6). */
    fun textExportEnabled(details: NoteDetails): Boolean =
        details.note.audioKind == AudioKind.DOCUMENT && details.note.documentState !in NOT_READABLE &&
            NoteMapping.selected(details) != null

    /** The writer format of a transcript [kind]; null for the document copy. */
    fun format(kind: ExportKind): TranscriptFormat? = when (kind) {
        ExportKind.DOCUMENT -> null
        ExportKind.TEXT -> TranscriptFormat.TXT
        ExportKind.SRT -> TranscriptFormat.SRT
        ExportKind.VTT -> TranscriptFormat.VTT
    }

    /** The actions sheet row that shows the progress of [kind]. */
    fun row(kind: ExportKind): NoteAction = when (kind) {
        ExportKind.DOCUMENT -> NoteAction.SAVE_DOCUMENT
        ExportKind.TEXT -> NoteAction.SAVE_TEXT
        ExportKind.SRT, ExportKind.VTT -> NoteAction.SAVE_SUBTITLES
    }

    /**
     * The display label of each speaker of the selected variant: named labels verbatim, placeholders as
     * [placeholder] of their number (features.md section 9).
     */
    fun labels(details: NoteDetails, placeholder: (Int) -> String): Map<String, String> {
        val variant = NoteMapping.selected(details) ?: return emptyMap()
        return details.speakers.filter { it.variantId == variant.variantId }.associate { speaker ->
            speaker.speakerId to if (speaker.isPlaceholder) {
                placeholder(speaker.placeholderNumber ?: (speaker.ordinal + 1))
            } else {
                speaker.label
            }
        }
    }

    /** The writer request of a transcript export of [details]. */
    fun request(
        details: NoteDetails,
        format: TranscriptFormat,
        placeholder: (Int) -> String,
        subtitle: String,
        emptyText: String,
    ): TranscriptExport = TranscriptExport(
        format = format,
        labels = labels(details, placeholder),
        subtitle = subtitle,
        emptyText = emptyText,
        variantId = NoteMapping.selected(details)?.variantId,
    )

    private val NOT_READABLE =
        setOf(DocumentState.UNREADABLE, DocumentState.INVALID_METADATA, DocumentState.UNKNOWN_FORMAT)
}
