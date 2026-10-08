package org.cassini.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpeakerAttributionTest {
    private val source = Transcript(listOf(Word("spk_1", 0, 10, "Ciao"), Word("spk_1", 10, 20, "mondo.")), "it")
    private val turns = listOf(SpeakerTurn(0, 10, 3), SpeakerTurn(10, 20, 9))
    private fun silence(): ByteArray {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0, 0, -128, -69, 0, 0, 0, 0, 0)
        return OggOpus.mux(OggOpus.Stream(head, CassiniDocument.tagsPacket(emptyList()), listOf(byteArrayOf(-8, -1, -2)), 960, true), CassiniDocument.tagsPacket(emptyList()))
    }
    @Test fun derivingLabelsKeepsASRProvenanceAndWordClock() {
        val previous = JSONObject().put("engine", "Parakeet TDT").put("x-inferenceMs", 1234)
        val before = previous.toString()
        val derived = SpeakerAttribution.derive(source, turns, previous, "words", 750)
        assertEquals(source, derived.transcript.copy(words = derived.transcript.words.map { it.copy(speaker = "spk_1") }))
        assertEquals(before, previous.toString())
        assertEquals("Parakeet TDT", derived.processing.getString("engine"))
        assertEquals(1234, derived.processing.getInt("x-inferenceMs"))
        assertEquals("words", derived.processing.getString("x-derivedFromTranscript"))
        assertEquals(2, derived.processing.getJSONObject("x-speakerDiarization").getInt("speakerCount"))
        assertEquals(750, derived.processing.getJSONObject("x-speakerDiarization").getLong("elapsedMs"))
    }
    @Test fun newVariantPreservesAudioEarlierWordsAndNamedSpeakers() {
        val bytes = CassiniDocument.create(silence(), source, "Meeting", JSONObject().put("engine", "Parakeet"), speakerLabels = mapOf("spk_1" to "Alice"))
        val original = CassiniDocument.read(bytes)
        val audit = original.manifest!!.getJSONObject("provenance").getJSONObject("attribution")
        audit.put("ran", true).put("mode", "annotate").put("wordsMeasured", 2).put("wordsFlagged", 1)
            .put("thresholdDb", 17).put("x-producer", "desktop").remove("reason")
        val derived = SpeakerAttribution.derive(source, turns, JSONObject(), original.defaultId, 500, audit)
        val ids = derived.transcript.words.map { it.speaker }.distinct()
        val labels = ids.mapIndexed { index, id -> id to "Speaker ${index + 1}" }.toMap()
        val nextBytes = CassiniDocument.create(bytes, derived.transcript, "Ignored", derived.processing, original, labels)
        val next = CassiniDocument.read(nextBytes)
        assertEquals("ok", next.state)
        assertEquals(2, next.variants.size)
        assertEquals(original.manifest!!.getJSONObject("provenance").getJSONObject("attribution").toString(),
            next.manifest!!.getJSONObject("provenance").getJSONObject("attribution").toString())
        assertEquals(source, next.selected(original.defaultId)?.transcript)
        assertEquals(derived.transcript, next.selected(null)?.transcript)
        assertEquals(audit.toString(), derived.processing.getJSONObject("x-sourceAttribution").toString())
        assertEquals("Alice", next.speakerLabel("spk_1"))
        assertEquals("Speaker 1", next.speakerLabel(ids[0]))
        assertEquals(OggOpus.read(bytes).digest(), OggOpus.read(nextBytes).digest())
        assertEquals(original.variants[0].body, next.variants[0].body)
    }
    @Test fun eachPassHasItsOwnAnonymousSpeakerIds() {
        val first = SpeakerAttribution.derive(source, turns, null, null, 1)
        val second = SpeakerAttribution.derive(source, turns, null, null, 1)
        assertTrue(first.transcript.words.map { it.speaker }.intersect(second.transcript.words.map { it.speaker }.toSet()).isEmpty())
    }
    @Test fun emptyOrInvalidDetectionsCannotReplaceTheTranscript() {
        for (bad in listOf(emptyList(), listOf(SpeakerTurn(10, 0, 0)))) {
            try { SpeakerAttribution.derive(source, bad, null, null, 1); fail() }
            catch (error: UserFacingException) { assertEquals(Failure.SPEAKERS, error.failure) }
        }
    }
}
