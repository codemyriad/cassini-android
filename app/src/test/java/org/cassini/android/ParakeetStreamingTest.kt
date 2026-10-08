package org.cassini.android

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlin.random.Random

/**
 * The cutting over a recording that is still being decoded, and resumed from journaled words, gives
 * the words of one uninterrupted pass over the whole array. A fake recognizer reads word identities
 * from the samples, so seams, overlaps and joins behave as with real decodes but deterministically.
 */
class ParakeetStreamingTest {
    private val rate = 16_000

    /** Words of 200 ms at a level that encodes their number, 300 ms apart, with a longer pause every eight. */
    private fun recording(seconds: Int): FloatArray {
        val samples = FloatArray(seconds * rate)
        var at = rate / 2
        var id = 0
        while (at + rate / 5 < samples.size) {
            val level = 0.01f + id * 0.00001f
            for (i in at until at + rate / 5) samples[i] = level
            at += rate / 5 + (if (id % 8 == 7) rate * 7 / 10 else rate * 3 / 10)
            id++
        }
        return samples
    }

    /** Each run of equal non-zero samples is one word, on the clock of the window it was given. */
    private object FakeRecognizer : Parakeet.Recognizer {
        override fun decode(samples: FloatArray, sampleRate: Int): List<TimedWord> {
            val words = ArrayList<TimedWord>()
            var i = 0
            while (i < samples.size) {
                if (samples[i] == 0f) { i++; continue }
                val level = samples[i]
                val start = i
                while (i < samples.size && samples[i] == level) i++
                val id = Math.round((level - 0.01f) / 0.00001f)
                val word = Word("spk_1", start * 1000L / sampleRate, i * 1000L / sampleRate, "w$id")
                words += TimedWord(word, word.endMs)
            }
            return words
        }
    }

    private class Record(val words: List<TimedWord>, val end: Long)

    private fun transcribe(source: AudioSource, resume: TranscriptJournal.State? = null, records: MutableList<Record>? = null): Transcript =
        Parakeet.runCutting(source, FakeRecognizer, null, DecodePolicy.WHOLE_SPANS, Parakeet.Cutting.QUIET, resume,
            { words, end -> records?.add(Record(words, end)) }, {})

    @Test fun streamingFromTheCacheGivesTheWordsOfTheWholeArray() {
        val samples = recording(150)
        val reference = transcribe(ArraySource(PcmAudio(samples, rate)))
        assertTrue(reference.words.size > 150)
        assertEquals(reference.words.map { it.text }.distinct().size, reference.words.size)

        val cache = PcmCache.open(Files.createTempDirectory("pcm").toFile(), "stream-test-key")
        cache.openWriter(samples.size.toLong())
        val producer = Thread {
            val random = Random(7)
            var at = 0
            while (at < samples.size) {
                val n = minOf(random.nextInt(1, 40_000), samples.size - at)
                cache.append(samples.copyOfRange(at, at + n), n)
                at += n
                if (random.nextInt(4) == 0) Thread.sleep(1)
            }
            cache.finish()
        }
        producer.start()
        val streamed = transcribe(CacheSource(cache))
        producer.join()
        assertEquals(reference.words, streamed.words)
    }

    @Test fun settledWordsArriveOnceInOrderAndAddUpToTheTranscript() {
        val samples = recording(150)
        val records = ArrayList<Record>()
        val transcript = transcribe(ArraySource(PcmAudio(samples, rate)), records = records)
        assertTrue(records.size > 3)
        val settled = records.flatMap { it.words }
        assertEquals(transcript.words.map { it.text }, settled.map { it.word.text })
        assertEquals(records.map { it.end }, records.map { it.end }.sorted())
        // Everything after a record's end was still to come.
        records.forEachIndexed { index, record ->
            records.drop(index + 1).flatMap { it.words }.forEach { later ->
                assertTrue(later.word.startMs * rate / 1000 >= record.end)
            }
        }
    }

