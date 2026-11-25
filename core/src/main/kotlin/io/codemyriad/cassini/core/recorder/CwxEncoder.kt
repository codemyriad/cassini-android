@file:Suppress("MagicNumber") // The CWX1 layout, the RGB565 packing and the recorder's default speaker palette.

package io.codemyriad.cassini.core.recorder

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One speaker of a [CwxTranscript]. [rgb] is 0xRRGGBB, or null for the recorder's palette color of its index. */
data class CwxSpeaker(val label: String, val rgb: Int? = null)

/**
 * One word of a [CwxTranscript]. [speakerIndex] points into [CwxTranscript.speakers]; any other value (or a speaker
 * past the 15th) shows as "Unknown".
 */
data class CwxWord(
    val startMs: Long,
    val endMs: Long,
    val speakerIndex: Int,
    val text: String,
    val turnStart: Boolean = false,
    val lowConfidence: Boolean = false,
)

/** The transcript the recorder shows during playback. [words] stay in speaker-turn order; they are never sorted. */
data class CwxTranscript(
    val title: String,
    val durationMs: Long,
    val speakers: List<CwxSpeaker>,
    val words: List<CwxWord>,
)

/**
 * Writes the CWX1 sidecar that the recorder's player reads (`open_fw/src/cwx.c`). This is a faithful port of
 * `build_cwx_bytes` in the recorder's `tools/cassini_to_device.py`; `CwxEncoderTest` checks it byte for byte against
 * that encoder. All numbers are little-endian:
 *
 * - Header, 64 bytes: "CWX1", u16 version 1, u16 speaker count (<= 16), u32 duration ms, u32 word count, u32 speakers
 *   offset, u32 seek offset, u32 seek count, u32 words offset, char[32] title (<= 31 bytes, NUL padded).
 * - Speakers, 24 bytes each: u16 RGB565 color, u8 flags 0, u8 reserved 0, char[20] label (<= 19 bytes).
 * - Seek table, duration / 1000 + 1 entries of 8 bytes: u32 byte offset of the first word that ends at or after
 *   k * 1000 ms (relative to the words), u32 its index.
 * - Words: u32 start ms, u32 end ms, u8 speaker, u8 flags (1 turn start, 2 low confidence), u8 length, UTF-8 text
 *   (<= 47 bytes). Words whose text is empty after cleaning are left out.
 */
object CwxEncoder {
    const val MAX_SPEAKERS = 16
    const val MAX_TITLE_BYTES = 31
    const val MAX_LABEL_BYTES = 19
    const val MAX_WORD_BYTES = 47
    const val FLAG_TURN_START = 0x01
    const val FLAG_LOW_CONFIDENCE = 0x02
    private const val HEADER_BYTES = 64
    private const val SPEAKER_BYTES = 24
    private const val SEEK_BYTES = 8
    private const val WORD_HEADER_BYTES = 11
    private const val TITLE_FIELD = 32
    private const val LABEL_FIELD = 20
    private const val VERSION = 1
    private const val FALLBACK_TITLE = "Meeting"
    private const val UNKNOWN_LABEL = "Unknown"
    private const val UNKNOWN_RGB = 0xD2D2D2
    private const val MAX_U32 = 0xFFFF_FFFFL
    private val MAGIC = "CWX1".toByteArray(Charsets.US_ASCII)

    /** The recorder's default speaker colors (`DEFAULT_SPEAKER_PALETTE_RGB`). */
    val palette: List<Int> = listOf(
        0x50E1FF, 0xFFC346, 0x73F091, 0xFF82AA, 0xB9A0FF, 0x82BEFF, 0xFFA56E, 0xC3F05F,
        0xFF7373, 0x6EEBD2, 0xF5DC6E, 0xDC96FF, 0x9BDCFF, 0xFFB9B9, 0xAFF5AF, 0xD2D2D2,
    )

    private val typographic = mapOf(
        '\u2018' to "'", '\u2019' to "'", '\u201A' to "'",
        '\u201C' to "\"", '\u201D' to "\"", '\u201E' to "\"",
        '\u2013' to "-", '\u2014' to "-", '\u2212' to "-",
        '\u2026' to "...", '\u00A0' to " ",
    )

    private val controlRuns = Regex("[\\x00-\\x1F\\x7F]+")

    /** The speaker table, in the reference encoder's order: the declared speakers, then "Unknown" when needed. */
    private class Speakers(declared: List<CwxSpeaker>) {
        val colors = mutableListOf<Int>()
        val labels = mutableListOf<ByteArray>()
        private val declaredCount = declared.size
        private var unknown = -1

        init {
            declared.forEach { add(it.label, it.rgb) }
        }

        fun indexOf(word: CwxWord): Int {
            if (word.speakerIndex in 0 until declaredCount) return word.speakerIndex
            if (unknown < 0) unknown = add(UNKNOWN_LABEL, UNKNOWN_RGB)
            return unknown
        }

