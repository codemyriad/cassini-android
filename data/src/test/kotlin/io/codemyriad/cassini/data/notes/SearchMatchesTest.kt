package io.codemyriad.cassini.data.notes

import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.data.db.LibraryRow
import io.codemyriad.cassini.data.db.SearchRow
import io.codemyriad.cassini.data.db.SpeakerEntity
import io.codemyriad.cassini.data.db.WordEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchMatchesTest {
    private fun hit(signature: String? = "one"): SearchRow = SearchRow(
        LibraryRow(
            id = "a",
            title = "Meeting",
            createdAt = 0,
            durationMs = 400,
            audioKind = AudioKind.DOCUMENT,
            documentState = DocumentState.OK,
            preview = null,
            jobState = null,
            jobKind = null,
            pauseReason = null,
            failure = null,
            doneMs = null,
            totalMs = null,
            selectedVariant = "v1",
            wordCount = 1,
            speakerCount = 1,
        ),
        snippet = "",
        titleSnippet = "Meeting",
        documentSignature = signature,
        speakers = listOf(SpeakerEntity("a", "v1", "S1", 0, "Ada", false)),
    )

    @Test fun progressReusesOnlyTheExcerptAndKeepsTheCurrentRow() = runTest {
        var reads = 0
        val cache = SearchMatches { id, variant, _ ->
            reads++
            listOf(WordEntity(id, variant, 0, 0, 400, "S1", "delivery"))
        }
        SpeakerSearch().use { matcher ->
            val first = hit()
            assertNotNull(cache.match(first, null, "\"deliver*\"", matcher))
            val progress = first.copy(row = first.row.copy(jobState = JobState.RUNNING, doneMs = 200))
            val result = cache.match(progress, null, "\"deliver*\"", matcher)
            assertEquals(1, reads)
            assertEquals(progress.row, result?.row)
            val renamed = first.copy(row = first.row.copy(title = "Renamed"))
            assertEquals("Renamed", cache.match(renamed, null, "\"deliver*\"", matcher)?.titleSnippet)
            assertEquals(2, reads)
            assertNotNull(cache.match(first.copy(documentSignature = "two"), null, "\"deliver*\"", matcher))
            assertEquals(3, reads)
            val variant = first.copy(row = first.row.copy(selectedVariant = "v2"))
            assertNotNull(cache.match(variant, null, "\"deliver*\"", matcher))
            assertEquals(4, reads)
            val bounded = first.copy(row = first.row.copy(wordEndsBoundedByAudio = true))
            assertNotNull(cache.match(bounded, null, "\"deliver*\"", matcher))
            assertEquals(5, reads)
            cache.retain(emptySet())
            assertNotNull(cache.match(bounded, null, "\"deliver*\"", matcher))
            assertEquals(6, reads)
        }
    }

    @Test fun aChangedSpeakerIdentityInvalidatesAnOtherwiseIdenticalSnapshot() = runTest {
        var reads = 0
        val cache = SearchMatches { id, variant, _ ->
            reads++
            listOf(WordEntity(id, variant, 0, 0, 400, if (reads == 1) "S1" else "S2", "delivery"))
        }
        SpeakerSearch().use { matcher ->
            assertNotNull(cache.match(hit(), "Ada", "\"deliver*\"", matcher))
            val changed = hit().copy(speakers = listOf(SpeakerEntity("a", "v1", "S2", 0, "Ada", false)))
            assertNotNull(cache.match(changed, "Ada", "\"deliver*\"", matcher))
            assertEquals(2, reads)
        }
    }

    @Test fun missingAndSupersededSnapshotsAreNeverCachedAsARealMiss() = runTest {
        var reads = 0
        val cache = SearchMatches { id, variant, _ ->
            reads++
            if (reads == 1) emptyList() else listOf(WordEntity(id, variant, 0, 0, 400, "S1", "delivery"))
        }
        SpeakerSearch().use { matcher ->
            assertNull(cache.match(hit(), null, "\"deliver*\"", matcher))
            assertNotNull(cache.match(hit(), null, "\"deliver*\"", matcher))
            assertEquals(2, reads)
            val unsigned = hit(signature = null)
            assertNotNull(cache.match(unsigned, null, "\"deliver*\"", matcher))
            assertNotNull(cache.match(unsigned, null, "\"deliver*\"", matcher))
            assertEquals(4, reads)
        }
    }
}
