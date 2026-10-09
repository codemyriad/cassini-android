package org.cassini.android

import android.util.AtomicFile
import org.cassini.core.library.LegacyLibraryJson
import org.cassini.core.library.Session
import org.json.JSONObject
import java.io.File

class SessionStore(directory: File) {
    private val file = AtomicFile(File(directory, "session.json"))
    fun load(): Session = try {
        LegacyLibraryJson.session(file.openRead().bufferedReader().use { it.readText() })
    } catch (_: Exception) { Session() }

    fun save(session: Session) {
        val json = JSONObject().put("uri", session.uri ?: "").put("name", session.name)
            .put("transcript", session.transcript?.takeIf { session.document == null }?.let { JSONObject(it.json()) })
            .put("durationMs", session.durationMs).put("inferenceMs", session.inferenceMs)
            .put("positionMs", session.positionMs)
            .put("resultPrecision", session.resultPrecision)
            .put("document", session.document ?: "").put("selectedVariant", session.selectedVariant ?: "")
            .put("processingMs", session.processingMs).put("processingPaused", session.processingPaused)
            .put("libraryId", session.libraryId ?: "").put("screen", session.screen ?: "")
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
