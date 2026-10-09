package org.cassini.data.notes

import android.media.MediaExtractor
import android.media.MediaFormat
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.OggOpus
import org.cassini.core.format.OggRepair
import org.cassini.core.library.AudioKind
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Locale

/** The first audio track that [AudioProbe] found in a file: its MIME type and duration (0 = unknown). */
data class ProbedAudio(val mime: String, val durationMs: Long)

/**
 * Opens a compressed audio file the way the decoder does, with `MediaExtractor`, and returns its first audio track,
 * or null when the file has none or cannot be opened.
 *
 * It is an interface because Robolectric cannot run `MediaExtractor` on real files: the repository tests
 * (ImportRepositoryTest, RecoveryRepositoryTest, CatalogueRebuildTest) pass a fake that sniffs the container instead.
 * Ogg Opus and WAV never reach it; they are read in Kotlin ([AudioFiles]).
 */
fun interface AudioProbe {
    fun probe(file: File): ProbedAudio?

    companion object {
        /** The production probe. */
        val Extractor: AudioProbe = AudioProbe(::extract)

        private fun extract(file: File): ProbedAudio? {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(file.path)
                (0 until extractor.trackCount).asSequence()
                    .map(extractor::getTrackFormat)
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    ?.let { format ->
                        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                            format.getLong(MediaFormat.KEY_DURATION) / MICROS_PER_MS
                        } else {
                            0L
                        }
                        ProbedAudio(format.getString(MediaFormat.KEY_MIME).orEmpty(), duration.coerceAtLeast(0))
                    }
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            } finally {
                extractor.release()
            }
        }

        private const val MICROS_PER_MS = 1000L
    }
}

/** What a file's first bytes say it is. */
internal enum class AudioContainer { OGG_OPUS, WAV, OTHER }

/**
 * Reads what the storage layer needs from audio files without decoding them: the container, whether an Ogg Opus file
 * is a Cassini document, durations, the WAV header rules and the repair of an unfinished capture.
 */
internal object AudioFiles {
    private const val SNIFF_BYTES = 64
    private const val RIFF_HEADER = 12
    private const val CASSINI_FORMAT_TAG = "CASSINI_FORMAT"

    fun sniff(file: File): AudioContainer {
        val head = file.inputStream().use { readUpTo(it, SNIFF_BYTES) }
        return when {
            OggOpus.looksLikeOpus(head) -> AudioContainer.OGG_OPUS
            head.size >= RIFF_HEADER && ascii(head, 0) == "RIFF" && ascii(head, WAVE_OFFSET) == "WAVE" -> AudioContainer.WAV
            else -> AudioContainer.OTHER
        }
    }

