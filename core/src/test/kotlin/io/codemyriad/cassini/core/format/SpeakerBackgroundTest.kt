package io.codemyriad.cassini.core.format

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** Background voices (cassini-ux D112 / L-86): stored, read back and cleared without touching anything else. */
class SpeakerBackgroundTest {
    @get:Rule val folder = TemporaryFolder()

    private fun audio(): ByteArray {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0, 0, -128, -69, 0, 0, 0, 0, 0)
        val tags = CassiniDocument.tagsPacket(emptyList())
        return OggOpus.mux(OggOpus.Stream(head, tags, listOf(byteArrayOf(-8, -1, -2)), 960, true), tags)
    }

    private val transcript = Transcript(
        listOf(Word("a", 0, 1, "one"), Word("b", 1, 2, "two"), Word("c", 2, 3, "three"), Word("a", 3, 4, "four")),
    )

    private fun source(): File = folder.newFile().apply {
        val labels = mapOf("a" to "Anna")
        writeBytes(CassiniDocument.create(audio(), transcript, "Meeting", JSONObject(), speakerLabels = labels))
    }

    /** [CassiniDocument.relabel] of [source] into [target] with [background] changes. */
    private fun edit(
        source: File,
        background: Map<String, Boolean>,
        labels: Map<String, String> = emptyMap(),
        existing: CassiniDocument = CassiniDocument.read(source),
        target: File = folder.newFile(),
    ): CassiniDocument = CassiniDocument.relabel(source, existing, labels, target, background)

    /** `x` with `codemyriad.io` at [schema] listing [speakers] in its `meeting`. */
    private fun ours(schema: Int, speakers: JSONArray): JSONObject {
        val meeting = JSONObject().put("speakers", speakers)
        return JSONObject().put("codemyriad.io", JSONObject().put("schema", schema).put("meeting", meeting))
    }

    private fun speaker(document: CassiniDocument, id: String): JSONObject {
        val speakers = document.manifest!!.getJSONArray("speakers")
        return (0 until speakers.length()).map { speakers.getJSONObject(it) }.first { it.getString("id") == id }
    }

    @Test fun switchingOnStoresTrueOnTheSpeakerAndKeepsLabelSlotAndBodies() {
        val source = source()
        val existing = CassiniDocument.read(source)
        val before = speaker(existing, "b").getLong("colourIndex")
        val marked = edit(source, mapOf("b" to true), existing = existing)
        assertEquals(setOf("b"), marked.backgroundSpeakers)
        assertEquals(true, speaker(marked, "b").get("background"))
        assertEquals("b", speaker(marked, "b").getString("label"))
        assertEquals(before, speaker(marked, "b").getLong("colourIndex"))
        assertEquals(existing.variants.map { it.body }, marked.variants.map { it.body })
        assertFalse(speaker(marked, "a").has("background"))
    }

    @Test fun switchingOffRemovesTheKeyNeverWritesFalse() {
        val source = source()
        val first = folder.newFile()
        val marked = edit(source, mapOf("b" to true, "c" to true), target = first)
        val cleared = edit(first, mapOf("b" to false), mapOf("b" to "Bruno"), existing = marked)
        assertEquals(setOf("c"), cleared.backgroundSpeakers)
        assertFalse(speaker(cleared, "b").has("background"))
        assertEquals("Bruno", speaker(cleared, "b").getString("label"))
    }

    @Test fun aNewTranscriptVersionKeepsTheFlag() {
        val source = source()
        val target = folder.newFile()
        val marked = edit(source, mapOf("c" to true), target = target)
        val added = CassiniDocument.create(target.readBytes(), transcript, "Meeting", JSONObject(), marked)
        val again = CassiniDocument.read(added)
        assertEquals(setOf("c"), again.backgroundSpeakers)
    }

    @Test fun theSharedCopyIsMirroredWhenPresentAndWinsWhenRead() {
        val source = source()
        val existing = CassiniDocument.read(source)
        val shared = JSONArray()
            .put(JSONObject().put("id", "b").put("name", JSONObject.NULL).put("provenance", "unnamed"))
            .put(JSONObject().put("id", "c").put("name", "Carla").put("provenance", "user").put("background", true))
        existing.manifest!!.put("x", ours(1, shared))
        // The shared copy says c is a background voice although the manifest record does not.
        assertEquals(setOf("c"), existing.backgroundSpeakers)
        val marked = edit(source, mapOf("b" to true, "c" to false), existing = existing)
        val mirrored = marked.manifest!!.getJSONObject("x").getJSONObject("codemyriad.io").getJSONObject("meeting")
            .getJSONArray("speakers")
        assertEquals(true, mirrored.getJSONObject(0).get("background"))
        assertFalse(mirrored.getJSONObject(1).has("background"))
        assertEquals("Carla", mirrored.getJSONObject(1).getString("name"))
        assertEquals(setOf("b"), marked.backgroundSpeakers)
    }

    @Test fun aNewerSharedSchemaIsLeftAsItIs() {
        val source = source()
        val existing = CassiniDocument.read(source)
        val shared = JSONArray().put(JSONObject().put("id", "b").put("background", true))
        existing.manifest!!.put("x", ours(2, shared))
        assertEquals(emptySet<String>(), existing.backgroundSpeakers)
        val written = edit(source, mapOf("b" to false), existing = existing)
        val kept = written.manifest!!.getJSONObject("x").getJSONObject("codemyriad.io").getJSONObject("meeting")
            .getJSONArray("speakers").getJSONObject(0)
        assertEquals(true, kept.get("background"))
    }

    @Test fun onlyTheLiteralTrueCounts() {
        val manifest = JSONObject().put(
            "speakers",
            JSONArray().put(JSONObject().put("id", "a").put("background", "true"))
                .put(JSONObject().put("id", "b").put("background", 1))
                .put(JSONObject().put("id", "c").put("background", true)),
        )
        assertEquals(setOf("c"), SpeakerBackground.of(manifest))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anUnknownSpeakerIsRefused() {
        val source = source()
        edit(source, mapOf("zz" to true))
    }

    /** The cassini-ux round-trip vector (D-1171, `vectors/library-vectors/round-trip/background-voice.opus`). */
    @Test fun readsTheSharedCassiniUxVector() {
        val bytes = javaClass.getResourceAsStream("/cross-app/background-voice.opus")!!.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("6f5ca216158372e23ac3a717c086b2a6f6dd6185416ff34065fce801e0f61caa", digest)
        val document = CassiniDocument.read(bytes)
        assertEquals(document.warnings.toString(), "ok", document.state)
        assertEquals(setOf("spk-3", "spk-4"), document.backgroundSpeakers)
        assertEquals("Marta", document.speakerLabel("spk-3"))
        val file = folder.newFile().apply { writeBytes(bytes) }
        val switched = edit(file, mapOf("spk-3" to false), existing = document)
        assertEquals(setOf("spk-4"), switched.backgroundSpeakers)
        assertEquals("Marta", switched.speakerLabel("spk-3"))
        assertArrayEquals(bytes, file.readBytes())
    }
}
