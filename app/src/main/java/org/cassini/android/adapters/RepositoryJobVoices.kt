package org.cassini.android.adapters

import android.util.Log
import org.cassini.core.models.ModelCatalog
import org.cassini.core.speakers.NoteVoiceCodec
import org.cassini.core.speakers.SpeakerPrint
import org.cassini.core.speakers.Voice
import org.cassini.data.notes.DocumentRepository
import org.cassini.data.notes.NoteRepository
import org.cassini.data.voices.VoiceRepository
import org.cassini.engine.job.JobVoices
import java.io.IOException

/**
 * [JobVoices] over `VoiceRepository` (architecture 4.5 and 5.4).
 * Every function blocks; the job calls them off the main thread.
 */
class RepositoryJobVoices(
    private val voices: VoiceRepository,
    private val notes: NoteRepository,
    private val documents: DocumentRepository,
) : JobVoices {
    override fun peopleBlocking(): List<Voice> = voices.peopleBlocking()

    override fun generationBlocking(): Long = voices.generationBlocking()

    /** The prints of the note's selected variant, else of its document's default variant. */
    override fun noteVoicesBlocking(noteId: String): Map<String, SpeakerPrint> {
        val variant = notes.rowBlocking(noteId)?.selectedVariant ?: documents.readBlocking(noteId)?.defaultId
        return variant?.let { voices.printsBlocking(noteId, it) }.orEmpty()
    }

    /**
     * Stores [prints] (by speaker id) under `<variantId>/<speakerId>` in `voices/pending/<noteId>.json`. The repository
     * writes the pinned voiceprint model, so [model] must be that model. A failed write is logged: voiceprints are best
     * effort, and the transcript does not depend on them.
     */
    override fun savePendingBlocking(
        noteId: String,
        variantId: String,
        model: String,
        prints: Map<String, SpeakerPrint>,
        generation: Long,
    ) {
        require(model == ModelCatalog.voiceprintModel.sha256) { "Prints of another model: $model" }
        val prefix = NoteVoiceCodec.prefix(variantId)
        if (!voices.savePendingBlocking(noteId, prints.mapKeys { prefix + it.key }, generation)) {
            Log.w(TAG, "The pending prints of $noteId were not saved")
        }
    }

    /** Throws when an older pending file stays: adopt would merge the prints of an earlier run. */
    override fun discardPendingBlocking(noteId: String) {
        if (!voices.discardPendingBlocking(noteId)) throw IOException("Cannot delete the pending prints of $noteId")
    }

    private companion object {
        const val TAG = "JobVoices"
    }
}
