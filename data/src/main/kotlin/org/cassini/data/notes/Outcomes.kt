package org.cassini.data.notes

import android.util.Log
import org.cassini.core.Failure
import org.cassini.core.format.DocumentState
import org.cassini.core.library.JobKind
import org.cassini.data.voices.VoiceRepository
import java.io.IOException

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

/**
 * Deletes what a note left outside `notes/<id>/`: its sealed and pending voice files (`VoiceRepository`). Returns
 * false when a file is still there; the purge then keeps the note for the next run.
 */
fun interface NoteFilesCleaner {
    suspend fun deleteForNote(noteId: String): Boolean
}

/** The result of an import (architecture 4.2). */
sealed interface ImportOutcome {
    /** A new note was added. */
    data class Created(val noteId: String) : ImportOutcome

    /** A live note already has these bytes; nothing was added. A trashed note with the same bytes does not count. */
    data class Existing(val noteId: String) : ImportOutcome

    /**
     * Nothing was added: LARGE (over 2 GiB), SPACE ([neededBytes] free are needed), WAV (not 16-bit PCM), OPEN
     * (unreadable, or not audio that Cassini can read), or SAVE (a storage error after the copy). [detail] is a
     * diagnostic, never shown as is.
     */
    data class Rejected(val failure: Failure, val detail: String? = null, val neededBytes: Long = 0) : ImportOutcome
}

/**
 * Finishes the adopt of a sealed job output after a crash, for `reconcileOnStart` and the catalogue rebuild
 * (architecture 5.3). [of] builds the production one; tests can pass a fake.
 */
fun interface SealedAdopter {
    suspend fun adopt(noteId: String): AdoptOutcome

    companion object {
        /**
         * `adoptSealed` (the document, the row and the job row), then `VoiceRepository.adoptPending`, then
         * `deleteAdoptedWork` (marker last), then the release of the draft names in memory, so the next job of the
         * note starts with no labels, merges or live matches of this run. A crash or an I/O error at any point leaves
         * the marker, so the next start runs it again.
         */
        fun of(documents: DocumentRepository, voices: VoiceRepository): SealedAdopter = SealedAdopter { noteId ->
            documents.adoptSealed(noteId).also { outcome ->
                if (outcome is AdoptOutcome.Adopted) {
                    try {
                        voices.adoptPending(noteId)
                        documents.deleteAdoptedWork(noteId)
                        voices.releaseDraftNames(noteId)
                    } catch (error: IOException) {
                        Log.w("SealedAdopter", "Cannot finish the voices of $noteId; the next start tries again", error)
                    }
                }
            }
        }
    }
}

/** What `CatalogueRepository.rebuild` did. */
data class RebuildReport(
    /** Notes added from their directories. */
    val created: List<String>,
    /** Notes whose sealed job output was installed. */
    val adopted: List<String>,
    /** Notes that got a PAUSED(INTERRUPTED) job from a saved checkpoint. */
    val paused: List<String>,
    /** `note_voice_links` rows written. */
    val links: Int,
    /** Note ids whose voice files were deleted because the note has no row and no directory. */
    val deletedVoiceFiles: List<String>,
    /** Note directories that a storage error stopped; the next start tries those without a row again. */
    val failed: List<String> = emptyList(),
)
