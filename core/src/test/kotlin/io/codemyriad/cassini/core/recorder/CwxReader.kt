package io.codemyriad.cassini.core.recorder

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The checks and the word walk of the recorder's reader (`open_fw/src/cwx.c`: `cwx_open`, `cwx_seek_ms`,
 * `cwx_next_word`), so tests can prove that the device accepts what [CwxEncoder] writes.
 */
class CwxReader(private val bytes: ByteArray) {
    class Word(val startMs: Long, val endMs: Long, val speaker: Int, val flags: Int, val text: String, val offset: Int)

    class Speaker(val color: Int, val label: String)

    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val speakerCount: Int
    val durationMs: Long
    val wordCount: Long
    val speakersOffset: Long
    val seekOffset: Long
    val seekCount: Long
    val wordsOffset: Long
    val title: String
    val speakers: List<Speaker>

    init {
        val size = bytes.size.toLong()
        check(size >= 64) { "Shorter than the header" }
        check(String(bytes, 0, 4, Charsets.US_ASCII) == "CWX1") { "Bad magic" }
        check(u16(4) == 1) { "Bad version" }
        speakerCount = u16(6)
        durationMs = u32(8)
        wordCount = u32(12)
        speakersOffset = u32(16)
        seekOffset = u32(20)
        seekCount = u32(24)
        wordsOffset = u32(28)
        check(speakerCount <= 16) { "Too many speakers" }
        val speakerBytes = speakerCount * 24L
        check(speakersOffset in 64..size && speakerBytes <= size - speakersOffset) { "Bad speakers" }
        val seekValid = seekOffset in speakersOffset + speakerBytes..size && seekCount <= (size - seekOffset) / 8
        check(seekValid) { "Bad seek table" }
        check(wordsOffset >= seekOffset + seekCount * 8 && wordsOffset <= size) { "Bad words offset" }
        title = cString(32, 32)
        speakers = (0 until speakerCount).map {
            val at = (speakersOffset + it * 24).toInt()
            Speaker(u16(at), cString(at + 4, 20))
        }
    }

    /** `cwx_seek_ms` then `cwx_next_word` until the end: the words from the seek entry of [targetMs]. */
    fun wordsFrom(targetMs: Long): List<Word> {
        if (wordCount == 0L || seekCount == 0L) return walk(wordsOffset, 0)
        val second = minOf(targetMs / 1000, seekCount - 1)
        val entry = (seekOffset + second * 8).toInt()
        val relative = u32(entry)
        val index = u32(entry + 4)
        check(relative <= bytes.size - wordsOffset && index <= wordCount) { "Bad seek entry" }
        return walk(wordsOffset + relative, index)
    }

    private fun walk(from: Long, firstIndex: Long): List<Word> {
        val words = mutableListOf<Word>()
        var at = from
        var index = firstIndex
        while (index < wordCount && bytes.size - at >= 11) {
            val p = at.toInt()
            val length = bytes[p + 10].toInt() and 0xFF
            check(length <= bytes.size - (at + 11)) { "Word text past the end" }
            // The reader keeps at most 47 bytes of text.
            val kept = minOf(length, 47)
            words += Word(
                startMs = u32(p),
                endMs = u32(p + 4),
                speaker = bytes[p + 8].toInt() and 0xFF,
                flags = bytes[p + 9].toInt() and 0xFF,
                text = String(bytes, p + 11, kept, Charsets.UTF_8),
                offset = p - wordsOffset.toInt(),
            )
            at += 11 + length
            index++
        }
        return words
    }

    private fun cString(at: Int, size: Int): String {
        val raw = bytes.copyOfRange(at, at + size - 1)
        val end = raw.indexOf(0).let { if (it < 0) raw.size else it }
        return String(raw, 0, end, Charsets.UTF_8)
    }

    private fun u16(at: Int): Int = buffer.getShort(at).toInt() and 0xFFFF

    private fun u32(at: Int): Long = buffer.getInt(at).toLong() and 0xFFFF_FFFFL
}
