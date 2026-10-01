package org.cassini.android

import org.json.JSONObject

/** A local catalogue entry. The portable document remains authoritative when opened. */
data class LibraryNote(val id: String, val createdAt: Long, val session: Session, val text: String) {
    fun matches(query: String): Boolean = query.isBlank() || session.name.contains(query.trim(), true) || text.contains(query.trim(), true)

    fun snippet(query: String, limit: Int = 160): String {
        val start = if (query.isBlank()) 0 else (text.indexOf(query.trim(), ignoreCase = true) - 45).coerceAtLeast(0)
        return (if (start > 0) "…" else "") + text.drop(start).take(limit) + if (text.length > start + limit) "…" else ""
    }

    fun json(): JSONObject = JSONObject().put("id", id).put("createdAt", createdAt).put("text", text)
        .put("session", JSONObject().put("uri", session.uri).put("name", session.name)
            .put("document", session.document).put("selectedVariant", session.selectedVariant)
            .put("transcript", session.transcript?.takeIf { session.document == null }?.let { JSONObject(it.json()) })
            .put("durationMs", session.durationMs).put("positionMs", session.positionMs)
            .put("inferenceMs", session.inferenceMs).put("processingMs", session.processingMs)
            .put("resultPrecision", session.resultPrecision))

    companion object {
        fun fromJson(json: JSONObject): LibraryNote {
            val id = json.getString("id")
            val value = json.getJSONObject("session")
            fun optional(key: String) = value.optString(key).takeIf { it.isNotEmpty() && it != "null" }
            return LibraryNote(id, json.getLong("createdAt"), Session(
                uri = optional("uri"), name = value.getString("name"), document = optional("document"),
                selectedVariant = optional("selectedVariant"), libraryId = id,
                transcript = value.optJSONObject("transcript")?.let { Transcript.fromJson(it.toString()) },
                durationMs = value.optLong("durationMs"), positionMs = value.optInt("positionMs"),
                inferenceMs = value.optLong("inferenceMs"), processingMs = value.optLong("processingMs"),
                resultPrecision = value.optString("resultPrecision")), json.optString("text"))
        }

        /**
         * The session a recreated viewer returns to. Saved state can predate work that finished
         * while the viewer was stopped, so session.json wins when that viewer wrote it last.
         * Otherwise a note is known by its ID, which it keeps when retranscription moves it to
         * another file. Without a catalogue entry, session.json still counts when it holds that
         * ID, or the same file under no other ID.
         */
        fun shown(screen: String?, id: String?, uri: String?, latest: Session, notes: List<LibraryNote>): Session? {
            if (screen != null && latest.screen == screen) return latest.takeIf { it.uri != null }
            if (uri == null) return null
            val note = if (id != null) notes.firstOrNull { it.id == id } else notes.firstOrNull { it.session.uri == uri }
            return note?.session ?: latest.takeIf { if (id != null && it.libraryId != null) it.libraryId == id else it.uri == uri }
        }

        fun update(previous: LibraryNote?, session: Session, id: String, now: Long): LibraryNote =
            LibraryNote(id, previous?.createdAt ?: now, session.copy(libraryId = id),
                session.transcript?.words?.joinToString(" ") { it.text }
                    ?: previous?.text?.takeIf { session.document != null && previous.session.document == session.document && previous.session.selectedVariant == session.selectedVariant }.orEmpty())
    }
}
