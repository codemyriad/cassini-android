package org.cassini.data.voices

import org.cassini.core.speakers.Match
import org.cassini.core.speakers.NoteVoiceCodec
import org.cassini.data.db.NoteVoiceLinkEntity
import org.json.JSONException

/**
 * The `note_voice_links` rows of one `voices/notes/<noteId>.json` file (NoteVoiceCodec version 2, keys
 * `<variantId>/<speakerId>`): one row per speaker print, with its match or NONE. Never an embedding.
 */
internal object VoiceLinks {
    /** The links in [text], or null when the file cannot be read (a newer version or broken JSON is left alone). */
    fun decode(noteId: String, text: String): List<NoteVoiceLinkEntity>? = try {
        val (_, prints) = NoteVoiceCodec.decode(text)
        prints.mapNotNull { (key, print) ->
            val variantId = key.substringBefore('/', "")
            val speakerId = key.substringAfter('/', "")
            if (variantId.isEmpty() || speakerId.isEmpty()) {
                null
            } else {
                NoteVoiceLinkEntity(
                    noteId = noteId,
                    variantId = variantId,
                    speakerId = speakerId,
                    voiceId = print.match?.voiceId,
                    state = print.match?.state ?: Match.State.NONE,
                    score = print.match?.score ?: 0f,
                    seconds = print.seconds,
                )
            }
        }
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
