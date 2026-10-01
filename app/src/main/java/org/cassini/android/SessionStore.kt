package org.cassini.android

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

data class Session(
    val uri: String? = null, val name: String = "", val transcript: Transcript? = null,
    val durationMs: Long = 0, val inferenceMs: Long = 0, val positionMs: Int = 0,
    val fp32: Boolean = false, val resultPrecision: String = "INT8",
    val document: String? = null, val selectedVariant: String? = null,
)

class SessionStore(directory: File) {
    private val file = AtomicFile(File(directory, "session.json"))
    fun load(): Session = try {
        val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        Session(
            uri = json.optString("uri").takeIf { it.isNotEmpty() }, name = json.optString("name"),
            transcript = json.optJSONObject("transcript")?.let { Transcript.fromJson(it.toString()) },
            durationMs = json.optLong("durationMs"), inferenceMs = json.optLong("inferenceMs"),
            positionMs = json.optInt("positionMs"), fp32 = json.optBoolean("fp32"),
            resultPrecision = json.optString("resultPrecision", "INT8"),
            document = json.optString("document").takeIf { it.isNotEmpty() },
            selectedVariant = json.optString("selectedVariant").takeIf { it.isNotEmpty() },
        )
    } catch (_: Exception) { Session() }

    fun save(session: Session) {
        val json = JSONObject().put("uri", session.uri ?: "").put("name", session.name)
            .put("transcript", session.transcript?.takeIf { session.document == null }?.let { JSONObject(it.json()) })
            .put("durationMs", session.durationMs).put("inferenceMs", session.inferenceMs)
            .put("positionMs", session.positionMs).put("fp32", session.fp32)
            .put("resultPrecision", session.resultPrecision)
            .put("document", session.document ?: "").put("selectedVariant", session.selectedVariant ?: "")
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
