package io.codemyriad.cassini.note

/** A bounded excerpt of the speaker's first contiguous speech, without synthetic overlap labels. */
internal fun NoteTranscript.namingQuote(speakerId: String): String {
    val words = firstSpeechWords(speakerId)
    val text = StringBuilder()
    var count = 0
    var truncated = false
    while (words.hasNext() && count < QUOTE_WORDS && !truncated) {
        val word = words.next()
        val separator = if (text.isEmpty()) 0 else 1
        val room = (QUOTE_CHARS - text.length - separator).coerceAtLeast(0)
        var end = minOf(room, word.length)
        if (end < word.length && end > 0 && Character.isHighSurrogate(word[end - 1])) end--
        if (end == 0) {
            truncated = true
        } else {
            if (separator != 0) text.append(' ')
            text.append(word, 0, end)
            count++
            if (end < word.length) truncated = true
        }
    }
    if (truncated || words.hasNext()) text.append('…')
    return text.toString()
}

private fun NoteTranscript.firstSpeechWords(speakerId: String): Iterator<String> = sequence {
    var inRun = false
    for (turn in turns) {
        for (word in turn.words) {
            val matches = word.speakerId.ifEmpty { turn.speakerId } == speakerId
            if (!matches && inRun) return@sequence
            if (matches) {
                inRun = true
                yield(word.text.trim())
            }
        }
    }
}.filter { it.isNotEmpty() }.iterator()

private const val QUOTE_WORDS = 40
private const val QUOTE_CHARS = 240
