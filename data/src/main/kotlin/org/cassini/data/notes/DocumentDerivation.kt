package org.cassini.data.notes

import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.DocumentState
import org.cassini.core.format.Word
import org.cassini.core.library.AudioKind
import org.cassini.core.library.PreviewText
import org.cassini.core.library.VariantKind
import org.cassini.core.speakers.DefaultLabels
import org.cassini.core.speakers.SpeakerStats
import org.cassini.data.db.NoteCache
import org.cassini.data.db.NoteEntity
import org.cassini.data.db.SpeakerEntity
import org.cassini.data.db.VariantEntity
import org.cassini.data.db.WordEntity
import org.json.JSONObject

/**
 * Builds every cache row of a note from its document (docs/architecture.md, sections 3.1 and 3.3): the derived
 * columns of the note row, the variants, the speakers (`SpeakerStats`), the word cache, the searchable body and the
 * Library preview. Pure: the repository writes the result in one transaction with the document signature.
 */
internal object DocumentDerivation {
    /**
     * The cache of [row] from [document], the file version with [signature]. `notes.title` is authoritative and kept;
     * `title_dirty` is set when the document has a manifest whose title differs. [select] replaces the selected
     * variant (adopt, merge); otherwise the row's selection is kept while it names a readable variant.
     */
    fun derive(
        row: NoteEntity,
        document: CassiniDocument,
        signature: String?,
        now: Long,
        select: String? = null,
    ): NoteCache {
        // Readable variants as (id, words), in manifest order.
        val readable = document.variants.mapNotNull { variant -> variant.transcript?.let { variant.id to it.words } }
        val selected = (select ?: row.selectedVariant)?.takeIf { id -> readable.any { it.first == id } }
        val shown = readable.firstOrNull { it.first == (selected ?: document.defaultId) } ?: readable.firstOrNull()
        val shownWords = shown?.second.orEmpty()
        val meeting = document.manifest?.optJSONObject("meeting")
        val note = row.copy(
            audioFile = NoteFiles.DOCUMENT,
            audioKind = AudioKind.DOCUMENT,
            durationMs = meeting?.optLong("durationMs", row.durationMs) ?: row.durationMs,
            documentState = document.documentState,
            documentError = errorOf(document),
            documentWarnings = document.warnings,
            documentSignature = signature,
            meetingId = meeting?.optString("id")?.ifEmpty { null } ?: row.meetingId,
            selectedVariant = selected,
            preview = PreviewText.of(shownWords, row.positionMs)?.display,
            titleDirty = document.manifest != null && document.title != row.title,
            updatedAt = now,
        )
        return NoteCache(
            note = note,
            variants = document.variants.mapIndexed { ordinal, variant ->
                variantRow(row.id, document, variant, ordinal)
            },
            speakers = readable.flatMap { (id, words) -> speakerRows(row.id, document, id, words) },
            words = readable.flatMap { (id, words) ->
                words.mapIndexed { index, word ->
                    WordEntity(row.id, id, index, word.startMs, word.endMs, word.speaker, word.text)
                }
            },
            body = shownWords.joinToString(" ") { it.text },
        )
    }

    /** The document's own error: the I/O error of an unreadable file, or why its metadata is invalid. */
    private fun errorOf(document: CassiniDocument): String? = when (document.documentState) {
        DocumentState.UNREADABLE, DocumentState.INVALID_METADATA -> document.warnings.firstOrNull()
        else -> null
    }

    private fun variantRow(noteId: String, document: CassiniDocument, variant: CassiniDocument.Variant, ordinal: Int): VariantEntity {
        val words = variant.transcript?.words
        val processing = processingOf(document, variant.id)
        return VariantEntity(
            noteId = noteId,
            variantId = variant.id,
            ordinal = ordinal,
            isDefault = variant.id == document.defaultId,
            createdAtUtc = variant.descriptor.optString("createdAtUtc").ifEmpty { null },
            language = variant.transcript?.language ?: variant.descriptor.optString("language"),
            kind = if (processing?.has(MERGES) == true) VariantKind.MERGED else VariantKind.TRANSCRIBED,
            derivedFrom = processing?.optString(DERIVED_FROM)?.ifEmpty { null },
            wordCount = words?.size ?: variant.descriptor.optInt("wordCount"),
            speakerCount = words?.mapTo(HashSet()) { it.speaker }?.size ?: 0,
            speakersToldApart = processing?.has(DIARIZATION) == true,
            inferenceMs = processing?.takeIf { it.has(INFERENCE_MS) }?.optLong(INFERENCE_MS),
            readable = words != null,
            error = variant.error,
        )
    }

    private fun speakerRows(noteId: String, document: CassiniDocument, variantId: String, words: List<Word>): List<SpeakerEntity> =
        SpeakerStats.of(words).values.map { stat ->
            val label = document.speakerLabel(stat.speakerId) ?: stat.speakerId
            val placeholder = DefaultLabels.isDefault(stat.speakerId, label)
            SpeakerEntity(
                noteId = noteId,
                variantId = variantId,
                speakerId = stat.speakerId,
                ordinal = stat.ordinal,
                label = label,
                isPlaceholder = placeholder,
                placeholderNumber = if (placeholder) placeholderNumber(label) ?: (stat.ordinal + 1) else null,
                speakingMs = stat.speakingMs,
                wordCount = stat.wordCount,
                turnCount = stat.turnCount,
                firstMs = stat.firstMs,
                sampleStartMs = stat.sample?.startMs,
                sampleEndMs = stat.sample?.endMs,
            )
        }

    /** The number of a placeholder label such as "Speaker 3"; null for a raw speaker id or a blank label. */
    private fun placeholderNumber(label: String): Int? =
        DefaultLabels.PREFIXES.firstNotNullOfOrNull { prefix ->
            if (label.startsWith(prefix)) label.removePrefix(prefix).toIntOrNull() else null
        }

    /** The provenance record of one variant: `provenance.speechToText.<variantId>`. */
    fun processingOf(document: CassiniDocument, variantId: String): JSONObject? =
        document.manifest?.optJSONObject("provenance")?.optJSONObject("speechToText")?.optJSONObject(variantId)

    private const val MERGES = "x-speakerMerges"
    private const val DERIVED_FROM = "x-derivedFromTranscript"
    private const val DIARIZATION = "x-speakerDiarization"
    private const val INFERENCE_MS = "x-inferenceMs"
}
