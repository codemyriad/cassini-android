package io.codemyriad.cassini.core.asr.whistle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WhistleSpeechGateStateTest {
    @Test fun partialFrameVotesPrerollAndActiveSpeechSurviveAWorkerRestart() {
        val probabilities = List(12) { 0f } + List(60) { 0.9f } + List(21) { 0f } + List(480) { 0.9f } + List(20) { 0f }
        val input = FloatArray(probabilities.size * 512 + 123) { at -> probabilities[minOf(at / 512, probabilities.lastIndex)] }
        val expected = ArrayList<WhistleSpeechSegment>()
        var baselineCalls = 0
        WhistleSpeechGate({ baselineCalls++; it[0] }, expected::add).apply { accept(input); finish() }
        for (cut in listOf(17, 3 * 512 + 17, 20 * 512 + 199, 95 * 512 + 113, 560 * 512 + 300)) {
            val actual = ArrayList<WhistleSpeechSegment>()
            var calls = 0
            val before = WhistleSpeechGate({ calls++; it[0] }, actual::add)
            before.accept(input.copyOfRange(0, cut))
            val state = before.snapshot()
            val after = WhistleSpeechGate({ calls++; it[0] }, actual::add)
            after.restore(state)
            state.frame.fill(Float.NaN)
            state.votes.fill(false)
            after.accept(input.copyOfRange(cut, input.size))
            after.finish()
            assertEquals("cut=$cut", expected, actual)
            assertEquals("No repeated or invented VAD frame at cut=$cut", baselineCalls, calls)
        }
    }

    @Test fun corruptGateStateCannotPartiallyRestoreAnInstance() {
        val source = WhistleSpeechGate({ 0.8f }, {})
        source.accept(FloatArray(1024))
        val state = source.snapshot()
        val target = WhistleSpeechGate({ 0.8f }, {})
        assertThrows(IllegalArgumentException::class.java) { target.restore(state.copy(used = 512)) }
        assertThrows(IllegalArgumentException::class.java) { target.restore(state.copy(start = state.position + 1)) }
        target.restore(state)
    }
}
