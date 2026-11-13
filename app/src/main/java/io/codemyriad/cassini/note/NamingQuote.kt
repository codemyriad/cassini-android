package io.codemyriad.cassini.note

/** A bounded excerpt of the speaker's first contiguous speech, without synthetic overlap labels. */
internal fun NoteTranscript.namingQuote(speakerId: String): String {
    val words = turns.asSequence().flatMap { turn ->
        turn.words.asSequence().map { it.speakerId.ifEmpty { turn.speakerId } to it.text }
    }.dropWhile { it.first != speakerId }.takeWhile { it.first == speakerId }.iterator()
    val text = StringBuilder()
    var count = 0
    var truncated = false
    while (words.hasNext() && count < QUOTE_WORDS && !truncated) {
        val word = words.next().second
        val separator = if (text.isEmpty()) 0 else 1
        val room = QUOTE_CHARS - text.length - separator
        if (room <= 0) {
            truncated = true
        }
        if (room > 0) {
            if (separator != 0) text.append(' ')
            var end = minOf(room, word.length)
            if (end < word.length && end > 0 && Character.isHighSurrogate(word[end - 1])) end--
            text.append(word, 0, end)
            count++
            if (end < word.length) {
                truncated = true
            }
        }
    }
    if (truncated || words.hasNext()) text.append('…')
    return text.toString()
}

private const val QUOTE_WORDS = 40
private const val QUOTE_CHARS = 240
