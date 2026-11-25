package io.codemyriad.cassini.data.notes

import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.data.db.SearchRow
import io.codemyriad.cassini.data.db.WordEntity

/** Only small excerpts are cached, never transcript words or a whole catalogue's temporary indexes. */
internal data class SearchExcerpt(val snippet: String, val title: String)

/** Per-query cache: unrelated progress/position invalidations must not reindex every long transcript. */
internal class SearchMatches(
    private val loadWords: suspend (String, String, String?) -> List<WordEntity>,
) {
    private data class Key(
        val signature: String?,
        val variant: String?,
        val title: String,
        val bounded: Boolean,
        val speakers: Set<String>?,
        val background: Set<String> = emptySet(),
    )

    private data class Cached(val key: Key, val excerpt: SearchExcerpt?)

    private val excerpts = mutableMapOf<String, Cached>()

    fun retain(ids: Set<String>) {
        excerpts.keys.retainAll(ids)
    }

    suspend fun match(hit: SearchRow, speaker: String?, expression: String, matcher: SpeakerSearch): SearchRow? {
        val variant = hit.row.selectedVariant
        val ids = speaker?.let { name ->
            hit.speakers.filter {
                it.variantId == variant && !it.isPlaceholder && !it.background && it.label.equals(name, ignoreCase = true)
            }.mapTo(HashSet()) { it.speakerId }
        }
        if (ids != null && ids.isEmpty()) return null
        val background = hit.speakers.filter { it.variantId == variant && it.background }
            .mapTo(HashSet()) { it.speakerId }
        val bounded = hit.row.wordEndsBoundedByAudio == true
        val key = Key(hit.documentSignature, variant, hit.row.title, bounded, ids, background)
        // A missing signature cannot attest a word snapshot; do not reuse it, even in partially derived caches.
        val previous = excerpts[hit.row.id]?.takeIf { key.signature != null && it.key == key }
        val cached = previous ?: index(hit, key, expression, matcher)?.also { excerpts[hit.row.id] = it }
        return cached?.excerpt?.let { hit.copy(snippet = it.snippet, titleSnippet = it.title) }
    }

    private suspend fun index(hit: SearchRow, key: Key, expression: String, matcher: SpeakerSearch): Cached? {
        val words = key.variant?.let {
            loadWords(hit.row.id, it, hit.documentSignature).map { word ->
                Word(word.speaker, word.startMs, word.endMs, word.text)
            }
        }.orEmpty()
        if (key.variant != null && words.size != hit.row.wordCount) return null
        val speakers = key.speakers
        val excerpt = if (speakers == null) {
            // Background voices (D112 / L-86) are not searched, as in the persistent body.
            matcher.note(expression, hit.row.title, words.filter { it.speaker !in key.background })
        } else {
            matcher.snippet(expression, words, speakers, key.bounded)?.let { SearchExcerpt(it, hit.row.title) }
        }
        return Cached(key, excerpt)
    }
}
