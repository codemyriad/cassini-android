package io.codemyriad.cassini.note

import io.codemyriad.cassini.core.export.ExportNames
import io.codemyriad.cassini.core.export.MarkdownExport
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.speakers.DefaultLabels
import io.codemyriad.cassini.data.db.NoteDetails
import io.codemyriad.cassini.data.notes.TranscriptExport
import io.codemyriad.cassini.data.notes.TranscriptFormat
import io.codemyriad.cassini.ui.note.ExportKind
import io.codemyriad.cassini.ui.note.NoteAction

/** One "Save a copy" file: the picker's MIME type and suggested name. */
data class ExportFile(val kind: ExportKind, val mime: String, val name: String)

/**
 * The exports of features.md section 6: what the system picker gets (`ACTION_CREATE_DOCUMENT` with a MIME type and
 * an `ExportNames.file` name) and the transcript request for the `:core` writers (the selected variant, with display
 * labels: names verbatim, placeholders as the localized "Speaker N").
 */
object NoteExports {
    const val MIME_TEXT = "text/plain"
    const val MIME_MARKDOWN = "text/markdown"
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
            ExportKind.MARKDOWN -> transcript(details, kind, MIME_MARKDOWN, TranscriptFormat.MARKDOWN)
            ExportKind.SRT -> transcript(details, kind, MIME_SRT, TranscriptFormat.SRT)
            ExportKind.VTT -> transcript(details, kind, MIME_VTT, TranscriptFormat.VTT)
        }
    }

    private fun transcript(details: NoteDetails, kind: ExportKind, mime: String, format: TranscriptFormat): ExportFile? {
        val name = if (format == TranscriptFormat.MARKDOWN) {
            MarkdownExport.filename(details.note.title, details.note.title)
        } else {
            ExportNames.file(details.note.title, format.extension)
        }
        return if (textExportEnabled(details)) ExportFile(kind, mime, name) else null
    }

    /**
     * The text formats (Markdown, Plain text, SRT, WebVTT) need a completed, readable transcript (L-11, cassini-ux
     * D99): a plain note, or one whose first transcription is queued, running or failed, is audio-only.
     */
    fun textExportEnabled(details: NoteDetails): Boolean =
        details.note.audioKind == AudioKind.DOCUMENT && details.note.documentState !in NOT_READABLE &&
            NoteMapping.selected(details) != null

    /** The writer format of a transcript [kind]; null for the document copy. */
    fun format(kind: ExportKind): TranscriptFormat? = when (kind) {
        ExportKind.DOCUMENT -> null
        ExportKind.TEXT -> TranscriptFormat.TXT
        ExportKind.MARKDOWN -> TranscriptFormat.MARKDOWN
        ExportKind.SRT -> TranscriptFormat.SRT
        ExportKind.VTT -> TranscriptFormat.VTT
    }

    /** The actions sheet row that shows the progress of [kind]. */
    fun row(kind: ExportKind): NoteAction = when (kind) {
        ExportKind.DOCUMENT -> NoteAction.SAVE_DOCUMENT
        ExportKind.TEXT -> NoteAction.SAVE_TEXT
        ExportKind.MARKDOWN -> NoteAction.SAVE_MARKDOWN
        ExportKind.SRT -> NoteAction.SAVE_SRT
        ExportKind.VTT -> NoteAction.SAVE_VTT
    }

    /**
     * The label of each speaker of the selected variant: the stored label verbatim, placeholder or not (D47), and
     * [placeholder] of the speaker's number only when the file stores no label (D53.3).
     */
    fun labels(details: NoteDetails, placeholder: (Int) -> String): Map<String, String> {
        val variant = NoteMapping.selected(details) ?: return emptyMap()
        return details.speakers.filter { it.variantId == variant.variantId }.associate { speaker ->
            speaker.speakerId to (
                DefaultLabels.stored(speaker.speakerId, speaker.label)
                    ?: placeholder(speaker.placeholderNumber ?: (speaker.ordinal + 1))
                )
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
