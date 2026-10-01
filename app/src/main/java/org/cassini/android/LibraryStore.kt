package org.cassini.android

import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** All access is on the main thread, except the one-time recovery before the library is displayed. */
class LibraryStore(private val directory: File) {
    private val file = AtomicFile(File(directory, "library.json"))

    fun load(): List<LibraryNote> {
        if (!file.baseFile.exists() && !File(directory, "library.json.bak").exists()) return emptyList()
        val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        require(json.getInt("version") == 1)
        val notes = json.getJSONArray("notes")
        return (0 until notes.length()).map { LibraryNote.fromJson(notes.getJSONObject(it)) }.sortedByDescending { it.createdAt }
    }

    fun save(session: Session): Session {
        if (session.uri == null) return session
        val notes = load().toMutableList()
        val previous = notes.firstOrNull { it.id == session.libraryId || it.session.uri == session.uri }
        val id = previous?.id ?: session.libraryId ?: UUID.randomUUID().toString()
        val note = LibraryNote.update(previous, session, id, System.currentTimeMillis())
        notes.removeAll { it.id == id }
        notes.add(note)
        write(notes)
        return note.session
    }

    private fun write(notes: List<LibraryNote>) {
        val json = JSONObject().put("version", 1).put("notes", JSONArray(notes.map { it.json() }))
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) { file.failWrite(stream); throw error }
    }

    /** Recover older retained portable documents once; variants of one meeting share a card. */
    fun migrate(latest: Session) {
        val marker = File(directory, "library-migrated")
        if (marker.exists()) return
        val existing = load()
        val recovered = linkedMapOf<String, LibraryNote>()
        val currentKey = latest.document?.let { path ->
            try { CassiniDocument.read(File(path).readBytes()).manifest?.getJSONObject("meeting")?.getString("id") }
            catch (_: Exception) { null }
        }
        File(directory, "documents").listFiles().orEmpty().filter { it.extension == "opus" }
            .sortedBy { it.lastModified() }.forEach { source ->
                try {
                    if (source.length() > OggOpus.MAX_FILE_BYTES) return@forEach
                    val doc = CassiniDocument.read(source.readBytes())
                    val key = doc.manifest?.optJSONObject("meeting")?.optString("id")?.takeIf { it.isNotEmpty() } ?: source.name
                    if (key == currentKey && latest.document != source.absolutePath) return@forEach
                    val selected = doc.selected(if (latest.document == source.absolutePath) latest.selectedVariant else null)
                    val session = if (latest.document == source.absolutePath) latest.copy(transcript = selected?.transcript)
                    else Session(uri = Uri.fromFile(source).toString(), document = source.absolutePath,
                        name = doc.title.ifBlank { source.name }, transcript = selected?.transcript,
                        selectedVariant = selected?.id, durationMs = doc.manifest?.optJSONObject("audio")?.optLong("durationMs") ?: 0)
                    val previous = existing.firstOrNull { it.session.uri == session.uri } ?: recovered[key]
                    recovered[key] = LibraryNote.update(previous, session, previous?.id ?: UUID.randomUUID().toString(), source.lastModified())
                } catch (error: Exception) { Log.w("Cassini", "Could not recover document ${source.name}", error) }
            }
        // Prefer the user's current document/selected variant over older versions of that meeting.
        latest.uri?.let { uri ->
            val entry = recovered.entries.firstOrNull { note -> note.value.session.uri == uri }
            val previous = entry?.value ?: existing.firstOrNull { it.session.uri == uri }
            val id = previous?.id ?: latest.libraryId ?: UUID.randomUUID().toString()
            val note = LibraryNote.update(previous, latest.copy(transcript = latest.transcript ?: previous?.session?.transcript), id, System.currentTimeMillis())
            recovered[entry?.key ?: id] = note
        }
        val recoveredPaths = recovered.values.map { it.session.uri }.toSet()
        write(existing.filter { it.session.uri !in recoveredPaths } + recovered.values)
        marker.outputStream().use { it.write(byteArrayOf(1)); it.fd.sync() }
    }
}
