package io.codemyriad.cassini.engine.whistle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WhistleStreamTest {
    @Test
    fun invalidInputIsRefusedBeforeVadOrRecognition() {
        val stream = WhistleStream(
            rate = 16000,
            probability = { error("VAD must not run") },
            transcribe = { error("ASR must not run") },
            segment = { _, _, _ -> },
        )
        for (samples in listOf(FloatArray(8193), floatArrayOf(Float.NaN), floatArrayOf(Float.POSITIVE_INFINITY))) {
            assertThrows(IllegalArgumentException::class.java) { stream.accept(samples) }
        }
    }

    @Test
    fun rawSegmentsStayExactAcrossMultipleRingWraps() {
        var recognized = FloatArray(0)
        var segments = 0
        val stream = WhistleStream(
            rate = 16000,
            probability = { 0.9f },
            transcribe = {
                recognized = it
                byteArrayOf(7)
            },
            segment = { range, json, _ ->
                val expected = FloatArray((range.endSample - range.startSample).toInt()) { sample(range.startSample + it) }
                assertArrayEquals(expected, recognized, 0f)
                assertArrayEquals(byteArrayOf(7), json)
                segments++
            },
        )
        var at = 0L
        val length = 1048701L
        while (at < length) {
            val count = minOf(8192L, length - at).toInt()
            stream.accept(FloatArray(count) { sample(at + it) })
            at += count
        }
        val summary = stream.finish()
        assertEquals(summary.frames, summary.voiced)
        assertTrue(segments >= 5)
    }

    @Test
    fun cachedSegmentSkipsNeuralTranscribeWhileKeepingExactSpeechGateState() {
        val length = 160_000L
        val cache = HashMap<Pair<String, Int>, ByteArray>()
        val firstSegments = mutableListOf<Triple<Long, Long, String>>()
        val firstSpans = mutableListOf<String>()
        var firstTranscribeCalls = 0
        val first = WhistleStream(
            rate = 16000,
            probability = { 0.9f },
            transcribe = {
                firstTranscribeCalls++
                """{"words":[{"word":"seg$firstTranscribeCalls","start":0.01,"end":0.10}]}""".toByteArray()
            },
            segment = { range, json, hash, cacheHit ->
                assertEquals(false, cacheHit)
                val count = (range.endSample - range.startSample).toInt()
                cache[hash to count] = json
                firstSpans += WhistlePrefixAudit.spanKey(range.startSample, range.endSample, hash)
                firstSegments += Triple(range.startSample, range.endSample, String(json))
            },
        )
        feed(first, length)
        val firstSummary = first.finish()
        assertTrue(firstTranscribeCalls >= 1)
        assertEquals(0, first.cacheHits)
        assertEquals(firstTranscribeCalls, first.neuralTranscribes)
        assertEquals(firstTranscribeCalls, cache.size)

        val secondSegments = mutableListOf<Triple<Long, Long, String>>()
        val secondHitSpans = mutableListOf<String>()
        var secondTranscribeCalls = 0
        val second = WhistleStream(
            rate = 16000,
            probability = { 0.9f },
            transcribe = {
                secondTranscribeCalls++
                error("Neural transcribe must be skipped for cached segments")
            },
            segment = { range, json, hash, cacheHit ->
                assertEquals(true, cacheHit)
                secondHitSpans += WhistlePrefixAudit.spanKey(range.startSample, range.endSample, hash)
                secondSegments += Triple(range.startSample, range.endSample, String(json))
            },
            cachedSegment = { hash, count -> cache[hash to count] },
        )
        feed(second, length)
        val secondSummary = second.finish()
        assertEquals(0, secondTranscribeCalls)
        assertEquals(firstTranscribeCalls, second.cacheHits)
        assertEquals(0, second.neuralTranscribes)
        assertEquals(firstSpans, secondHitSpans)
        assertEquals(firstSegments, secondSegments)
        assertEquals(firstSummary, secondSummary)
    }

    @Test
    fun prefixAuditFailsWhenOneValidCachedPrefixSegmentIsDropped() {
        val prefixLength = 500_000L
        val totalLength = 750_000L
        val cache = LinkedHashMap<Pair<String, Int>, ByteArray>()
        val precomputedSpans = ArrayList<String>()
        var preIdx = 0
        val precompute = WhistleStream(
            rate = 16000,
            probability = { 0.9f },
            transcribe = {
                preIdx++
                """{"words":[{"word":"w$preIdx","start":0.01,"end":0.10}]}""".toByteArray()
            },
            segment = { range, json, hash, cacheHit ->
                assertEquals(false, cacheHit)
                cache[hash to (range.endSample - range.startSample).toInt()] = json
                precomputedSpans += WhistlePrefixAudit.spanKey(range.startSample, range.endSample, hash)
            },
        )
        feed(precompute, prefixLength)
        assertEquals(2, precomputedSpans.size)

        // Intact cache: all precomputed prefix segments hit cache, only tail segments run neural inference.
        val (intactHits, intactNeural) = runStreamWithAudit(totalLength, cache, "tail")
        val baselineTotal = intactHits.size + intactNeural.size
        WhistlePrefixAudit.verifyNoNeuralPrefixRedecode(
            precomputedPrefixSpans = precomputedSpans,
            postStopCacheHitSpans = intactHits,
            postStopNeuralSpans = intactNeural,
            baselineTotalSegments = baselineTotal,
        )

        // Drop ONE valid prefix entry from cache: total segments still equals baselineTotal, so a sum-only check
        // would pass, whereas WhistlePrefixAudit must fail naming the re-decoded prefix span.
        val degradedCache = LinkedHashMap(cache).apply { remove(cache.keys.first()) }
        val (degradedHits, degradedNeural) = runStreamWithAudit(totalLength, degradedCache, "redecoded")
        assertEquals(baselineTotal, degradedHits.size + degradedNeural.size)
        val err = assertThrows(AssertionError::class.java) {
            WhistlePrefixAudit.verifyNoNeuralPrefixRedecode(
                precomputedPrefixSpans = precomputedSpans,
                postStopCacheHitSpans = degradedHits,
                postStopNeuralSpans = degradedNeural,
                baselineTotalSegments = baselineTotal,
            )
        }
        assertTrue(err.message.orEmpty().contains(precomputedSpans.first()))
    }

    private fun runStreamWithAudit(
        length: Long,
        cache: Map<Pair<String, Int>, ByteArray>,
        word: String,
    ): Pair<List<String>, List<String>> {
        val hitSpans = ArrayList<String>()
        val neuralSpans = ArrayList<String>()
        val stream = WhistleStream(
            rate = 16000,
            probability = { 0.9f },
            transcribe = { """{"words":[{"word":"$word","start":0.01,"end":0.10}]}""".toByteArray() },
            segment = { range, _, hash, cacheHit ->
                val span = WhistlePrefixAudit.spanKey(range.startSample, range.endSample, hash)
                if (cacheHit) hitSpans += span else neuralSpans += span
            },
            cachedSegment = { hash, count -> cache[hash to count] },
        )
        feed(stream, length)
        stream.finish()
        return hitSpans to neuralSpans
    }

    private fun feed(stream: WhistleStream, length: Long) {
        var at = 0L
        while (at < length) {
            val count = minOf(8192L, length - at).toInt()
            stream.accept(FloatArray(count) { sample(at + it) })
            at += count
        }
    }

    private fun sample(index: Long) = ((index % 4093) - 2046).toFloat() / 4096f
}
