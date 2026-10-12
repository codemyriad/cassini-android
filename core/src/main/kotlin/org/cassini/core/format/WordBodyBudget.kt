package org.cassini.core.format

/**
 * The size of the word body that the seal writes (`Transcript.json`, pretty-printed JSON), counted without writing
 * it. The format code refuses a body over [CassiniPayload.MAX_RAW] (16 MiB), so this is the real length limit of one
 * transcript: about 110,000 words with the job's speaker ids, or 11 to 12 hours of continuous speech
 * (docs/architecture.md section 3.7). The job counts the words after each window and stops with `Failure.LONG` as
 * soon as they pass [MAX_BYTES], not after hours of work at the seal.
 *
 * [bytes] is an upper bound of the length of Android's `JSONObject.toString(2)`: it counts each `/` as escaped and
 * each control character as a `\uXXXX` escape.
 */
object WordBodyBudget {
    /** The body bytes that a transcript may have: the format limit less a margin of 256 KiB. */
    const val MAX_BYTES = CassiniPayload.MAX_RAW - (256L shl 10)

    /** The bytes of one word item, without its four values: indentation, member names, quotes and separators. */
    private const val ITEM_BYTES = 87

    /** The body without items: the braces, `format`, `wordCount`, `items` and some room for the digits. */
    private const val HEADER_BYTES = 128

    /** The length of `Transcript(words, language).json()`, as an upper bound. */
    fun bytes(words: List<Word>, language: String = ""): Long {
        var total = HEADER_BYTES.toLong() + if (language.isEmpty()) 0 else LANGUAGE_BYTES + quoted(language)
        for (word in words) {
            total += ITEM_BYTES + quoted(word.speaker) + digits(word.startMs) + digits(word.endMs) + quoted(word.text)
        }
        return total
    }

    /** Whether the body of [words] fits in [MAX_BYTES]. */
    fun fits(words: List<Word>): Boolean = bytes(words) <= MAX_BYTES

    private const val LANGUAGE_BYTES = 18

    private fun digits(value: Long): Int = value.toString().length

    /** The UTF-8 bytes of [text] inside a JSON string, escapes included. */
    private fun quoted(text: String): Int {
        var bytes = 0
        for (c in text) {
            bytes += when {
                c == '"' || c == '\\' || c == '/' -> 2
                c < ' ' || c.code == LINE_SEPARATOR || c.code == PARAGRAPH_SEPARATOR -> UNICODE_ESCAPE
                c.code < ONE_BYTE_LIMIT -> 1
                c.code < TWO_BYTE_LIMIT -> 2
                c.isSurrogate() -> 2
                else -> THREE_BYTES
            }
        }
        return bytes
    }

    private const val ONE_BYTE_LIMIT = 0x80
    private const val TWO_BYTE_LIMIT = 0x800
    private const val LINE_SEPARATOR = 0x2028
    private const val PARAGRAPH_SEPARATOR = 0x2029
    private const val THREE_BYTES = 3
    private const val UNICODE_ESCAPE = 6
}
