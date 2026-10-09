package org.cassini.data.voices

import org.cassini.core.speakers.Match
import org.cassini.core.speakers.NoteVoiceCodec
import org.cassini.core.speakers.SpeakerPrint
import org.cassini.data.db.NoteVoiceLinkEntity
import org.json.JSONException

/**
 * The `note_voice_links` rows of one `voices/notes/<noteId>.json` file (NoteVoiceCodec version 2, keys
 * `<variantId>/<speakerId>`): one row per speaker print, with its match or NONE. Never an embedding.
 */
internal object VoiceLinks {
    /** The links in [text], or null when the file cannot be read (a newer version or broken JSON is left alone). */
    fun decode(noteId: String, text: String): List<NoteVoiceLinkEntity>? = try {
        of(noteId, NoteVoiceCodec.decode(text).second)
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** The links of [prints] (keys `<variantId>/<speakerId>`); a key without both parts gives no row. */
    fun of(noteId: String, prints: Map<String, SpeakerPrint>): List<NoteVoiceLinkEntity> =
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
}
