package io.codemyriad.cassini.recorder

import android.net.Uri
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.data.notes.ImportOutcome
import io.codemyriad.cassini.data.notes.NoteRepository
import io.codemyriad.cassini.processing.StartOrigin
import io.codemyriad.cassini.processing.TranscribeOutcome
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

    override suspend fun openLivePrecompute(partFile: File, displayName: String): LivePrecomputeSession? =
        graph.openRecorderLivePrecompute(partFile)

    override suspend fun transcribe(noteId: String) {
        val outcome = graph.processing.transcribe(noteId, origin = StartOrigin.AUTOMATIC)
        if (outcome is TranscribeOutcome.Queued) {
            graph.processing.drain()
        }
    }

    override suspend fun needsTranscription(noteId: String): Boolean {
        val catalogue = graph.awaitCatalogue()
        val note = catalogue.notes.note(noteId).first()?.note ?: return false
        return note.trashedAt == null && note.audioKind != AudioKind.DOCUMENT && catalogue.jobs.job(noteId) == null
    }

    override suspend fun activeTranscriptions(noteIds: Collection<String>): Int {
        if (noteIds.isEmpty()) return 0
        val jobs = graph.awaitCatalogue().jobs
        var count = 0
        for (noteId in noteIds) {
            val state = jobs.job(noteId)?.state
            if (state == JobState.QUEUED || state == JobState.RUNNING) count++
        }
        return count
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

    /**
     * The shown variant of [noteId] when it is a finished, readable transcript. A transcription that found no speech
     * (`wordCount == 0`) produces a valid 0-word CWX1 sidecar so the recorder displays `"No speech"` (`[T]` badge) and
     * completes the round-trip cleanly.
     */
    @Suppress("ReturnCount") // absent note, no readable variant, or an unrecoverable word cache
    private suspend fun transcript(notes: NoteRepository, noteId: String): RecorderTranscript? {
        notes.note(noteId).first()
            ?.takeIf { it.note.audioKind == AudioKind.DOCUMENT && it.note.trashedAt == null } ?: return null
        notes.refreshIfStale(noteId)
        val details = notes.note(noteId).first()
            ?.takeIf { it.note.audioKind == AudioKind.DOCUMENT && it.note.trashedAt == null } ?: return null
        val readable = details.variants.filter { it.readable }
        val withWords = readable.filter { it.wordCount > 0 }
        val finished = readable.firstOrNull { it.variantId == details.note.selectedVariant }
            ?: withWords.firstOrNull { it.isDefault }
            ?: withWords.firstOrNull()
            ?: readable.firstOrNull { it.isDefault }
            ?: readable.firstOrNull()
            ?: return null
        val words = if (finished.wordCount > 0) notes.words(noteId, finished.variantId) else emptyList()
        if (finished.wordCount > 0 && words.isEmpty()) return null
        val speakers = if (words.isEmpty()) emptyList() else details.speakers.filter { it.variantId == finished.variantId }
        val note = details.note
        return RecorderTranscript(
            finished.variantId,
            RecorderTranscripts.build(note.title, note.durationMs, words, speakers, placeholderLabel),
        )
    }
}