    /**
     * Whether an Ogg Opus file carries Cassini metadata (a `CASSINI_FORMAT` comment), from its two header pages only;
     * null when the headers cannot be read. Such a file is a document (`CassiniDocument.readChecked` then decides
     * whether it is OK, unverified, invalid or of a newer format); any other Ogg Opus file is plain audio.
     */
    fun isCassini(file: File): Boolean? = try {
        val (_, tags) = file.inputStream().buffered().use(OggOpus::headers)
        CassiniDocument.readComments(tags).any {
            it.substringBefore('=', "").uppercase(Locale.ROOT) == CASSINI_FORMAT_TAG
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    /** The Opus tags' vendor string (`cassini-android/0.3` for documents this app wrote), or null. */
    fun vendor(file: File): String? = try {
        val (_, tags) = file.inputStream().buffered().use(OggOpus::headers)
        val length = OggOpus.u32(tags, OPUS_TAGS_MAGIC)
        val fits = length >= 0 && VENDOR_START + length <= tags.size
        if (fits) String(tags, VENDOR_START, length, Charsets.UTF_8) else null
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** The duration of an Ogg Opus stream from one pass over its pages; 0 when unknown, null when it is not Ogg Opus. */
    fun opusDurationMs(file: File): Long? = try {
        OggOpus.scan(file).durationMs.coerceAtLeast(0)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    /**
     * The duration of a WAV file that the decoder accepts (`WavDecoder`: 16-bit integer PCM, 8-96 kHz, one or two
     * channels, a whole number of frames), or null for any other WAV, such as 24-bit or float samples.
     */
    fun wavDurationMs(file: File): Long? = try {
        RandomAccessFile(file, "r").use(WavHeader::read)?.durationMs
    } catch (_: IOException) {
        null
    }

    /**
     * Finishes a capture that was not closed (a crash or kill during recording): a `capture.opus` keeps every whole
     * page and gets an end-of-stream page (`OggRepair`, which leaves a cleanly ended file alone); another capture
     * format is plain if [probe] opens it. Returns the note's audio kind and duration; UNPLAYABLE when nothing
     * playable is left.
     */
    fun finishCapture(file: File, probe: AudioProbe): Pair<AudioKind, Long> = when {
        !file.isFile -> AudioKind.UNPLAYABLE to 0L
        file.name.endsWith(".opus") -> repairOpus(file)?.let { AudioKind.PLAIN to it } ?: (AudioKind.UNPLAYABLE to 0L)
        else -> probe.probe(file)?.let { AudioKind.PLAIN to it.durationMs } ?: (AudioKind.UNPLAYABLE to 0L)
    }

    /**
     * The kind and duration of an imported `source.*` file, which is never changed: Ogg Opus and WAV are read in
     * Kotlin, the rest by [probe].
     */
    fun inspectSource(file: File, probe: AudioProbe): Pair<AudioKind, Long> {
        val duration = when (if (file.isFile) sniff(file) else null) {
            null -> null
            AudioContainer.OGG_OPUS -> opusDurationMs(file)
            AudioContainer.WAV -> wavDurationMs(file)
            AudioContainer.OTHER -> probe.probe(file)?.durationMs
        }
        return if (duration == null) AudioKind.UNPLAYABLE to 0L else AudioKind.PLAIN to duration
    }

    private fun repairOpus(file: File): Long? = try {
        OggRepair.repair(file)?.durationMs
    } catch (_: IOException) {
        null
    }

    private fun readUpTo(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var got = 0
        while (got < count) {
            val n = input.read(buffer, got, count - got)
            if (n < 0) break
            got += n
        }
        return buffer.copyOf(got)
    }

    private fun ascii(bytes: ByteArray, offset: Int) = String(bytes, offset, FOURCC, Charsets.US_ASCII)

    private const val FOURCC = 4
    private const val WAVE_OFFSET = 8

    /** "OpusTags" is 8 bytes; the 32-bit vendor length follows, then the vendor string. */
    private const val OPUS_TAGS_MAGIC = 8
    private const val VENDOR_START = OPUS_TAGS_MAGIC + 4
}

/** The `fmt ` and `data` chunks of a RIFF/WAVE file, with the rules `WavDecoder` applies before it decodes. */
internal object WavHeader {
    class Format(val sampleRate: Int, val channels: Int, val frames: Long) {
        val durationMs: Long get() = frames * MS_PER_SECOND / sampleRate
    }

    private const val PCM = 1
    private const val BITS = 16
    private const val BYTES_PER_SAMPLE = 2
    private const val MIN_RATE = 8000
    private const val MAX_RATE = 96000
    private const val MAX_CHANNELS = 2
    private const val MIN_FMT_SIZE = 16
    private const val FIRST_CHUNK = 12L
    private const val CHUNK_HEADER = 8
    private const val MS_PER_SECOND = 1000L
    private const val U32_MASK = 0xffffffffL
    private const val U16_MASK = 0xffff
    private const val FOURCC = 4

    /** The byte rate (4 bytes) and block align (2 bytes) between the sample rate and the sample size. */
    private const val RATE_AND_ALIGN_BYTES = 6

    /** The format of [file] when the decoder accepts it, else null. */
    fun read(file: RandomAccessFile): Format? {
        val chunks = try {
            Chunks().also { it.scan(file) }
        } catch (_: EOFException) {
            null
        }
        return chunks?.format()
    }

    /** What the chunk walk found. */
    private class Chunks {
        var pcm16 = false
        var rate = 0
        var channels = 0
        var dataSize = -1L
        var broken = false

        fun scan(file: RandomAccessFile) {
            file.seek(FIRST_CHUNK)
            while (!broken && file.filePointer + CHUNK_HEADER <= file.length()) {
                val id = ByteArray(FOURCC).also(file::readFully).toString(Charsets.US_ASCII)
                val size = Integer.reverseBytes(file.readInt()).toLong() and U32_MASK
                val offset = file.filePointer
                when {
                    offset + size > file.length() -> broken = true
                    id == "fmt " -> readFormat(file, size)
                    id == "data" -> dataSize = size
                }
                file.seek(offset + size + (size and 1))
            }
        }

        private fun readFormat(file: RandomAccessFile, size: Long) {
            if (size < MIN_FMT_SIZE) {
                broken = true
                return
            }
            val format = u16(file)
            channels = u16(file)
            rate = Integer.reverseBytes(file.readInt())
            file.skipBytes(RATE_AND_ALIGN_BYTES)
            pcm16 = format == PCM && u16(file) == BITS
        }

        fun format(): Format? {
            val frameBytes = BYTES_PER_SAMPLE * channels
            val valid = !broken && pcm16 && rate in MIN_RATE..MAX_RATE && channels in 1..MAX_CHANNELS &&
                dataSize >= frameBytes && dataSize % frameBytes == 0L
            return if (valid) Format(rate, channels, dataSize / frameBytes) else null
        }
    }

    private fun u16(file: RandomAccessFile): Int = java.lang.Short.reverseBytes(file.readShort()).toInt() and U16_MASK
}
