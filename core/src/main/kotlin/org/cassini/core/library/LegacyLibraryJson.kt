package org.cassini.core.library

import org.cassini.core.format.Transcript
import org.json.JSONObject

/**
 * Read-only parsers for the 0.0.x/0.1.0 catalogue files: `library.json` version 1 and `session.json`. Absent or
 * `"null"` strings read as null, and the retired `fp32` and `modelChoice` members are ignored.
 */
object LegacyLibraryJson {
    /** `library.json`: the notes, newest first. */
    fun library(text: String): List<LibraryNote> {
        val json = JSONObject(text)
        require(json.getInt("version") == 1)
        val notes = json.getJSONArray("notes")
        return (0 until notes.length()).map { libraryNote(notes.getJSONObject(it)) }.sortedByDescending { it.createdAt }
    }

    /** One entry of `library.json` `notes`. */
    fun libraryNote(json: JSONObject): LibraryNote {
        val id = json.getString("id")
        val value = json.getJSONObject("session")
        fun optional(key: String) = value.optString(key).takeIf { it.isNotEmpty() && it != "null" }
        return LibraryNote(id, json.getLong("createdAt"), Session(
            uri = optional("uri"), name = value.getString("name"), document = optional("document"),
            selectedVariant = optional("selectedVariant"), libraryId = id,
            transcript = value.optJSONObject("transcript")?.let { Transcript.fromJson(it.toString()) },
            durationMs = value.optLong("durationMs"), positionMs = value.optInt("positionMs"),
            inferenceMs = value.optLong("inferenceMs"), processingMs = value.optLong("processingMs"),
            resultPrecision = value.optString("resultPrecision"), processingPaused = value.optBoolean("processingPaused")), json.optString("text"))
    }

    /** `session.json`. Throws on text that is not a JSON object; the caller decides what an unreadable file means. */
    fun session(text: String): Session {
        val json = JSONObject(text)
        return Session(
            uri = json.optString("uri").takeIf { it.isNotEmpty() }, name = json.optString("name"),
            transcript = json.optJSONObject("transcript")?.let { Transcript.fromJson(it.toString()) },
            durationMs = json.optLong("durationMs"), inferenceMs = json.optLong("inferenceMs"),
            positionMs = json.optInt("positionMs"),
            resultPrecision = json.optString("resultPrecision", "INT8"),
            document = json.optString("document").takeIf { it.isNotEmpty() },
            selectedVariant = json.optString("selectedVariant").takeIf { it.isNotEmpty() },
            processingMs = json.optLong("processingMs"), processingPaused = json.optBoolean("processingPaused"),
            libraryId = json.optString("libraryId").takeIf { it.isNotEmpty() },
            screen = json.optString("screen").takeIf { it.isNotEmpty() },
        )
    }
}
