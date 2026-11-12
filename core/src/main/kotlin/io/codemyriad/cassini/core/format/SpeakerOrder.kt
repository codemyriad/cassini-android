package io.codemyriad.cassini.core.format

/** Speaker order by earliest speech, with file order breaking ties; word-body order stays untouched. */
fun List<Word>.speakersByFirstSpeech(): List<String> {
    val first = LinkedHashMap<String, Pair<Long, Int>>()
    forEachIndexed { index, word ->
        val previous = first[word.speaker]
        if (previous == null || word.startMs < previous.first) first[word.speaker] = word.startMs to index
    }
    return first.entries.sortedWith(compareBy({ it.value.first }, { it.value.second })).map { it.key }
}
