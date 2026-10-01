package org.cassini.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CassiniDocumentTest {
    @Test fun publishedConformanceVectors() {
        val directory = File(System.getProperty("cassini.conformance", "../cassini-format/spec/conformance")!!)
        if (System.getProperty("cassini.conformance.required").toBoolean()) {
            assertTrue("Required Cassini conformance directory is missing: $directory", directory.isDirectory)
        }
        assumeTrue("Checkout cassini-format beside this repository for the published conformance suite", directory.isDirectory)
        val vectors = JSONObject(File(directory, "index.json").readText()).getJSONArray("vectors")
        val failures = mutableListOf<String>()
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i); val expected = vector.getJSONObject("expect")
            val doc = CassiniDocument.read(File(directory, vector.getString("file")).readBytes())
            val id = vector.getString("id")
            if (expected.getString("state") != doc.state && !(expected.getString("state") == "unverified" && doc.state == "ok")) failures += "$id: expected ${expected.getString("state")}, got ${doc.state} ${doc.warnings}"
            expected.optJSONArray("transcriptIds")?.let { ids -> assertEquals(id, (0 until ids.length()).map { ids.getString(it) }, doc.variants.map { it.id }) }
            expected.optString("defaultTranscriptId").takeIf { it.isNotEmpty() }?.let { assertEquals(id, it, doc.defaultId) }
            expected.optJSONObject("wordCounts")?.let { counts ->
                assertEquals(id, counts.keys().asSequence().associateWith { counts.getInt(it) }, doc.variants.filter { it.transcript != null }.associate { it.id to it.transcript!!.words.size })
            }
            if (doc.manifest == null) assertTrue(id, doc.variants.isEmpty())
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
    private fun silence(): ByteArray {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0, 0, -128, -69, 0, 0, 0, 0, 0)
        return OggOpus.mux(OggOpus.Stream(head, CassiniDocument.tagsPacket(listOf("X_CUSTOM=keep=me")),
            listOf(byteArrayOf(-8, -1, -2)), 960, true), CassiniDocument.tagsPacket(listOf("X_CUSTOM=keep=me")))
    }
    @Test fun newDocumentSealsAudioAndAppendedVariantPreservesExtensions() {
        val transcript = Transcript(listOf(Word("spk_1", 0, 20, "Ciao")), "it")
        val bytes = CassiniDocument.create(silence(), transcript, "Italian sample", JSONObject().put("engine", "sherpa-onnx"))
        val original = CassiniDocument.read(bytes)
        assertEquals("ok", original.state); assertEquals(transcript, original.selected(null)?.transcript)
        assertTrue(original.comments.contains("X_CUSTOM=keep=me"))
        val withPrivate = original.copy(comments = original.comments + listOf("CASSINI_AUDIO_FUTURE=keep", "CASSINI_PAYLOAD_FUTURE=keep"))
        original.manifest!!.put("extension", JSONObject().put("keep", true))
        original.manifest.getJSONArray("speakers").getJSONObject(0).put("pronouns", "they")
        val next = CassiniDocument.read(CassiniDocument.create(bytes, transcript.copy(language = "en"), "Ignored", JSONObject(), withPrivate))
        assertEquals("ok", next.state); assertEquals(2, next.variants.size)
        assertTrue(next.comments.contains("CASSINI_AUDIO_FUTURE=keep")); assertTrue(next.comments.contains("CASSINI_PAYLOAD_FUTURE=keep"))
        assertTrue(next.manifest!!.getJSONObject("extension").getBoolean("keep"))
        assertEquals("they", next.manifest.getJSONArray("speakers").getJSONObject(0).getString("pronouns"))
        assertEquals(original.variants[0].body, next.variants[0].body)
        assertEquals(OggOpus.read(bytes).digest(), OggOpus.read(CassiniDocument.create(bytes, transcript, "Again", JSONObject(), original)).digest())
    }
    @Test fun strictPayloadRejectsDuplicatesTrailingGzipAndBadLengths() {
        for (text in listOf("{\"x\":1,\"x\":2}", "{\"a\":{\"x\":1,\"x\":2}}", "{\"x\":NaN}", "{\"x\":01}", "{'x':1}")) {
            try { CassiniPayload.json(text); fail(text) } catch (_: IllegalArgumentException) {} catch (_: org.json.JSONException) {}
        }
        val (ref, tags) = CassiniPayload.encode("TEST_", CassiniPayload.MIME, "{\"ok\":true}")
        val chunk = tags.last().substringAfter('=')
        assertEquals("{\"ok\":true}", CassiniPayload.decode(listOf(chunk), ref.getLong("rawBytes"), ref.getLong("gzipBytes"), ref.getString("sha256")))
        val compressed = java.util.Base64.getUrlDecoder().decode(chunk)
        val extra = java.util.Base64.getUrlEncoder().encodeToString(compressed + compressed)
        try { CassiniPayload.decode(listOf(extra), ref.getLong("rawBytes"), compressed.size * 2L, ref.getString("sha256")); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun truncatedAudioKeepsReadableMetadata() {
        val bytes = CassiniDocument.create(silence(), Transcript(emptyList()), "Truncated", JSONObject())
        val doc = CassiniDocument.read(bytes.copyOf(bytes.size - 1))
        assertEquals("unverified", doc.state)
        assertNotNull(doc.selected(null)?.transcript)
    }
    @Test fun crcFailureKeepsTranscriptButDoesNotVerifyAudio() {
        val bytes = CassiniDocument.create(silence(), Transcript(emptyList()), "CRC", JSONObject())
        val changed = bytes.copyOf(); changed[22] = (changed[22].toInt() xor 1).toByte()
        val doc = CassiniDocument.read(changed)
        assertEquals("unverified", doc.state); assertNotNull(doc.selected(null)?.transcript)
    }
}