    @Test fun progressCountsTheSettledWordsAndCarriesOnlyTheProvisionalTail() {
        val samples = recording(150)
        var handedOn = 0
        val reports = ArrayList<Pair<Int, Parakeet.Progress>>()
        val transcript = Parakeet.runCutting(ArraySource(PcmAudio(samples, rate)), FakeRecognizer, null, DecodePolicy.WHOLE_SPANS,
            Parakeet.Cutting.QUIET, null, { words, _ -> handedOn += words.size }) { reports += handedOn to it }
        assertTrue(reports.size >= 2)
        reports.forEach { (count, progress) -> assertEquals(count, progress.settled) }
        val last = reports.last().second
        assertTrue(last.pending.isEmpty())
        assertEquals(transcript.words.size, last.settled)
    }

    @Test fun resumingFromAnyRecordGivesTheSameWords() {
        val samples = recording(150)
        val source = ArraySource(PcmAudio(samples, rate))
        val records = ArrayList<Record>()
        val reference = transcribe(source, records = records)
        for (k in 0 until records.size - 1) {
            val kept = records.take(k + 1)
            if (kept.last().end <= 0) continue
            val state = TranscriptJournal.State(kept.flatMap { it.words }, kept.last().end)
            val resumed = transcribe(source, state)
            assertEquals("resumed after record $k", reference.words.map { it.text }, resumed.words.map { it.text })
        }
    }

    @Test fun aResumeWhoseDecodeShiftsTheJournaledWordsNeverHandsThemOnAgain() {
        val samples = recording(150)
        val source = ArraySource(PcmAudio(samples, rate))
        val records = ArrayList<Record>()
        val reference = transcribe(source, records = records)
        for (k in 0 until records.size - 1) {
            val kept = records.take(k + 1)
            if (kept.last().end <= 0) continue
            // A real decode places the overlap words a little differently from the journal.
            val journaled = kept.flatMap { it.words }.map { TimedWord(it.word.copy(startMs = it.word.startMs - 200, endMs = it.word.endMs - 200), it.capMs - 200) }
            val later = ArrayList<Record>()
            val resumed = transcribe(source, TranscriptJournal.State(journaled, kept.last().end), later)
            val journal = journaled + later.flatMap { it.words }
            assertEquals("journal after record $k", reference.words.map { it.text }, journal.map { it.word.text })
            assertEquals("resumed after record $k", reference.words.map { it.text }, resumed.words.map { it.text })
        }
    }

    @Test fun aDecodeThatFailsIsAFailureNotTheEndOfTheRecording() {
        val cache = PcmCache.open(Files.createTempDirectory("pcm").toFile(), "failed-test-key")
        cache.openWriter(rate * 60L)
        cache.append(recording(40), rate * 40)
        cache.abandon()
        val error = assertThrows(UserFacingException::class.java) {
            transcribe(CacheSource(cache) { UserFacingException(Failure.AUDIO) })
        }
        assertEquals(Failure.AUDIO, error.failure)
    }

    @Test fun speechDetectorFeedOverAnArrivingSourceMatchesTheArray() {
        val samples = FloatArray(16_000 * 13 + 77) { (it % 501) / 501f }
        val fromArray = ArrayList<FloatArray>()
        val fromSource = ArrayList<FloatArray>()
        class Recording(val into: MutableList<FloatArray>) : SpeechDetector.Stream {
            override fun reset() {}
            override fun acceptWaveform(window: FloatArray) { into += window }
            override fun empty() = true
            override fun front() = throw IllegalStateException()
            override fun pop() {}
            override fun flush() {}
        }
        val padding = SpeechDetector.feed(Recording(fromArray), samples) {}
        val cache = PcmCache.open(Files.createTempDirectory("pcm").toFile(), "feed-test-key")
        cache.openWriter(samples.size.toLong())
        val producer = Thread {
            var at = 0
            while (at < samples.size) {
                val n = minOf(9_999, samples.size - at)
                cache.append(samples.copyOfRange(at, at + n), n); at += n
            }
            cache.finish()
        }
        producer.start()
        assertEquals(padding, SpeechDetector.feed(Recording(fromSource), CacheSource(cache), 0) {})
        producer.join()
        assertEquals(fromArray.size, fromSource.size)
        fromArray.indices.forEach { assertArrayEquals(fromArray[it], fromSource[it], 0f) }
    }
}
