package org.cassini.android

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PcmCacheTest {
    private fun dir(): File = Files.createTempDirectory("pcm").toFile().also { it.deleteOnExit() }
    private val key = "opus-0123456789abcdef"

    @Test fun appendAndReadRoundTrip() {
        val directory = dir()
        val cache = PcmCache.open(directory, key)
        cache.openWriter(100_000)
        val samples = FloatArray(100_000) { (it % 977) / 977f - 0.5f }
        var at = 0
        while (at < samples.size) {
            val n = minOf(3001, samples.size - at)
            cache.append(samples.copyOfRange(at, at + n), n, at * 62L)
            at += n
        }
        // Readable before finish, unflushed tail included.
        val middle = FloatArray(500)
        cache.read(99_000, 500, middle)
        assertArrayEquals(samples.copyOfRange(99_000, 99_500), middle, 0f)
        cache.finish()
        assertTrue(cache.state.complete)
        assertEquals(100_000L, cache.state.decodedSamples)
        assertArrayEquals(samples, cache.readAll(), 0f)
        val again = PcmCache.open(directory, key)
        assertTrue(again.state.complete)
        assertArrayEquals(samples, again.readAll(), 0f)
    }

    @Test fun interruptedDecodeResumesFromLastCheckpoint() {
        val directory = dir()
        val cache = PcmCache.open(directory, key)
        cache.openWriter(400_000)
        val samples = FloatArray(400_000) { it.toFloat() }
        val chunk = 1000
        // A kill after 250 000 samples: only the 10 s checkpoint survives in the sidecar.
        for (at in 0 until 250_000 step chunk) cache.append(samples.copyOfRange(at, at + chunk), chunk, at.toLong())
        val resumed = PcmCache.open(directory, key)
        val checkpoint = resumed.state.decodedSamples
        assertEquals(160_000L, checkpoint)
        assertFalse(resumed.state.complete)
        assertEquals(checkpoint * 4, resumed.data.length())
        resumed.openWriter(400_000)
        for (at in checkpoint.toInt() until samples.size step chunk) resumed.append(samples.copyOfRange(at, at + chunk), chunk)
        resumed.finish()
        assertArrayEquals(samples, PcmCache.open(directory, key).readAll(), 0f)
    }

    @Test fun tornSidecarTrustsTheSmallerCount() {
        val directory = dir()
        val cache = PcmCache.open(directory, key)
        cache.openWriter(0)
        cache.append(FloatArray(200_000), 200_000)
        cache.checkpoint()
        // The data file lost its tail, the sidecar claims more.
        java.io.RandomAccessFile(cache.data, "rw").use { it.setLength(150_001L * 4 + 2) }
        assertEquals(150_001L, PcmCache.open(directory, key).state.decodedSamples)
        assertEquals(150_001L * 4, cache.data.length())
        // The sidecar is garbage: start over.
        cache.sidecar.writeText("{\"version\":1,\"sampleR")
        val fresh = PcmCache.open(directory, key)
        assertEquals(0L, fresh.state.decodedSamples)
        assertEquals(0L, fresh.data.length())
    }

    @Test fun readersWaitForTheWriter() {
        val cache = PcmCache.open(dir(), key)
        cache.openWriter(32_000)
        val reader = Thread {
            assertEquals(20_000L, cache.awaitAvailable(20_000).coerceAtMost(20_000))
            assertEquals(32_000L, cache.awaitAvailable(Long.MAX_VALUE))
        }
        reader.start()
        repeat(32) { cache.append(FloatArray(1000), 1000); Thread.sleep(1) }
        cache.finish()
        reader.join(5000)
        assertFalse(reader.isAlive)
    }

    @Test fun keysSelectFilesAndTrimKeepsRecent() {
        val directory = dir()
        for ((i, k) in listOf("opus-aaaaaaaa", "opus-bbbbbbbb", "file-cccccccc", "file-dddddddd").withIndex()) {
            val cache = PcmCache.open(directory, k)
            cache.openWriter(1); cache.append(floatArrayOf(i.toFloat()), 1); cache.finish()
            cache.data.setLastModified(1_000_000L * (i + 1))
        }
        assertEquals(1f, PcmCache.open(directory, "opus-bbbbbbbb").readAll()[0])
        PcmCache.trim(directory, 2, except = "opus-aaaaaaaa")
        val left = directory.listFiles()!!.filter { it.name.endsWith(".f32") }.map { it.nameWithoutExtension }.toSet()
        assertEquals(setOf("opus-aaaaaaaa", "file-cccccccc", "file-dddddddd"), left)
        assertFalse(File(directory, "opus-bbbbbbbb.json").exists())
        assertThrows(IllegalArgumentException::class.java) { PcmCache.open(directory, "../escape") }
    }
}
