package org.cassini.data.db

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.library.PauseReason
import org.cassini.core.library.TrashPolicy
import org.cassini.core.speakers.Match
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId

/** The catalogue queries and transactional writes of docs/architecture.md section 3.3. */
@RunWith(RobolectricTestRunner::class)
class CatalogueDaoTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var db: CassiniDatabase

    @Before fun setUp() {
        db = ScratchCatalogue(folder).open().database
    }

    @After fun tearDown() {
        db.close()
    }

    private fun job(noteId: String, seq: Long, state: JobState = JobState.QUEUED) =
        JobEntity(noteId, seq, JobKind.TRANSCRIBE, state, enqueuedAt = seq, updatedAt = seq)

    private fun link(noteId: String, variantId: String, speakerId: String, voiceId: String?, state: Match.State) =
        NoteVoiceLinkEntity(noteId, variantId, speakerId, voiceId, state, score = 0.7f, seconds = 12.5)

    private fun count(table: String, noteId: String): Int =
        db.column("SELECT COUNT(*) FROM $table WHERE note_id = ?", noteId).single()!!.toInt()

    @Test fun deletingANoteCascadesToEveryRow() = runTest {
        val row = note("a")
        db.notes().insert(row)
        db.cache().replaceCache(cache(row, listOf("v1" to words("uno due tre quattro"), "v2" to words("cinque sei"))))
        db.jobs().insert(job("a", 1))
        db.voiceLinks().replaceForNote("a", listOf(link("a", "v1", "S1", "voice-1", Match.State.CONFIRMED)))
        db.notes().insert(note("b"))
        db.cache().replaceCache(cache(note("b"), listOf("v1" to words("uno"))))

        db.notes().delete("a")

        for (table in listOf("variants", "speakers", "words", "note_text", "jobs", "note_voice_links")) {
            assertEquals(table, 0, count(table, "a"))
        }
        assertEquals(listOf("1"), db.column("SELECT COUNT(*) FROM note_fts WHERE note_fts MATCH 'uno'"))
        assertEquals(listOf("b"), db.notes().searchFlow("\"uno*\"").first().map { it.row.id })
        assertEquals(1, count("words", "b"))
    }

    @Test fun replacingTheCacheDropsTheOldVariantsWithTheirSpeakersAndWords() = runTest {
        val row = note("a")
        db.notes().insert(row)
        db.cache().replaceCache(cache(row, listOf("v1" to words("uno due tre quattro"), "v2" to words("cinque sei"))))
        db.cache().replaceCache(cache(row, listOf("v3" to words("sette"))))
        assertEquals(listOf("v3"), db.cache().variants("a").map { it.variantId })
        assertEquals(listOf("v3"), db.column("SELECT DISTINCT variant_id FROM speakers WHERE note_id = 'a'"))
        assertEquals(listOf("v3"), db.column("SELECT DISTINCT variant_id FROM words WHERE note_id = 'a'"))
    }

    @Test fun replaceCacheInsertsTheNoteWhenItHasNoRow() = runTest {
        val row = note("rebuilt", title = "Recovered")
        db.cache().replaceCache(cache(row, listOf("v1" to words("ciao a tutti"))))
        assertEquals("Recovered", db.notes().note("rebuilt")!!.title)
        assertEquals("ciao a tutti", db.notes().text("rebuilt")!!.body)
    }

    @Test fun wordCacheKeepsCanonicalOrderAndPages() = runTest {
        val row = note("a")
        db.notes().insert(row)
        val text = (0 until 50).joinToString(" ") { "w$it" }
        db.cache().replaceCache(cache(row, listOf("v1" to words(text))))
        assertEquals((0 until 50).map { "w$it" }, db.cache().words("a", "v1").map { it.text })
        assertEquals(listOf("w10", "w11", "w12"), db.cache().words("a", "v1", from = 10, limit = 3).map { it.text })
        assertEquals(50, db.cache().cachedWordCount("a", "v1"))
        db.cache().dropWords("a")
        assertEquals(0, db.cache().cachedWordCount("a", "v1"))
        assertEquals(50, db.cache().variants("a").single().wordCount)
    }

    @Test fun voiceNoteCountsOnlyAutoAndConfirmedOnTheSelectedVariantOfLiveNotes() = runTest {
        // a: selected v2. AUTO on v2 counts for X; CONFIRMED on v1 (not selected) does not count for Y.
        val a = note("a")
        db.notes().insert(a)
        val two = listOf("v1" to words("uno due tre quattro"), "v2" to words("cinque sei sette otto"))
        db.cache().replaceCache(cache(a, two, selected = "v2"))
        // b: no selected_variant, so its default v1 is selected. CONFIRMED X counts; SUGGESTED Y does not.
        db.notes().insert(note("b"))
        val b = cache(note("b"), listOf("v1" to words("uno due tre quattro")))
        db.cache().replaceCache(b.copy(note = b.note.copy(selectedVariant = null)))
        // c: trashed, its AUTO X does not count.
        db.notes().insert(note("c"))
        db.cache().replaceCache(cache(note("c"), listOf("v1" to words("uno"))))
        db.notes().moveToTrash("c", at = 9_000, job = null)
        db.voiceLinks().replaceForNote(
            "a",
            listOf(
                link("a", "v2", "S1", "X", Match.State.AUTO),
                link("a", "v2", "S2", "X", Match.State.CONFIRMED),
                link("a", "v1", "S1", "Y", Match.State.CONFIRMED),
                link("a", "v2", "S3", "Z", Match.State.REJECTED),
            ),
        )
        db.voiceLinks().replaceForNote(
            "b",
            listOf(
                link("b", "v1", "S1", "X", Match.State.CONFIRMED),
                link("b", "v1", "S2", "Y", Match.State.SUGGESTED),
            ),
        )
        db.voiceLinks().replaceForNote("c", listOf(link("c", "v1", "S1", "X", Match.State.AUTO)))

        val counts = db.notes().voiceNoteCounts().first().associate { it.voiceId to it.notes }
        assertEquals(mapOf("X" to 2), counts)
    }

    @Test fun trashIsOrderedOldestFirstAndExpiredUsesTheCutoff() = runTest {
        val zone = ZoneId.of("Europe/Rome")
        val now = Instant.parse("2026-11-01T10:00:00Z")
        val oct2 = Instant.parse("2026-10-02T09:00:00Z").toEpochMilli()
        val oct9 = Instant.parse("2026-10-09T09:00:00Z").toEpochMilli()
        val oct20 = Instant.parse("2026-10-20T09:00:00Z").toEpochMilli()
        for (id in listOf("live", "mid", "new", "old")) db.notes().insert(note(id))
        db.notes().moveToTrash("mid", at = oct9, job = null)
        db.notes().moveToTrash("new", at = oct20, job = null)
        db.notes().moveToTrash("old", at = oct2, job = TrashedJob(JobState.QUEUED, JobKind.TRANSCRIBE_AGAIN))

        assertEquals(listOf("old", "mid", "new"), db.notes().trashFlow().first().map { it.id })
        assertEquals(listOf("old"), db.notes().expired(TrashPolicy.purgeCutoff(now, zone)))
        assertEquals(TrashedJob(JobState.QUEUED, JobKind.TRANSCRIBE_AGAIN), db.notes().note("old")!!.trashedJob)
        assertEquals(listOf("QUEUED/TRANSCRIBE_AGAIN"), db.column("SELECT trashed_job FROM notes WHERE id = 'old'"))
        assertEquals(Counts(live = 1, trashed = 3, queued = 0), db.notes().counts().first())
    }

    @Test fun libraryListsLiveNotesNewestFirstWithJobVariantAndPreview() = runTest {
        val a = note("a", createdAt = 1_000)
        db.notes().insert(a)
        val two = listOf("v1" to words("prima versione del testo"), "v2" to words("seconda"))
        db.cache().replaceCache(cache(a, two, selected = "v1"))
        db.notes().insert(note("b", createdAt = 3_000, audioKind = AudioKind.PLAIN))
        db.jobs().insert(job("b", 1).copy(state = JobState.PAUSED, pauseReason = PauseReason.USER, doneMs = 760_000))
        db.notes().insert(note("c", createdAt = 2_000))
        db.notes().setPositionAndPreview("c", 5_000, "…quindi la consegna…")
        db.notes().insert(note("gone", createdAt = 4_000))
        db.notes().moveToTrash("gone", at = 5_000, job = null)

        val rows = db.notes().libraryFlow().first()
        assertEquals(listOf("b", "c", "a"), rows.map { it.id })
        val b = rows[0]
        assertEquals(JobState.PAUSED, b.jobState)
        assertEquals(PauseReason.USER, b.pauseReason)
        assertEquals(760_000L, b.doneMs)
        assertNull(b.preview)
        assertNull(b.wordCount)
        assertEquals("…quindi la consegna…", rows[1].preview)
        val first = rows[2]
        assertNull(first.jobState)
        assertEquals("v1", first.selectedVariant)
        assertEquals(4, first.wordCount)
        assertEquals(2, first.speakerCount)
        assertEquals("prima versione del testo", first.preview)
    }

    @Test fun noteFlowCarriesVariantsSpeakersAndJob() = runTest {
        val a = note("a")
        db.notes().insert(a)
        db.cache().replaceCache(cache(a, listOf("v1" to words("uno due tre quattro"))))
        db.jobs().insert(job("a", 4))
        val details = db.notes().noteFlow("a").first()!!
        assertEquals("a", details.note.id)
        assertEquals(listOf("v1"), details.variants.map { it.variantId })
        assertEquals(listOf("S1", "S2"), details.speakers.map { it.speakerId })
        assertEquals(4L, details.job!!.seq)
        assertNull(db.notes().noteFlow("missing").first())
    }

    @Test fun enumsAreStoredByName() = runTest {
        db.notes().insert(note("a"))
        db.jobs().insert(job("a", 1).copy(state = JobState.PAUSED, pauseReason = PauseReason.MEMORY))
        assertEquals(listOf("RECORDED"), db.column("SELECT origin FROM notes"))
        assertEquals(listOf("DOCUMENT"), db.column("SELECT audio_kind FROM notes"))
        assertEquals(listOf("OK"), db.column("SELECT document_state FROM notes"))
        assertEquals(listOf("PAUSED"), db.column("SELECT state FROM jobs"))
        assertEquals(listOf("MEMORY"), db.column("SELECT pause_reason FROM jobs"))
        assertEquals(listOf("TRANSCRIBE"), db.column("SELECT kind FROM jobs"))
    }

    @Test fun verificationResultRoundTrips() = runTest {
        val warnings = listOf("pretty-printed body", "x \"quoted\"")
        val row = note("a").copy(documentWarnings = warnings, documentSignature = "10:20:ab")
        db.notes().insert(row)
        assertEquals(row, db.notes().note("a"))
    }

    @Test fun claimNextHandsOutOneRunningJobInFifoOrder() = runTest {
        for (id in listOf("a", "b", "c", "t")) db.notes().insert(note(id))
        db.jobs().insert(job("t", 0))
        db.notes().moveToTrash("t", at = 2_000, job = TrashedJob(JobState.QUEUED, JobKind.TRANSCRIBE))
        db.jobs().insert(job("c", 3))
        db.jobs().insert(job("a", 1))
        db.jobs().insert(job("b", 2))
        assertEquals(listOf("a", "b", "c"), db.jobs().queueFlow().first().map { it.job.noteId })

        val claims = (1..8).map { async { db.jobs().claimNext(now = 5_000) } }.awaitAll()
        assertEquals(listOf("a"), claims.mapNotNull { it?.noteId })
        assertEquals(1, db.jobs().runningCount())
        db.jobs().delete("a")
        assertEquals("b", db.jobs().claimNext(now = 6_000)!!.noteId)
        assertEquals(3L, db.jobs().maxSeq())
        assertEquals(Counts(live = 3, trashed = 1, queued = 1), db.notes().counts().first())
    }

    @Test fun replaceAllLinksSkipsNotesWithoutARow() = runTest {
        db.notes().insert(note("a"))
        val orphans = db.voiceLinks().replaceAll(
            listOf(link("a", "v1", "S1", "X", Match.State.AUTO), link("gone", "v1", "S1", "X", Match.State.AUTO)),
        )
        assertEquals(setOf("gone"), orphans)
        assertEquals(listOf("a"), db.voiceLinks().all().map { it.noteId })
        db.voiceLinks().deleteForVoice("X")
        assertTrue(db.voiceLinks().all().isEmpty())
    }

    @Test fun importDedupeSeesOnlyLiveNotes() = runTest {
        db.notes().insert(note("a").copy(sourceSha256 = "abc"))
        assertEquals("a", db.notes().liveWithSource("abc"))
        db.notes().moveToTrash("a", at = 2_000, job = null)
        assertNull(db.notes().liveWithSource("abc"))
    }
}
