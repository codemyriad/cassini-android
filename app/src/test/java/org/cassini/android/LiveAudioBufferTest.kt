package org.cassini.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class LiveAudioBufferTest {
    // 1 kHz keeps the arithmetic readable: 5 s chunks are 5000 samples, overlap 1000.
    private fun buffer(seconds: Int = 20) = LiveAudioBuffer(seconds, 1000)
    private fun LiveAudioBuffer.add(n: Int, value: Short = 1) = append(ShortArray(n) { value })

    private fun <T> inThread(block: () -> T): Pair<Thread, AtomicReference<Result<T>>> {
        val result = AtomicReference<Result<T>>()
        val thread = Thread { result.set(runCatching(block)) }.apply { start() }
        return thread to result
    }

    @Test fun chunksFollowTheFiveSecondRuleWithOverlap() {
        val b = buffer()
        b.add(5000)
        assertEquals(Span(0, 5000), b.awaitChunk(0))
        b.add(5000)
        assertEquals(Span(4000, 10000), b.awaitChunk(5000))
        b.add(4999)
        b.finish()
        assertEquals(Span(9000, 14999), b.awaitChunk(10000))
        assertNull(b.awaitChunk(14999))
    }

    @Test fun waitsUntilAChunkIsComplete() {
        val b = buffer()
        val (thread, result) = inThread { b.awaitChunk(0) }
        b.add(4999)
        thread.join(100)
        assertTrue(thread.isAlive)
        b.add(1)
        thread.join(2000)
        assertEquals(Span(0, 5000), result.get().getOrThrow())
    }

    @Test fun slowConsumerCatchesUpInChunkSizedSteps() {
        val b = buffer()
        b.add(18_000)
        var end = 0
        val spans = mutableListOf<Span>()
        b.add(0); b.finish()
        while (true) { val s = b.awaitChunk(end) ?: break; spans += s; end = s.end }
        assertEquals(listOf(Span(0, 5000), Span(4000, 10000), Span(9000, 15000), Span(14000, 18000)), spans)
        assertEquals(18_000, b.size)
        assertEquals(18_000, b.snapshot().samples.size)
    }

    @Test fun tinyFinalFlushKeepsItsOverlap() {
        val b = buffer()
        b.add(5001)
        assertEquals(Span(0, 5000), b.awaitChunk(0))
        b.finish()
        assertEquals(Span(4000, 5001), b.awaitChunk(5000))
        assertNull(b.awaitChunk(5001))
    }

    @Test fun shortRecordingIsOneSpanOnFinish() {
        val b = buffer()
        b.add(10)
        b.finish()
        assertEquals(Span(0, 10), b.awaitChunk(0))
        assertNull(b.awaitChunk(10))
    }

    @Test fun emptyRecordingDrainsToNull() {
        val b = buffer()
        b.finish()
        assertNull(b.awaitChunk(0))
    }

    @Test fun pausedAudioLeavesNoGap() {
        val b = buffer()
        b.add(3000, 1)
        // Capture stopped for a while: nothing is appended, so the next samples continue the clock.
        b.add(3000, 2)
        val audio = b.snapshot(2999, 3001)
        assertEquals(1f / 32768f, audio.samples[0], 0f)
        assertEquals(2f / 32768f, audio.samples[1], 0f)
        assertEquals(6000, b.size)
    }

    @Test fun finishWakesAWaitingConsumer() {
        val b = buffer()
        val (thread, result) = inThread { b.awaitChunk(0) }
        b.add(10)
        Thread.sleep(50)
        b.finish()
        thread.join(2000)
        assertEquals(Span(0, 10), result.get().getOrThrow())
        assertTrue(b.finished)
    }

    @Test fun cancelWakesAWaitingConsumerAndDiscardsLaterAudio() {
        val b = buffer()
        b.add(7000)
        val (thread, result) = inThread { b.awaitChunk(5000) }
        Thread.sleep(50)
        b.cancel()
        thread.join(2000)
        assertNull(result.get().getOrThrow())
        assertTrue(b.cancelled)
        assertNull(b.awaitChunk(0))
        assertEquals(0, b.add(5))
        assertEquals(7000, b.size)
    }

    @Test fun interruptedWaitThrows() {
        val b = buffer()
        val (thread, result) = inThread { b.awaitChunk(0) }
        Thread.sleep(50)
        thread.interrupt()
        thread.join(2000)
        assertTrue(result.get().exceptionOrNull() is InterruptedException)
    }

    @Test fun producerNeverWaitsForTheConsumer() {
        val b = buffer(100)
        val (thread, _) = inThread { b.awaitChunk(0) }
        val done = CountDownLatch(1)
        Thread { repeat(90) { b.add(1000) }; done.countDown() }.start()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        thread.join(2000)
        assertEquals(90_000, b.size)
    }

    @Test fun overflowAcceptsOnlyWhatFits() {
        val b = LiveAudioBuffer(1, 1000)
        assertEquals(600, b.add(600))
        assertEquals(400, b.add(1000))
        assertEquals(0, b.add(10))
        assertEquals(1000, b.size)
        assertEquals(1000, b.capacity)
    }

    @Test fun defaultsHoldOneHundredSeventyNineSecondsAt16k() {
        val b = LiveAudioBuffer()
        assertEquals(179 * 16000, b.capacity)
        assertEquals(16000, b.sampleRate)
    }

    @Test fun invalidArgumentsAreRejected() {
        val b = buffer()
        b.add(100)
        assertThrows(IllegalArgumentException::class.java) { b.append(ShortArray(2), 3) }
        assertThrows(IllegalArgumentException::class.java) { b.append(ShortArray(2), -1) }
        assertThrows(IllegalArgumentException::class.java) { b.snapshot(-1, 5) }
        assertThrows(IllegalArgumentException::class.java) { b.snapshot(50, 40) }
        assertThrows(IllegalArgumentException::class.java) { b.snapshot(0, 101) }
        assertThrows(IllegalArgumentException::class.java) { b.awaitChunk(-1) }
        assertThrows(IllegalArgumentException::class.java) { b.awaitChunk(101) }
        assertThrows(IllegalArgumentException::class.java) { LiveAudioBuffer(0) }
        assertThrows(IllegalArgumentException::class.java) { LiveAudioBuffer(1, 1000, 1000, 1000) }
        assertEquals(100, b.size)
    }

    @Test fun snapshotConvertsToFloatAndKeepsTheRate() {
        val b = buffer()
        b.append(shortArrayOf(-32768, 0, 16384, 32767))
        val audio = b.snapshot()
        assertEquals(1000, audio.sampleRate)
        assertArrayEquals(floatArrayOf(-1f, 0f, 0.5f, 32767 / 32768f), audio.samples, 0f)
        assertEquals(2, b.snapshot(1, 3).samples.size)
        assertEquals(0, b.snapshot(2, 2).samples.size)
    }
}
