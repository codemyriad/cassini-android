package io.codemyriad.cassini.engine.whistle

import io.codemyriad.cassini.core.asr.IncrementalState
import io.codemyriad.cassini.core.checkpoint.CheckpointState
import io.codemyriad.cassini.core.checkpoint.RecognitionState
import io.codemyriad.cassini.core.checkpoint.WhistleResume
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.engine.job.ParakeetRecognizers
import io.codemyriad.cassini.engine.job.Recognizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WhistleRecognizerTest {
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
            result(WhistleResult(512, 1536, """{"words":[{"word":" $text ","start":0.010,"end":0.050}]}""".toByteArray()))
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
        null, callbacks, { _, _ -> error("Fresh run must not replay") }, { Fake(it, eof) },
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

    @Test
    fun replaysSavedPrefixWithoutRepeatingSpeakerOrProgressCallbacks() {
        val state = fresh().use { it.accept(pcm, pcm.size); it.checkpoint() }
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
    fun resumeAlsoReplaysTrueEofBeforeReturningSavedWords() {
        val state = fresh(true).use { it.accept(pcm, pcm.size); it.finish(256); it.checkpoint() }
        chunks.clear()
        lateinit var worker: Fake
        WhistleRecognizer(state, callbacks, { _, sink -> sink(pcm, pcm.size) }, { Fake(it, true).also { worker = it } }).use {
            assertTrue(chunks.isEmpty())
            assertEquals(state.chunks.words, it.finish(256).words)
            assertEquals(1, worker.finishes)
        }
        assertEquals(1, worker.closes)
    }

    @Test
    fun mismatchedReplayClosesWorkerAndPreservesTheOriginalFailure() {
        val state = fresh().use { it.accept(pcm, pcm.size); it.checkpoint() }
        lateinit var worker: Fake
        val error = assertThrows(IllegalArgumentException::class.java) {
            WhistleRecognizer(state, callbacks, { _, sink -> sink(pcm, pcm.size) }, {
                Fake(it).also { fake -> worker = fake; fake.text = "Different."; fake.cleanupFailure = true }
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
        val state = fresh().use { it.accept(pcm, pcm.size); it.checkpoint() }
        lateinit var worker: Fake
        val error = assertThrows(IllegalArgumentException::class.java) {
            WhistleRecognizer(state, callbacks, { _, sink -> sink(FloatArray(pcm.size), pcm.size) }, { Fake(it).also { worker = it } })
        }
        assertEquals("Whistle replay PCM differs from saved progress", error.message)
        assertEquals(1, worker.closes)
    }

    @Test
    fun incompleteOrOverlongReplayIsRefusedAndClosesTheWorker() {
        val state = fresh().use { it.accept(pcm, pcm.size); it.checkpoint() }
        for (count in listOf(2048, 4097)) {
            lateinit var worker: Fake
            assertThrows(IllegalArgumentException::class.java) {
                WhistleRecognizer(state, callbacks, { _, sink -> sink(FloatArray(count), count) }, { Fake(it).also { worker = it } })
            }
            assertEquals(1, worker.closes)
        }
    }
}
