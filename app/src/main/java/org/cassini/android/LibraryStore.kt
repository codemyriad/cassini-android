package org.cassini.android

import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The catalogue file. Screens and the processing service both write it, so every read-modify-write
 * holds one process-wide lock. Calls do file I/O: keep long ones off the main thread.
 */
class LibraryStore(private val directory: File) {
    private val file = AtomicFile(File(directory, "library.json"))

    /** Waits briefly for screens' queued writes ([SerialWriter.library]) so a read never misses one. Never call it on the main thread. */
    fun load(): List<LibraryNote> {
        SerialWriter.library.flush()
        return lock.withLock { read() }
    }

    private fun read(): List<LibraryNote> {
        if (!file.baseFile.exists() && !File(directory, "library.json.bak").exists()) return emptyList()
        val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        require(json.getInt("version") == 1)
        val notes = json.getJSONArray("notes")
        return (0 until notes.length()).map { LibraryNote.fromJson(notes.getJSONObject(it)) }.sortedByDescending { it.createdAt }
    }

    fun save(session: Session): Session = lock.withLock {
        if (session.uri == null) return session
        val notes = read().toMutableList()
        val previous = notes.firstOrNull { it.id == session.libraryId || it.session.uri == session.uri }
        val id = previous?.id ?: session.libraryId ?: UUID.randomUUID().toString()
        val note = LibraryNote.update(previous, session, id, System.currentTimeMillis())
        notes.removeAll { it.id == id }
        notes.add(note)
        write(notes)
        return note.session
    }

    /** Rewrites the note [id] from its current saved state; null when it no longer exists. */
    fun update(id: String, change: (Session) -> Session): Session? = lock.withLock {
        val notes = read().toMutableList()
        val index = notes.indexOfFirst { it.id == id }
        if (index < 0) return null
        val previous = notes[index]
        val note = LibraryNote.update(previous, change(previous.session), id, previous.createdAt)
        notes[index] = note
        write(notes)
        return note.session
    }

    /** Adds a session the catalogue does not hold yet. session.json alone may reference it. */
    fun adopt(session: Session) = lock.withLock {
        if (session.uri != null && read().none { it.id == session.libraryId || it.session.uri == session.uri }) save(session)
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
    fun migrate(latest: Session) = lock.withLock {
        val marker = File(directory, "library-migrated")
        if (marker.exists()) return@withLock
        val existing = read()
        val recovered = linkedMapOf<String, LibraryNote>()
        fun meeting(path: String) = try { CassiniDocument.read(File(path), verify = false).manifest?.getJSONObject("meeting")?.getString("id") }
            catch (_: Exception) { null }
        // Notes saved before this recovery keep their state. Like the current note, each stands for its meeting.
        val kept = existing.mapNotNull { it.session.document }.filter { it != latest.document }.toSet()
        val knownKeys = (kept + listOfNotNull(latest.document)).mapNotNull(::meeting).toSet()
        File(directory, "documents").listFiles().orEmpty().filter { it.extension == "opus" }
            .sortedBy { it.lastModified() }.forEach { source ->
                try {
                    val doc = CassiniDocument.read(source, verify = false)
                    val known = existing.firstOrNull { it.session.document == source.absolutePath }?.takeIf { source.absolutePath in kept }
                    if (known != null) {
                        // session.json does not carry a document's words, so a note adopted from it has no search text yet.
                        doc.selected(known.session.selectedVariant)?.transcript?.takeIf { known.text.isEmpty() }?.let {
                            recovered[known.id] = LibraryNote.update(known, known.session.copy(transcript = it), known.id, known.createdAt)
                        }
                        return@forEach
                    }
                    val key = doc.manifest?.optJSONObject("meeting")?.optString("id")?.takeIf { it.isNotEmpty() } ?: source.name
                    if (key in knownKeys && latest.document != source.absolutePath) return@forEach
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

    private companion object { val lock = ReentrantLock() }
}
