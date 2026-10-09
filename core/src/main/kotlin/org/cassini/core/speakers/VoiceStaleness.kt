package org.cassini.core.speakers

import org.cassini.core.models.ModelCatalog

/**
 * Whether a saved voice can still be compared with new prints. A voice is stale when it was made with another
 * voiceprint model than the pinned one, or when its mean does not have [ModelCatalog.VOICEPRINT_DIM] values. Saved
 * voices shows "Name them again in a note to keep recognising them" for a stale voice, and the Name a speaker sheet
 * lists it last without a score.
 */
object VoiceStaleness {
    fun isStale(voice: Voice): Boolean =
        voice.model != ModelCatalog.voiceprintModel.sha256 || voice.mean.size != ModelCatalog.VOICEPRINT_DIM
}
