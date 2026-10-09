package org.cassini.data.notes

import org.cassini.core.Failure
import org.cassini.core.format.DocumentState
import org.cassini.core.library.JobKind

// The results the note and document repositories return instead of throwing (docs/architecture.md, section 4).

/** The result of an edit of a note's document: relabel, retitle or merge. */
sealed interface EditOutcome {
    /** The document was written; [variantId] is the new variant of a merge. */
    data class Done(val variantId: String? = null) : EditOutcome

    /** A job row exists: the document is the job's audio source and is not rewritten (architecture 3.1). */
    data object JobPending : EditOutcome

    /** The note has no document, or its [state] is not OK; non-OK documents are never sealed again. */
    data class NotEditable(val state: DocumentState?) : EditOutcome

    /** The request does not fit the document (an unknown variant or speaker, a merge of a speaker with itself). */
    data class Invalid(val reason: String) : EditOutcome

    data object NoNote : EditOutcome

    /** Writing failed; the old document is unchanged. */
    data class Failed(val failure: Failure, val detail: String?) : EditOutcome
}

/** The result of `refreshIfStale`. */
sealed interface RefreshOutcome {
    /** The cache matches the file; nothing was read. */
    data object Fresh : RefreshOutcome

    /** The cache was derived again from the document, whose verification gave [state]. */
    data class Refreshed(val state: DocumentState) : RefreshOutcome

    /** The note's audio is not a document. */
    data object NotDocument : RefreshOutcome

    data object NoNote : RefreshOutcome
}

/** The result of `adoptSealed`. */
sealed interface AdoptOutcome {
    /** The sealed document is the note's audio now, with every cache row; [state] is its verification result. */
    data class Adopted(val state: DocumentState) : AdoptOutcome

    /** No `work/sealed` marker: nothing to adopt (also after a completed adopt). */
    data object NothingToAdopt : AdoptOutcome

    /** The sealed output did not read back OK; it was deleted with its marker. */
    data object Unreadable : AdoptOutcome

    data object NoNote : AdoptOutcome
}

/** The result of a rename. */
sealed interface RenameOutcome {
    data object Renamed : RenameOutcome

    /** The title is blank or longer than `NoteTitles.MAX_LENGTH` after trimming. */
    data object Invalid : RenameOutcome

    data object NoNote : RenameOutcome
}

/** The result of "Save a copy" and the transcript exports. */
sealed interface ExportOutcome {
    data class Saved(val bytes: Long) : ExportOutcome

    /** There is nothing to export: no transcript, or a document that cannot be read. */
    data object NotAvailable : ExportOutcome

    data object NoNote : ExportOutcome

    data class Failed(val failure: Failure, val detail: String?) : ExportOutcome
}

/** What the startup sweep deleted (architecture 4.8, item 6), as paths relative to the files root. */
data class SweepReport(val deleted: List<String>)

/** The queue writes that Trash needs; `JobRepository` implements them (P4 step 4). */
interface JobQueueWriter {
    /**
     * Puts the job of [noteId] back in the queue at the back (`seq = max + 1`) as QUEUED with [kind], creating the
     * row when it is missing. Restore calls it for a note that was QUEUED when it was trashed.
     */
    suspend fun requeueAtBack(noteId: String, kind: JobKind)

    /** Stops the job of [noteId] if it runs and removes its row: step 1 of the purge (architecture 4.10). */
    suspend fun cancelAndRemove(noteId: String)
}

/** Deletes what a note left outside `notes/<id>/`: its voice files (`VoiceRepository.deleteForNote`, P4 step 5). */
fun interface NoteFilesCleaner {
    suspend fun deleteForNote(noteId: String)
}
