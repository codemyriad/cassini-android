package io.codemyriad.cassini.engine.whistle

import io.codemyriad.cassini.core.ProcessingPaused
import io.codemyriad.cassini.core.asr.IncrementalState
import io.codemyriad.cassini.core.checkpoint.CheckpointState
import io.codemyriad.cassini.core.checkpoint.RecognitionState
import io.codemyriad.cassini.core.checkpoint.WhistleCachedSegment
import io.codemyriad.cassini.core.checkpoint.WhistleResume
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.engine.job.ParakeetRecognizers
import io.codemyriad.cassini.engine.job.Recognizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class WhistleRecognizerTest {
    @get:Rule val temp = TemporaryFolder()
    private val pcm = FloatArray(4096) { it / 4096f }
    private val chunks = mutableListOf<Pair<Long, Transcript>>()
    private val progress = mutableListOf<List<Word>>()
    private val callbacks = Recognizer.Callbacks(
        { audio, start, transcript ->
            assertEquals(16000, audio.sampleRate)
            assertArrayEquals(pcm.copyOfRange(512, 1536), audio.samples, 0f)
            chunks += start to transcript
            transcript.copy(words = transcript.words.map { it.copy(speaker = "named_speaker") })
        },
        { _, _, _ -> },
        { progress += it.words },
    )

    private class Fake(private val result: (WhistleResult) -> Unit, private val eof: Boolean = false) : WhistleTransport {
        var received = 0
        var closes = 0
        var finishes = 0
        var sent = false
        var text = "Hello."
        var cleanupFailure = false
        private fun emit() {
            sent = true
            val json = """{"words":[{"word":" $text ","start":0.010,"end":0.050}]}""".toByteArray()
            result(WhistleResult(512, 1536, json))
        }
        override fun accept(samples: FloatArray, count: Int) {
            received += count
            if (!eof && !sent && received >= 2048) emit()
        }
        override fun finish() {
            finishes++
            if (!sent && received >= 2048) emit()
        }
        override fun close() {
            closes++
            if (cleanupFailure) error("cleanup failed")
        }
    }

    private fun fresh(eof: Boolean = false): WhistleRecognizer = WhistleRecognizer(
        null,
        callbacks,
        { _, _ -> error("Fresh run must not replay") },
        { Fake(it, eof) },
    )

    @Test
    fun placesWordsOnceAndPassesRawPcmToSpeakerCallbacks() {
        fresh().use {
            it.totalMs = 256
            it.accept(pcm, pcm.size)
            val transcript = it.finish(256)
            assertEquals(32L, chunks.single().first)
            assertEquals(Word("speaker_0", 10, 50, "Hello."), chunks.single().second.words.single())
            assertEquals(Word("named_speaker", 42, 82, "Hello."), transcript.words.single())
            assertEquals(Transcript.UNDETERMINED, transcript.language)
            assertEquals(transcript.words, progress.last())
            assertEquals(256L, it.doneMs)
            assertEquals(4096L, it.positionSamples)
        }
    }

    @Test
    fun codecRetainsTypedPipelineAndEofMarkerAndLeavesParakeetUntagged() {
        fresh(true).use {
            it.accept(pcm, pcm.size)
            it.finish(256)
            val state = it.checkpoint()
            val restored = CheckpointState.recognition(CheckpointState.json(state))
            assertEquals(state.whistle, restored.whistle)
            assertEquals(state.chunks.words, restored.chunks.words)
            assertEquals(4096L, restored.chunks.start)
            assertTrue(restored.chunks.window.isEmpty())
            val parakeet = RecognitionState(0, IncrementalState(0, 0, FloatArray(0), emptyList()))
            assertTrue(!CheckpointState.json(parakeet).has("whistle"))
        }
    }

    @Test fun stopBetweenReplayBlocksClosesTheWorkerWithoutChangingSavedProgress() {
        val saved = fresh().use {
            it.accept(pcm, pcm.size)
            it.checkpoint()
        }
        val before = CheckpointState.json(saved).toString()
        var stop = false
        lateinit var worker: Fake
        val callbacks = Recognizer.Callbacks(
            { _, _, _ -> error("Replay must not repeat speaker work") },
            { _, _, _ -> },
            {},
            checkReplayActive = { if (stop) throw ProcessingPaused() },
        )
        assertThrows(ProcessingPaused::class.java) {
            WhistleRecognizer(
                saved,
                callbacks,
                { _, sink ->
                    sink(pcm, 2048)
                    stop = true
                    sink(pcm.copyOfRange(2048, pcm.size), 2048)
                },
                { Fake(it).also { opened -> worker = opened } },
            )
        }
        assertEquals(2048, worker.received)
        assertEquals(1, worker.closes)
        assertEquals(before, CheckpointState.json(saved).toString())
    }

    @Test
    fun replaysSavedPrefixWithoutRepeatingSpeakerOrProgressCallbacks() {
        val state = fresh().use {
            it.accept(pcm, pcm.size)
            it.checkpoint()
        }
        chunks.clear()
        progress.clear()
        var replayed = 0L
        WhistleRecognizer(state, callbacks, { count, sink ->
            replayed = count
            sink(pcm, 2048)
            sink(pcm.copyOfRange(2048, 4096), 2048)
        }, { Fake(it) }).use {
            assertEquals(4096L, replayed)
            assertTrue(chunks.isEmpty())
            assertTrue(progress.isEmpty())
            assertEquals(state.chunks.words, it.finish(256).words)
        }
    }

    @Test
    fun newSegmentsAfterResumeKeepSavedSpeakersAndOnlyCallBackForNewWords() {
        val state = fresh().use {
            it.accept(pcm, pcm.size)
            it.checkpoint()
        }
        val starts = mutableListOf<Long>()
        val nextCallbacks = Recognizer.Callbacks(
            { audio, start, transcript ->
                starts += start
                assertArrayEquals(pcm.copyOfRange(512, 1536), audio.samples, 0f)
                transcript.copy(words = transcript.words.map { it.copy(speaker = "next_speaker") })
            },
            { _, _, _ -> },
            {},
        )
        WhistleRecognizer(
            state,
            nextCallbacks,
            { _, sink -> sink(pcm, pcm.size) },
            { result ->
                val first = Fake(result)
                object : WhistleTransport {
                    override fun accept(samples: FloatArray, count: Int) {
                        first.accept(samples, count)
                        if (first.received == 8192) {
                            val json = """{"words":[{"word":"Again.","start":0.010,"end":0.050}]}""".toByteArray()
                            result(WhistleResult(4608, 5632, json))
                        }
                    }
                    override fun finish() = first.finish()
                    override fun close() = first.close()
                }
            },
        ).use {
            it.accept(pcm, pcm.size)
            val transcript = it.finish(512)
            assertEquals(listOf(288L), starts)
            assertEquals(state.chunks.words.single(), transcript.words.first())
            assertEquals(Word("next_speaker", 298, 338, "Again."), transcript.words.last())
            assertEquals(2, transcript.words.size)
        }
    }

    @Test
    fun resumeAlsoReplaysTrueEofBeforeReturningSavedWords() {
        val state = fresh(true).use {
            it.accept(pcm, pcm.size)
            it.finish(256)
            it.checkpoint()
        }
        chunks.clear()
        lateinit var worker: Fake
        WhistleRecognizer(
            state,
            callbacks,
            { _, sink -> sink(pcm, pcm.size) },
            { Fake(it, true).also { worker = it } },
        ).use {
            assertTrue(chunks.isEmpty())
            assertEquals(state.chunks.words, it.finish(256).words)
            assertEquals(1, worker.finishes)
        }
        assertEquals(1, worker.closes)
    }

    @Test
    fun mismatchedReplayClosesWorkerAndPreservesTheOriginalFailure() {
        val state = fresh().use {
            it.accept(pcm, pcm.size)
            it.checkpoint()
        }
        lateinit var worker: Fake
        val error = assertThrows(IllegalArgumentException::class.java) {
            WhistleRecognizer(state, callbacks, { _, sink -> sink(pcm, pcm.size) }, {
                Fake(it).also { fake ->
                    worker = fake
                    fake.text = "Different."
                    fake.cleanupFailure = true
                }
            })
        }
        assertEquals("Whistle replayed words differ from saved progress", error.message)
        assertEquals("cleanup failed", error.suppressed.single().message)
        assertEquals(1, worker.closes)
    }

    @Test
    fun refusesOtherCheckpointsBeforeOpeningAnyNativeWorker() {
        val parakeet = RecognitionState(0, IncrementalState(0, 0, FloatArray(0), emptyList()))
        val wrongPin = parakeet.copy(whistle = WhistleResume("other-pipeline", false, "0".repeat(64)))
        for (state in listOf(parakeet, wrongPin)) {
            assertThrows(IllegalArgumentException::class.java) {
                WhistleRecognizer(state, callbacks, { _, _ -> error("must not replay") }, { error("must not load") })
            }
        }
        val whistle = parakeet.copy(whistle = WhistleResume(WhistleRecognizer.PIPELINE, false, "0".repeat(64)))
        assertThrows(IllegalArgumentException::class.java) {
            ParakeetRecognizers({ error("must not load") }, { error("must not load") }).open(whistle, callbacks)
        }
    }

    @Test
    fun changedPcmIsRefusedEvenWhenWordsAndTimesHappenToMatch() {
        val state = fresh().use {
            it.accept(pcm, pcm.size)
            it.checkpoint()
        }
        lateinit var worker: Fake
        val error = assertThrows(IllegalArgumentException::class.java) {
            WhistleRecognizer(
                state,
                callbacks,
                { _, sink -> sink(FloatArray(pcm.size), pcm.size) },
                { Fake(it).also { worker = it } },
            )
        }
        assertEquals("Whistle replay PCM differs from saved progress", error.message)
        assertEquals(1, worker.closes)
    }

    @Test
    fun incompleteOrOverlongReplayIsRefusedAndClosesTheWorker() {
        val state = fresh().use {
            it.accept(pcm, pcm.size)
            it.checkpoint()
        }
        for (count in listOf(2048, 4097)) {
            lateinit var worker: Fake
            assertThrows(IllegalArgumentException::class.java) {
                WhistleRecognizer(
                    state,
                    callbacks,
                    { _, sink -> sink(FloatArray(count), count) },
                    { Fake(it).also { worker = it } },
                )
            }
            assertEquals(1, worker.closes)
        }
    }

    @Test
    fun segmentCacheSkipsNeuralTranscribeOnPrefixReplayWhileVerifyingWordsAndPcm() {
        val cacheFile = temp.newFile("whistle-segments.json")
        // 22 s at 16 kHz: 0..5s voiced, 5..7s silence (closes segment 1), 7..12s voiced, 12..14s silence (closes segment 2),
        // 14..18s voiced, 18..22s silence (tail segment 3 after resume at 14s).
        val totalSamples = 22 * 16_000
        val track = FloatArray(totalSamples) { i ->
            val sec = i / 16_000
            val voiced = sec in 0..4 || sec in 7..11 || sec in 14..17
            if (voiced) (((i % 389) - 194) / 1024f) else 0f
        }
        val splitSample = 14 * 16_000

        fun streamTransport(
            onResult: (WhistleResult) -> Unit,
            transcribeCounter: AtomicInteger,
        ): WhistleTransport {
            val cache = HashMap<Pair<String, Int>, ByteArray>()
            var frameIdx = 0
            val stream = WhistleStream(
                rate = 16_000,
                probability = {
                    val sec = (frameIdx++ * 512) / 16_000
                    if (sec in 0..4 || sec in 7..11 || sec in 14..17) 0.95f else 0.01f
                },
                transcribe = { seg ->
                    val n = transcribeCounter.incrementAndGet()
                    val tag = "seg${seg.size}_$n"
                    """{"words":[{"word":"$tag","start":0.050,"end":0.250}]}""".toByteArray()
                },
                segment = { range, json, hash ->
                    onResult(WhistleResult(range.startSample, range.endSample, json, hash))
                },
                cachedSegment = { hash, count -> cache[hash to count] },
            )
            return object : WhistleTransport {
                override fun seedCache(entries: List<WhistleCachedSegment>) {
                    entries.forEach { cache[it.pcmSha256 to it.sampleCount] = it.json }
                }

                override fun accept(samples: FloatArray, count: Int) {
                    stream.accept(if (count == samples.size) samples else samples.copyOf(count))
                }

                override fun finish() {
                    stream.finish()
                }

                override fun close() = Unit
            }
        }

        val passthrough = Recognizer.Callbacks({ _, _, t -> t }, { _, _, _ -> }, {})
        val firstCalls = AtomicInteger()
        val saved = WhistleRecognizer(
            restored = null,
            callbacks = passthrough,
            replay = { _, _ -> error("Fresh run must not replay") },
            open = { onResult -> streamTransport(onResult, firstCalls) },
            segmentStore = FileWhistleSegmentStore(cacheFile),
        ).use { recognizer ->
            recognizer.accept(track, splitSample)
            recognizer.checkpoint()
        }
        assertEquals(2, firstCalls.get())
        assertEquals(2, saved.chunks.words.size)

        // Resume with FileWhistleSegmentStore: the 2 prefix segments MUST have 0 neural transcribe calls,
        // and only the 1 new tail segment may invoke transcribe!
        val resumeCalls = AtomicInteger()
        var prefixCallsAtReplayEnd = -1
        val resumedTranscript = WhistleRecognizer(
            restored = saved,
            callbacks = passthrough,
            replay = { limit, sink ->
                sink(track, limit.toInt())
                prefixCallsAtReplayEnd = resumeCalls.get()
            },
            open = { onResult -> streamTransport(onResult, resumeCalls) },
            segmentStore = FileWhistleSegmentStore(cacheFile),
        ).use { recognizer ->
            assertEquals("Zero neural Whistle calls on already-settled prefix segments", 0, resumeCalls.get())
            recognizer.accept(track.copyOfRange(splitSample, totalSamples), totalSamples - splitSample)
            recognizer.finish(totalSamples * 1000L / 16_000L)
        }
        assertEquals(0, prefixCallsAtReplayEnd)
        assertEquals("Only the 1 new tail segment after Stop should run neural ASR", 1, resumeCalls.get())
        assertEquals(3, resumedTranscript.words.size)
        assertEquals(saved.chunks.words, resumedTranscript.words.take(2))
    }
}
