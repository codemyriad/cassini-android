package io.codemyriad.cassini.core.format

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeakerColourIndexTest {
    @get:Rule val folder = TemporaryFolder()

    private fun audio(comments: List<String> = emptyList()): ByteArray {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0, 0, -128, -69, 0, 0, 0, 0, 0)
        val tags = CassiniDocument.tagsPacket(comments)
        return OggOpus.mux(OggOpus.Stream(head, tags, listOf(byteArrayOf(-8, -1, -2)), 960, true), tags)
    }

    @Test fun overlappingTurnOrderUsesEarliestSpeechWithoutReorderingWords() {
        val words = listOf(Word("a", 2000, 2100, "late"), Word("b", 1000, 1100, "early"),
            Word("c", 1000, 1100, "tie"), Word("a", 500, 600, "earliest"))
        assertEquals(listOf("a", "b", "c"), words.speakersByFirstSpeech())
        val transcript = Transcript(words.take(3))
        val document = CassiniDocument.read(CassiniDocument.create(audio(), transcript, "Overlap", JSONObject()))
        assertEquals(listOf("b", "c", "a"), transcript.words.speakersByFirstSpeech())
        assertEquals(0, document.speakerColourIndex("b"))
        assertEquals(1, document.speakerColourIndex("c"))
        assertEquals(2, document.speakerColourIndex("a"))
        assertEquals(transcript, document.selected(null)!!.transcript)
        val speakers = document.manifest!!.getJSONArray("speakers")
        repeat(speakers.length()) { speakers.getJSONObject(it).remove("colourIndex") }
        assertEquals(0, document.speakerColourIndex("b"))
        assertEquals(2, document.speakerColourIndex("a"))
    }

    @Test fun slotsAreStoredBeforeNamingAndSurviveMergesAndRediarization() {
        val raw = Transcript((0..6).map { Word("s$it", it.toLong(), it + 1L, "word") })
        val source = folder.newFile("source.opus").apply {
            writeBytes(CassiniDocument.create(audio(), raw, "Meeting", JSONObject()))
        }
        val first = CassiniDocument.read(source)
        val stored = first.manifest!!.getJSONArray("speakers")
        assertEquals((0..6).toList(), (0..6).map { stored.getJSONObject(it).getInt("colourIndex") })
        assertEquals((0..6).toList(), (0..6).map { first.speakerColourIndex("s$it") })
        val namedFile = folder.newFile("named.opus")
        val named = CassiniDocument.relabel(source, first, mapOf("s1" to "Alex"), namedFile)
        assertEquals(1, named.speakerColourIndex("s1"))
        val merged = SpeakerMerges.apply(raw, mapOf("s0" to "s1"))
        val nextBytes = CassiniDocument.create(namedFile.readBytes(), merged, "Meeting", JSONObject(), named)
        val next = CassiniDocument.read(nextBytes)
        assertEquals(1, next.speakerColourIndex("s1"))
        assertEquals(0, next.speakerColourIndex("s0"))
        val reversed = Transcript(
            raw.words.reversed().mapIndexed { i, w -> w.copy(startMs = i.toLong(), endMs = i + 1L) },
        )
        val againBytes = CassiniDocument.create(source.readBytes(), reversed, "Meeting", JSONObject(), next)
        val again = CassiniDocument.read(againBytes)
        assertEquals((0..6).toList(), (0..6).map { again.speakerColourIndex("s$it") })
    }

    @Test fun firstSealAfterADraftMergeKeepsTheTargetsOriginalSlotInBothWriters() {
        val raw = Transcript(listOf(Word("a", 0, 1, "one"), Word("b", 1, 2, "two")))
        val merged = SpeakerMerges.apply(raw, mapOf("a" to "b"))
        val slots = mapOf("a" to 0, "b" to 1)
        val bytes = CassiniDocument.create(audio(), merged, "Meeting", JSONObject(), colourIndices = slots)
        assertEquals(1, CassiniDocument.read(bytes).speakerColourIndex("b"))
        val source = folder.newFile("audio.opus").apply { writeBytes(audio()) }
        val output = folder.newFile("streamed.opus")
        val streamed = CassiniDocument.create(
            source,
            merged,
            "Meeting",
            JSONObject(),
            null,
            emptyMap(),
            output,
            colourIndices = slots,
        )
        assertEquals(1, CassiniDocument.read(output).speakerColourIndex("b"))
        assertEquals(1, streamed.speakerColourIndex("b"))
    }

    @Test fun oldFileSlotsAreReadWithoutWritesAndPersistOnlyOnAnExplicitEdit() {
        val words = Transcript(listOf(Word("a", 0, 1, "one"), Word("b", 1, 2, "two")))
        val document = CassiniDocument.read(CassiniDocument.create(audio(), words, "Meeting", JSONObject()))
        val manifest = JSONObject(document.manifest.toString())
        val speakers = manifest.getJSONArray("speakers")
        for (i in 0 until speakers.length()) speakers.getJSONObject(i).remove("colourIndex")
        speakers.getJSONObject(1).put("provenance", "unnamed")
        manifest.put("speakers", org.json.JSONArray().put(speakers.getJSONObject(1)).put(speakers.getJSONObject(0)))
        val comments = document.comments.filterNot {
            it.startsWith("CASSINI_PAYLOAD_") && !it.startsWith("CASSINI_PAYLOAD_SCHEMA=")
        } +
            CassiniPayload.encode("CASSINI_PAYLOAD_", CassiniPayload.MIME, manifest.toString()).second
        val source = folder.newFile("legacy.opus").apply { writeBytes(audio(comments)) }
        val bytes = source.readBytes()
        val legacy = CassiniDocument.read(source)
        assertEquals("ok", legacy.state)
        assertEquals("unnamed", legacy.speakerProvenance("b"))
        assertEquals(1, legacy.speakerColourIndex("b"))
        org.junit.Assert.assertArrayEquals(bytes, source.readBytes())
        val output = folder.newFile("renamed.opus")
        val renamed = CassiniDocument.relabel(source, legacy, mapOf("b" to "Alex"), output)
        org.junit.Assert.assertNull(renamed.speakerProvenance("b"))
        assertEquals(1, renamed.manifest!!.getJSONArray("speakers").getJSONObject(0).getInt("colourIndex"))
        org.junit.Assert.assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun legacyColourLookupsScanTheTranscriptOnlyOnce() {
        var reads = 0
        val words = object : AbstractList<Word>() {
            override val size: Int = 50
            override fun get(index: Int): Word {
                reads++
                return Word("s$index", index.toLong(), index + 1L, "word")
            }
        }
        val speakers = org.json.JSONArray()
        repeat(words.size) { speakers.put(JSONObject().put("id", "s$it").put("label", "s$it")) }
        val doc = CassiniDocument(
            "ok",
            JSONObject().put("speakers", speakers),
            listOf(CassiniDocument.Variant("words", JSONObject(), null, Transcript(words))),
        )
        repeat(words.size) { assertEquals(it, doc.speakerColourIndex("s$it")) }
        assertEquals(words.size, reads)
    }

    @Test fun optionalMalformedAndMaximumColourHintsCannotPreventAnotherTranscript() {
        val transcript = Transcript(listOf(Word("a", 0, 1, "one"), Word("b", 1, 2, "two")))
        val bytes = CassiniDocument.create(audio(), transcript, "Meeting", JSONObject())
        for (hint in listOf("unknown", Int.MAX_VALUE - 1, Int.MAX_VALUE)) {
            val existing = CassiniDocument.read(bytes)
            existing.manifest!!.getJSONArray("speakers").getJSONObject(0).put("colourIndex", hint)
            val next = Transcript(transcript.words + listOf(Word("c", 2, 3, "three"), Word("d", 3, 4, "four")))
            val written = CassiniDocument.read(CassiniDocument.create(bytes, next, "Meeting", JSONObject(), existing))
            assertEquals("ok", written.state)
            org.junit.Assert.assertTrue(written.speakerColourIndex("c")!! >= 0)
            org.junit.Assert.assertTrue(written.speakerColourIndex("d")!! >= 0)
            assertEquals(1, written.speakerColourIndex("b"))
        }
    }
}