        fun add(label: String, rgb: Int?): Int {
            if (colors.size >= MAX_SPEAKERS) return MAX_SPEAKERS - 1
            val index = colors.size
            colors += rgb565(rgb ?: palette[index % palette.size])
            labels += sanitize(label.ifEmpty { "Speaker ${index + 1}" }, MAX_LABEL_BYTES)
            return index
        }
    }

    /** The word records, in the transcript's order, with each one's offset and end for the seek table. */
    private class Words(private val speakers: Speakers) {
        val bytes = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        val ends = mutableListOf<Long>()
        var maxEnd = 0L
            private set
        private var previous = -1

        fun add(word: CwxWord) {
            val text = sanitize(word.text, MAX_WORD_BYTES)
            if (text.isEmpty()) return
            val startMs = word.startMs.coerceAtLeast(0)
            val endMs = word.endMs.coerceAtLeast(startMs)
            require(endMs <= MAX_U32) { "Word time out of range" }
            maxEnd = maxOf(maxEnd, endMs)
            val speaker = speakers.indexOf(word)
            var flags = 0
            if (previous < 0 || speaker != previous || word.turnStart) flags = flags or FLAG_TURN_START
            if (word.lowConfidence) flags = flags or FLAG_LOW_CONFIDENCE
            previous = speaker
            offsets += bytes.size()
            ends += endMs
            val header = little(WORD_HEADER_BYTES).putInt(startMs.toInt()).putInt(endMs.toInt())
                .put(speaker.toByte()).put(flags.toByte()).put(text.size.toByte())
            bytes.write(header.array())
            bytes.write(text)
        }
    }

    /** The CWX1 bytes of [transcript]. */
    fun encode(transcript: CwxTranscript): ByteArray {
        require(transcript.durationMs in 0..MAX_U32) { "Duration out of range" }
        val title = sanitize(transcript.title.ifEmpty { FALLBACK_TITLE }, MAX_TITLE_BYTES)
        // At most 15 declared speakers, so that "Unknown" always fits.
        val speakers = Speakers(transcript.speakers.take(MAX_SPEAKERS - 1))
        val words = Words(speakers)
        transcript.words.forEach(words::add)
        if (speakers.colors.isEmpty()) speakers.add("Speaker 1", null)

        val durationMs = maxOf(if (transcript.durationMs != 0L) transcript.durationMs else words.maxEnd, words.maxEnd)
        val seekCount = maxOf(1L, durationMs / 1000 + 1).toInt()
        val speakersOffset = HEADER_BYTES
        val seekOffset = speakersOffset + speakers.colors.size * SPEAKER_BYTES
        val wordsOffset = seekOffset + seekCount * SEEK_BYTES
        val wordBytes = words.bytes.toByteArray()

        val out = little(wordsOffset + wordBytes.size)
        out.put(MAGIC).putShort(VERSION.toShort()).putShort(speakers.colors.size.toShort())
            .putInt(durationMs.toInt()).putInt(words.offsets.size)
            .putInt(speakersOffset).putInt(seekOffset).putInt(seekCount).putInt(wordsOffset)
            .put(title.copyOf(TITLE_FIELD))
        speakers.colors.indices.forEach { i ->
            out.putShort(speakers.colors[i].toShort()).put(0).put(0).put(speakers.labels[i].copyOf(LABEL_FIELD))
        }
        // Entry k: the first word that ends at or after k seconds (the end of the words when none does).
        var scan = 0
        for (second in 0 until seekCount) {
            while (scan < words.offsets.size && words.ends[scan] < second * 1000L) scan++
            out.putInt(words.offsets.getOrElse(scan) { wordBytes.size }).putInt(scan)
        }
        out.put(wordBytes)
        return out.array()
    }

    /** RGB565 of the 0xRRGGBB color [rgb]. */
    fun rgb565(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return ((r and 0xF8) shl 8) or ((g and 0xFC) shl 3) or ((b and 0xF8) shr 3)
    }

    /**
     * [text] for the recorder's display, as UTF-8 of at most [maxBytes] bytes: typographic quotes, dashes, the ellipsis
     * and the no-break space become ASCII, each run of control characters becomes one space, the ends lose their white
     * space (Python's `str.strip`), and a cut never splits a character.
     */
    fun sanitize(text: String, maxBytes: Int): ByteArray {
        val mapped = buildString(text.length) {
            for (c in text) append(typographic[c] ?: c)
        }
        val spaced = controlRuns.replace(mapped, " ").trim(::isPythonSpace)
        val raw = spaced.toByteArray(Charsets.UTF_8)
        if (raw.size <= maxBytes) return raw
        var cut = maxBytes
        while (cut > 0 && (raw[cut].toInt() and 0xC0) == 0x80) cut--
        return raw.copyOf(cut)
    }

    /** Python's `str.isspace` for the characters of the Basic Multilingual Plane. */
    private fun isPythonSpace(c: Char): Boolean = when (c) {
        in '\u0009'..'\u000D', in '\u001C'..'\u0020', '\u0085', '\u00A0', '\u1680', in '\u2000'..'\u200A',
        '\u2028', '\u2029', '\u202F', '\u205F', '\u3000',
        -> true
        else -> false
    }

    private fun little(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
