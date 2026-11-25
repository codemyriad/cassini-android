package io.codemyriad.cassini.recorder

import android.net.Uri
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.data.notes.ImportOutcome
import io.codemyriad.cassini.data.notes.NoteRepository
import io.codemyriad.cassini.processing.StartOrigin
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * [RecorderLibrary] over the app's repositories: an import as "Open with" does it, and a transcription as the app
 * starts one by itself (origin AUTOMATIC: it clears no queue hold, and without model consent the note waits for it).
 * [placeholderLabel] gives "Speaker N" in the app language.
 */
class GraphRecorderLibrary(
    private val graph: AppGraph,
    private val placeholderLabel: (Int) -> String,
) : RecorderLibrary {
    override suspend fun import(file: File, displayName: String): LibraryImport {
        graph.ready.await()
        val outcome = graph.awaitCatalogue().imports.import(Uri.fromFile(file), displayName = displayName)
        return when (outcome) {
            is ImportOutcome.Created -> LibraryImport.Created(outcome.noteId)
            is ImportOutcome.Existing -> LibraryImport.Existing(outcome.noteId)
            is ImportOutcome.Rejected -> LibraryImport.Rejected(outcome.failure.name)
        }
    }

    override suspend fun transcribe(noteId: String) {
        graph.processing.transcribe(noteId, origin = StartOrigin.AUTOMATIC)
    }

    override suspend fun needsTranscription(noteId: String): Boolean {
        val catalogue = graph.awaitCatalogue()
        val note = catalogue.notes.note(noteId).first()?.note ?: return false
        return note.trashedAt == null && note.audioKind != AudioKind.DOCUMENT && catalogue.jobs.job(noteId) == null
    }

    override suspend fun transcripts(noteIds: Collection<String>): Map<String, RecorderTranscript> {
        if (noteIds.isEmpty()) return emptyMap()
        val notes = graph.awaitCatalogue().notes
        val result = LinkedHashMap<String, RecorderTranscript>()
        for (noteId in noteIds) {
            transcript(notes, noteId)?.let { result[noteId] = it }
        }
        return result
    }

    /** The shown variant of [noteId] when it is a finished, readable transcript. */
    private suspend fun transcript(notes: NoteRepository, noteId: String): RecorderTranscript? {
        val details = notes.note(noteId).first()
            ?.takeIf { it.note.audioKind == AudioKind.DOCUMENT && it.note.trashedAt == null }
        val variant = details?.variants?.let { variants ->
            variants.firstOrNull { it.variantId == details.note.selectedVariant }
                ?: variants.firstOrNull { it.isDefault }
        }
        val finished = variant?.takeIf { it.readable && it.wordCount > 0 }
        if (details == null || finished == null) return null
        val words = notes.words(noteId, finished.variantId)
        val speakers = details.speakers.filter { it.variantId == finished.variantId }
        val note = details.note
        return RecorderTranscript(
            finished.variantId,
            RecorderTranscripts.build(note.title, note.durationMs, words, speakers, placeholderLabel),
        )
    }
}
