package org.cassini.data.notes

import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/** The FLEURS clip in fixtures/audio in [extension] (wav, m4a, mp3, opus). */
fun fleurs(extension: String): File =
    File(checkNotNull(System.getProperty("cassini.fixtures")), "audio/fleurs-it-short.$extension")

/** The FLEURS WAV: 253 440 frames at 16 kHz. */
const val FLEURS_WAV_MS = 15_840L

/**
 * Stands in for `MediaExtractor`, which Robolectric cannot run on real files. Like the extractor it needs a whole
 * container: an MP4 opens only when its `moov` box is present (a capture cut short has none), an MP3 when it starts
 * with an ID3 tag or a frame sync. Everything else has no audio track.
 */
class FakeProbe(private val durationMs: Long = FLEURS_WAV_MS) : AudioProbe {
    val calls = AtomicInteger()

    /** When set, every probe throws it: an I/O error while the file is read. */
    @Volatile var error: IOException? = null

    override fun probe(file: File): ProbedAudio? {
        calls.incrementAndGet()
        error?.let { throw it }
        val bytes = file.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        return when {
            bytes.size > 8 && text.substring(4, 8) == "ftyp" ->
                if ("moov" in text) ProbedAudio("audio/mp4a-latm", durationMs) else null
            text.startsWith("ID3") || (bytes.size > 1 && bytes[0] == 0xFF.toByte() && bytes[1].toInt() and 0xE0 == 0xE0) ->
                ProbedAudio("audio/mpeg", durationMs)
            else -> null
        }
    }
}

/** An import source that reports [size] (null = unknown) instead of the real one. */
class FakeSizeSource(private val base: ImportSource, private val size: Long?) : ImportSource {
    override fun describe(uri: Uri): ImportSource.Description = base.describe(uri).copy(size = size)

    override fun open(uri: Uri): InputStream? = base.open(uri)
}

/** A RIFF/WAVE file of [frames] silent frames with the given format code (1 = PCM, 3 = IEEE float) and sample size. */
@Suppress("LongParameterList") // Every WAV header field a test may vary.
fun writeWav(file: File, format: Int, bits: Int, rate: Int = 16_000, channels: Int = 1, frames: Int = 1_600) {
    val blockAlign = channels * bits / 8
    val dataBytes = frames * blockAlign
    val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
    buffer.put("fmt ".toByteArray()).putInt(16).putShort(format.toShort()).putShort(channels.toShort())
    buffer.putInt(rate).putInt(rate * blockAlign).putShort(blockAlign.toShort()).putShort(bits.toShort())
    buffer.put("data".toByteArray()).putInt(dataBytes)
    file.writeBytes(buffer.array())
}

/** The first [fraction] of [source]'s bytes, as a crash in the middle of a write leaves them. */
fun truncatedCopy(source: File, target: File, fraction: Double) {
    val bytes = source.readBytes()
    target.parentFile?.mkdirs()
    target.writeBytes(bytes.copyOf((bytes.size * fraction).toInt()))
}

fun sha256(file: File): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
