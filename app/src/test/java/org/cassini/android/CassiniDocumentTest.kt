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
    @Test fun retiredAsrProvenanceSurvivesAnInt8Retranscription() {
        val transcript = Transcript(listOf(Word("spk_1", 0, 20, "Prima.")), "it")
        val provenance = JSONObject().put("model", "nvidia/parakeet-tdt-0.6b-v3 FP32")
            .put("x-segmentation", "live 5 s steps with 1 s recorded overlap, 0.5 s synthetic tail, seams spliced at an aligned word")
        val bytes = CassiniDocument.create(silence(), transcript, "Old live note", provenance)
        val original = CassiniDocument.read(bytes)
        val nextBytes = CassiniDocument.create(bytes, transcript.copy(words = listOf(Word("spk_1", 0, 20, "Dopo."))),
            "Ignored", JSONObject().put("model", "nvidia/parakeet-tdt-0.6b-v3 INT8"), original)
        val next = CassiniDocument.read(nextBytes)
        assertEquals("ok", next.state)
        assertEquals(2, next.variants.size)
        assertEquals(original.variants[0].body, next.variants[0].body)
        assertEquals(original.manifest!!.getJSONObject("provenance").getJSONObject("speechToText").getJSONObject(original.variants[0].id).toString(),
            next.manifest!!.getJSONObject("provenance").getJSONObject("speechToText").getJSONObject(original.variants[0].id).toString())
        assertEquals(OggOpus.read(bytes).digest(), OggOpus.read(nextBytes).digest())
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
    private fun relabelled(bytes: ByteArray, labels: Map<String, String>): Pair<CassiniDocument, CassiniDocument> {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val source = File(dir, "a.opus").apply { writeBytes(bytes) }
            val before = CassiniDocument.read(source)
            return before to CassiniDocument.relabel(source, before, labels, File(dir, "b.opus"))
        } finally { dir.deleteRecursively() }
    }
    private fun tags(doc: CassiniDocument, filter: (String) -> Boolean) = doc.comments.filter { filter(it.substringBefore('=')) }
    @Test fun relabelChangesOnlyTheLabels() {
        val transcript = Transcript(listOf(Word("spk_1", 0, 20, "Ciao"), Word("spk_2", 20, 40, "Salve")), "it")
        val bytes = CassiniDocument.create(silence(), transcript, "Two", JSONObject().put("engine", "sherpa-onnx"))
        val base = CassiniDocument.read(bytes)
        base.manifest!!.put("x", JSONObject().put("keep", true)).getJSONArray("speakers").getJSONObject(0).put("pronouns", "she")
        val (before, after) = relabelled(CassiniDocument.create(bytes, transcript, "Ignored", JSONObject(), base), mapOf("spk_1" to "  Anna "))
        assertEquals("ok", after.state); assertTrue(after.warnings.isEmpty())
        assertEquals("Anna", after.speakerLabel("spk_1")); assertEquals("spk_2", after.speakerLabel("spk_2"))
        assertEquals("she", after.manifest!!.getJSONArray("speakers").getJSONObject(0).getString("pronouns"))
        assertTrue(after.manifest.getJSONObject("x").getBoolean("keep"))
        assertEquals(before.manifest!!.getJSONObject("meeting").toString(), after.manifest.getJSONObject("meeting").toString())
        assertEquals(before.manifest.getJSONObject("integrity").toString(), after.manifest.getJSONObject("integrity").toString())
        assertEquals(before.variants.map { it.id to it.body }, after.variants.map { it.id to it.body })
        assertEquals(tags(before) { !it.startsWith("CASSINI_PAYLOAD_") }, tags(after) { !it.startsWith("CASSINI_PAYLOAD_") })
        assertEquals(tags(before) { it == "CASSINI_PAYLOAD_SCHEMA" || it == "CASSINI_PAYLOAD_MIME" }, tags(after) { it == "CASSINI_PAYLOAD_SCHEMA" || it == "CASSINI_PAYLOAD_MIME" })
        assertNotEquals(tags(before) { it == "CASSINI_PAYLOAD_SHA256" }, tags(after) { it == "CASSINI_PAYLOAD_SHA256" })
        assertNotEquals(tags(before) { it == "CASSINI_PAYLOAD_RAW_BYTES" }, tags(after) { it == "CASSINI_PAYLOAD_RAW_BYTES" })
        before.manifest.getJSONArray("speakers").getJSONObject(0).put("label", "Anna")
        assertEquals(before.manifest.toString(), after.manifest.toString())
    }
    @Test fun relabelRejectsUnknownSpeakersAndBlankLabels() {
        val bytes = CassiniDocument.create(silence(), Transcript(listOf(Word("spk_1", 0, 20, "Ciao")), "it"), "One", JSONObject())
        for (labels in listOf(mapOf("spk_9" to "Anna"), mapOf("spk_1" to "  "))) {
            try { relabelled(bytes, labels); fail("$labels") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun relabelConformanceVectorWithUnknownMembers() {
        val file = File(System.getProperty("cassini.conformance", "../cassini-format/spec/conformance")!!, "opus/014-unknown-manifest-member.opus")
        assumeTrue("Checkout cassini-format beside this repository", file.isFile)
        val (before, after) = relabelled(file.readBytes(), mapOf("spk_a" to "Anna"))
        assertEquals("ok", after.state); assertEquals("Anna", after.speakerLabel("spk_a"))
        assertEquals(before.speakerLabel("spk_b"), after.speakerLabel("spk_b"))
        assertTrue(after.manifest!!.has("cassiniFutureField"))
        assertEquals(before.manifest!!.getJSONArray("speakers").getJSONObject(0).get("pronouns").toString(),
            after.manifest.getJSONArray("speakers").getJSONObject(0).get("pronouns").toString())
        assertEquals(before.variants.map { it.id to it.body }, after.variants.map { it.id to it.body })
        assertEquals(before.manifest.getJSONObject("integrity").getString("opusAudioSha256"), after.manifest.getJSONObject("integrity").getString("opusAudioSha256"))
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
