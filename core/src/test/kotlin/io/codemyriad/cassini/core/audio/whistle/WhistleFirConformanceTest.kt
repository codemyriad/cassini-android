package io.codemyriad.cassini.core.audio.whistle

import io.codemyriad.cassini.core.asr.SileroInputFilter
import io.codemyriad.cassini.core.audio.whistle.WhistleResamplerTest.Companion.bytes
import io.codemyriad.cassini.core.audio.whistle.WhistleResamplerTest.Companion.resample
import io.codemyriad.cassini.core.audio.whistle.WhistleResamplerTest.Companion.sha
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Run only with :core:whistleFirConformance. Missing fixtures are failures, never skips. No ASR or VAD inference. */
class WhistleFirConformanceTest {
    @Test
    fun publishedCleanAndDcBytesMatchDesktop() {
        val directory = System.getProperty("cassini.whistleVectors", "")
        require(directory.isNotBlank()) { "Set -PcassiniWhistleVectorsDir to coordination/ux-draft" }
        val root = File(directory)
        val clean = pcm(
            File(root, "whistle/pipeline-input-48k.wav"),
            "b7062e41f452236812aa3417a68eb50372a99851ab4a18d1c4f4f99e2e2e3963",
        )
        val cleanHash = "1fb2707f7556fd1d2431b29cfe15e2118cea334fe55278be8c47298833ff5263"
        for (chunk in listOf(511, 8192)) assertEquals(cleanHash, sha(bytes(resample(clean, chunk))))
        println("clean FIR SHA-256=$cleanHash")

        val pinsData = requireNotNull(javaClass.getResourceAsStream("/whistle/dc-segment-pins.json")).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        val pins = JSONArray(pinsData)
        val files = listOf(
            Triple(
                "dc-quiet-jfk-v1",
                "071f051241d394587c85463272e9414f133ee18c4bb835d1382b662e4154efda",
                "eeaeee543bc328acf6f0c4557e8aba86652e0144d9763264e1e5d62a3d13f968",
            ),
            Triple(
                "dc-quiet-jfk-v1-control",
                "e31ed958621a39baf1d33dab6d10798695b8319e78fcaa1cb672cdb43c5f7830",
                "6ece4c5cacfc2456cb79d0aa1b16f4aa3912fef6697912e633886d7b0da545ab",
            ),
        )
        for ((name, sourceHash, filteredHash) in files) {
            val input = pcm(File(root, "dc/$name.wav"), sourceHash)
            val raw = resample(input, 8192)
            assertArrayEquals(bytes(raw), bytes(resample(input, 511)))
            val filter = SileroInputFilter()
            val filtered = filter.process(raw.copyOf(raw.size / 512 * 512))
            assertEquals("$name complete VAD frames", 604672, filtered.size)
            assertEquals("$name high-pass input", filteredHash, sha(bytes(filtered)))
            val streaming = SileroInputFilter()
            val frames = FloatArray(filtered.size)
            for (at in frames.indices step 512) {
                streaming.process(raw.copyOfRange(at, at + 512)).copyInto(frames, at)
            }
            assertArrayEquals("$name VAD frame state", bytes(filtered), bytes(frames))
            var matched = 0
            for (i in 0 until pins.length()) {
                val pin = pins.getJSONObject(i)
                if (pin.getString("sourceWavSha256") != sourceHash) continue
                val segment = raw.copyOfRange(pin.getInt("startSample"), pin.getInt("endSample"))
                assertEquals(pin.getString("file"), pin.getString("inputSha256"), sha(bytes(segment)))
                matched++
            }
            assertEquals(6, matched)
            println("$name: raw segment hashes=6/6; filtered complete-frame SHA-256=$filteredHash")
        }
    }

    private fun pcm(file: File, expected: String): FloatArray {
        val data = file.readBytes()
        assertEquals("Frozen input $file", expected, sha(data))
        val input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, input.getShort(20).toInt())
        assertEquals(1, input.getShort(22).toInt())
        assertEquals(48000, input.getInt(24))
        assertEquals(16, input.getShort(34).toInt())
        input.position(44)
        return FloatArray(input.remaining() / 2) { input.short / 32768f }
    }
}
