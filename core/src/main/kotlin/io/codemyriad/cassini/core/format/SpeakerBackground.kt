package io.codemyriad.cassini.core.format

import org.json.JSONArray
import org.json.JSONObject

/**
 * Background voices (cassini-ux D112 / L-86, `meeting.schema.json` `speakers[].background`): a speaker the
 * microphone picked up who is not part of the conversation. The flag is `background: true`; absent means not a
 * background voice, and switching it off removes the key (never `false`). The label, colour slot, merges and the
 * transcript bodies stay as they are, so switching back restores the speaker as it was.
 *
 * Where it is stored (docs/data-formats.md, "Background voices"):
 *
 * - On the manifest speaker record, beside `colourIndex`: Android has no `meeting.json` and writes no
 *   `x["codemyriad.io"]` of its own, so its speaker table is the manifest's `speakers[]`. Unknown members of a
 *   speaker record are carried forward by every Cassini reader.
 * - In `x["codemyriad.io"].meeting.speakers` (MAPPING.md), where the other Cassini apps read it, when the file
 *   already carries that object at schema 1 and it lists the speaker. Android never creates the object: the desktop
 *   validates it against `x-schema.json` before an edit, and a partial one would break that.
 *
 * Reading, the shared copy wins for every id it lists; otherwise the manifest record decides.
 */
object SpeakerBackground {
    const val KEY = "background"
    private const val X_DOMAIN = "codemyriad.io"
    private const val X_SCHEMA = 1L

    /** The ids marked as background voices in [manifest]. */
    fun of(manifest: JSONObject?): Set<String> {
        manifest ?: return emptySet()
        val shared = sharedSpeakers(manifest)?.let(::flags).orEmpty()
        val own = manifest.optJSONArray("speakers")?.let(::flags).orEmpty()
        return (own + shared).filterValues { it }.keys
    }

    /**
     * Sets or clears the flag of each speaker in [changes] (by id) on [manifest] in place: on its `speakers[]` record
     * and, when listed there, on its `x["codemyriad.io"].meeting.speakers` entry. Unknown ids are ignored here; the
     * caller checks them.
     */
    fun apply(manifest: JSONObject, changes: Map<String, Boolean>) {
        if (changes.isEmpty()) return
        listOfNotNull(manifest.optJSONArray("speakers"), sharedSpeakers(manifest)).forEach { speakers ->
            for (i in 0 until speakers.length()) {
                val speaker = speakers.optJSONObject(i) ?: continue
                when (changes[speaker.optString("id")]) {
                    true -> speaker.put(KEY, true)
                    false -> speaker.remove(KEY)
                    null -> Unit
                }
            }
        }
    }

    /** `x["codemyriad.io"].meeting.speakers` of a schema 1 object; a newer schema is kept as it is, unread. */
    private fun sharedSpeakers(manifest: JSONObject): JSONArray? =
        manifest.optJSONObject("x")?.optJSONObject(X_DOMAIN)
            ?.takeIf { (it.opt("schema") as? Number)?.toLong() == X_SCHEMA }
            ?.optJSONObject("meeting")?.optJSONArray("speakers")

    /** id to flag for each record with an id; only the literal JSON `true` marks a background voice. */
    private fun flags(speakers: JSONArray): Map<String, Boolean> = (0 until speakers.length())
        .mapNotNull { speakers.optJSONObject(it) }
        .filter { it.optString("id").isNotEmpty() }
        .associate { it.optString("id") to (it.opt(KEY) == true) }
}

/** The speakers of this document marked as background voices ([SpeakerBackground]). */
val CassiniDocument.backgroundSpeakers: Set<String> get() = SpeakerBackground.of(manifest)
