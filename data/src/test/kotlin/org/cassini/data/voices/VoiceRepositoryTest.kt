package org.cassini.data.voices

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.cassini.core.Failure
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.models.ModelCatalog
import org.cassini.core.speakers.Match
import org.cassini.core.speakers.NoteVoiceCodec
import org.cassini.core.speakers.SpeakerPrint
import org.cassini.core.speakers.Voice
import org.cassini.core.speakers.VoiceCodec
import org.cassini.core.speakers.Voiceprints
import org.cassini.data.checkpoint.CheckpointRepository
import org.cassini.data.checkpoint.noteId
import org.cassini.data.db.JobEntity
import org.cassini.data.db.offMain
import org.cassini.data.notes.NotesHarness
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.random.Random

/** VoiceRepository (architecture 4.5, features.md section 10): forget, generations, pending adopt, broken files. */
@RunWith(RobolectricTestRunner::class)
class VoiceRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness
    private lateinit var checkpoints: CheckpointRepository
    private lateinit var voices: VoiceRepository

    private val sealedId = noteId(1)
    private val draftId = noteId(2)
    private val model = ModelCatalog.voiceprintModel.sha256
    private val luca = voice("voice_luca", "Luca", seed = 1)
    private val anna = voice("voice_anna", "Anna", seed = 2)

    @Before fun setUp() = runTest {
        h = NotesHarness(folder)
        checkpoints = CheckpointRepository(h.db, h.paths, h.dispatchers, h.locks)
        voices = newRepository()
        h.plainNote(sealedId)
        h.plainNote(draftId)
        // A running job, so the draft's names.json is written.
        h.db.jobs().insert(
            JobEntity(
                noteId = draftId,
                seq = 1,
                kind = JobKind.TRANSCRIBE,
                state = JobState.RUNNING,
                enqueuedAt = 1,
                updatedAt = 1,
            ),
        )
        h.paths.work(draftId).mkdirs()
    }

    @After fun tearDown() = h.close()

    private fun newRepository() = VoiceRepository(h.db, h.paths, h.dispatchers, checkpoints.names) { h.now }

    private fun unit(seed: Int, dim: Int = ModelCatalog.VOICEPRINT_DIM): FloatArray {
        val random = Random(seed)
        return requireNotNull(Voiceprints.normalised(FloatArray(dim) { random.nextFloat() - 0.5f }))
    }

    private fun voice(id: String, name: String, seed: Int, seconds: Double = 30.0, dim: Int = 512) =
        Voice(id, name, model, unit(seed, dim), 1, seconds, createdAt = 1, updatedAt = 1)

    private fun print(seed: Int, match: Match? = null) = SpeakerPrint(unit(seed), 12.0, match)

    private fun writePeople(vararg people: Voice) {
        h.paths.people.apply { parentFile!!.mkdirs() }.writeText(VoiceCodec.encode(people.toList()))
    }

    private fun sealedFile(id: String = sealedId) = File(File(h.paths.voices, "notes"), "$id.json")

    private fun pendingFile(id: String = draftId) = File(File(h.paths.voices, "pending"), "$id.json")

    private fun writeSealed(id: String, prints: Map<String, SpeakerPrint>) {
        sealedFile(id).apply { parentFile!!.mkdirs() }.writeText(NoteVoiceCodec.encode(model, prints))
    }

    private fun sealedPrints(id: String = sealedId) = NoteVoiceCodec.decode(sealedFile(id).readText()).second

    private suspend fun links(id: String) =
        h.db.voiceLinks().links(id).associate { "${it.variantId}/${it.speakerId}" to it.voiceId }

    private suspend fun people() = voices.people().first()

    @Test fun forgetScrubsSealedAndPendingFilesLinksAndDrafts() = runTest {
        writePeople(luca, anna)
        writeSealed(
            sealedId,
            mapOf(
                "v1/S1" to print(10, Match(luca.id, 0.8f, Match.State.AUTO)),
                "v1/S2" to print(11, Match(anna.id, 0.9f, Match.State.CONFIRMED)),
            ),
        )
        voices.syncLinks()
        checkpoints.names.mutate(draftId) { it.confirm("S1", luca.id, "Luca", 0, 0.8f).rename("S2", "Bruno") }
        offMain {
            val prints = mapOf("d1/S1" to print(12, Match(luca.id, 0.8f, Match.State.CONFIRMED)))
            voices.savePendingBlocking(draftId, prints, 0)
        }
        val choices = JSONObject(pendingFile().readText()).getJSONObject("confirmations")
        assertEquals(luca.id, choices.getJSONObject("S1").getString("voice"))

        assertEquals(VoiceEdit.Done, voices.forget(luca.id))

        assertEquals(listOf(anna.id), people().voices.map { it.id })
        assertNull(sealedPrints().getValue("v1/S1").match)
        assertEquals(anna.id, sealedPrints().getValue("v1/S2").match?.voiceId)
        assertEquals(mapOf("v1/S1" to null, "v1/S2" to anna.id), links(sealedId))
        val pending = JSONObject(pendingFile().readText())
        assertFalse(pending.getJSONObject("speakers").getJSONObject("d1/S1").has("match"))
        assertFalse(pending.has("confirmations"))
        val draft = checkpoints.names.current(draftId)
        assertTrue(draft.live.isEmpty() && draft.remembered.isEmpty() && draft.automatic.isEmpty())
        // Names already given stay.
        assertEquals(mapOf("S1" to "Luca", "S2" to "Bruno"), draft.labels)
        val onDisk = JSONObject(File(h.paths.work(draftId), "names.json").readText())
        assertEquals(0, onDisk.getJSONObject("remembered").length())
        assertEquals(VoiceEdit.NotFound, voices.forget(luca.id))
    }

    @Test fun forgetEveryoneBumpsTheGenerationAndDeletesEveryPrint() = runTest {
        writePeople(luca, anna)
        writeSealed(sealedId, mapOf("v1/S1" to print(10, Match(luca.id, 0.8f, Match.State.AUTO))))
        File(sealedFile().path + ".corrupt-5").writeText("{")
        voices.syncLinks()
        checkpoints.names.mutate(draftId) { it.confirm("S1", luca.id, "Luca", 0, 0.8f) }
        offMain { voices.savePendingBlocking(draftId, mapOf("d1/S1" to print(12)), 0) }
        assertTrue(pendingFile().isFile)

        assertEquals(VoiceEdit.Done, voices.forgetEveryone())

        assertEquals("1", h.paths.generation.readText())
        assertEquals(listOf("generation"), h.paths.voices.list()!!.toList())
        assertTrue(h.db.voiceLinks().all().isEmpty())
        assertTrue(people().voices.isEmpty())
        val draft = checkpoints.names.current(draftId)
        assertTrue(draft.live.isEmpty() && draft.remembered.isEmpty() && draft.automatic.isEmpty())
        assertEquals(1L, draft.generation)
        assertEquals(mapOf("S1" to "Luca"), draft.labels)
        assertEquals(1L, offMain { voices.generationBlocking() })
        // A second run bumps again.
        voices.forgetEveryone()
        assertEquals("2", h.paths.generation.readText())
    }

    @Test fun anAdoptAfterForgetEveryoneKeepsNoPrint() = runTest {
        writePeople(luca)
        checkpoints.names.mutate(draftId) { it.confirm("S1", luca.id, "Luca", 0, 0.8f) }
        // The job read the generation when it started matching; the user forgets everyone before it seals.
        val generation = offMain { voices.generationBlocking() }
        voices.forgetEveryone()
        offMain {
            val prints = mapOf("d1/S1" to print(12, Match(luca.id, 0.8f, Match.State.AUTO)))
            voices.savePendingBlocking(draftId, prints, generation)
        }
        assertFalse("the draft choices were cleared", JSONObject(pendingFile().readText()).has("confirmations"))

        assertEquals(PendingOutcome.Discarded, voices.adoptPending(draftId))
        assertFalse(sealedFile(draftId).exists())
        assertFalse(pendingFile().exists())
        assertTrue(links(draftId).isEmpty())
        assertTrue(people().voices.isEmpty())
        assertEquals(PendingOutcome.NothingPending, voices.adoptPending(draftId))
    }

    @Test fun anAdoptWithTheSameGenerationMergesAndEnrolsTheDraftChoices() = runTest {
        writePeople(luca)
        // An earlier transcript's prints stay; the new variant's prints come from pending.
        writeSealed(draftId, mapOf("old/S1" to print(20, Match(luca.id, 0.7f, Match.State.CONFIRMED))))
        checkpoints.names.mutate(draftId) {
            it.confirm("S1", luca.id, "Luca", 0, 0.8f).rename("S3", "Bea").remember("S3", "", 0)
        }
        offMain {
            voices.savePendingBlocking(
                draftId,
                mapOf(
                    "new/S1" to print(21, Match(luca.id, 0.8f, Match.State.CONFIRMED)),
                    "new/S2" to print(22, Match("voice_gone", 0.6f, Match.State.SUGGESTED)),
                    "new/S3" to print(23),
                ),
                0,
            )
        }

        val outcome = voices.adoptPending(draftId) as PendingOutcome.Merged

        assertEquals(3, outcome.prints)
        assertEquals(1, outcome.droppedMatches)
        val prints = sealedPrints(draftId)
        assertEquals(setOf("old/S1", "new/S1", "new/S2", "new/S3"), prints.keys)
        assertNull(prints.getValue("new/S2").match)
        val bea = prints.getValue("new/S3").match!!
        assertEquals(Match.State.CONFIRMED, bea.state)
        assertTrue(bea.voiceId.startsWith("voice_"))
        assertEquals(setOf(luca.id, bea.voiceId), outcome.enrolled.toSet())
        val saved = people().voices.associateBy { it.name }
        assertEquals(setOf("Luca", "Bea"), saved.keys)
        assertEquals(2, saved.getValue("Luca").voice.count)
        assertEquals(bea.voiceId, saved.getValue("Bea").id)
        assertEquals(1, saved.getValue("Bea").voice.count)
        assertEquals(
            mapOf("old/S1" to luca.id, "new/S1" to luca.id, "new/S2" to null, "new/S3" to bea.voiceId),
            links(draftId),
        )
        assertFalse(pendingFile().exists())

        // Running it again does nothing.
        assertEquals(PendingOutcome.NothingPending, voices.adoptPending(draftId))
        assertEquals(2, people().voices.size)
    }

    @Test fun aRepeatedAdoptNeverMakesTheSamePersonTwice() = runTest {
        writePeople(luca)
        checkpoints.names.mutate(draftId) {
            it.confirm("S1", luca.id, "Luca", 0, 0.8f).rename("S2", "Bea").remember("S2", "", 0)
        }
        offMain { voices.savePendingBlocking(draftId, mapOf("new/S1" to print(21), "new/S2" to print(22)), 0) }
        val pending = pendingFile().readBytes()
        voices.adoptPending(draftId)
        val after = h.paths.people.readText()

        // A crash before the pending file was deleted: the next start adopts again.
        pendingFile().writeBytes(pending)
        voices = newRepository()
        voices.adoptPending(draftId)

        assertEquals(after, h.paths.people.readText())
        assertEquals(1, people().voices.count { it.name == "Bea" })
        assertEquals(2, people().voices.first { it.id == luca.id }.voice.count)
    }

    @Test fun aForgottenVoiceIsNeitherMatchedNorEnrolledAtAdopt() = runTest {
        writePeople(luca, anna)
        checkpoints.names.mutate(draftId) { it.confirm("S1", luca.id, "Luca", 0, 0.8f) }
        offMain {
            voices.savePendingBlocking(
                draftId,
                mapOf(
                    "new/S1" to print(21, Match(luca.id, 0.8f, Match.State.CONFIRMED)),
                    "new/S2" to print(22, Match(anna.id, 0.75f, Match.State.AUTO)),
                ),
                0,
            )
        }
        // Forgotten while the job sealed: the pending file was scrubbed.
        voices.forget(luca.id)
        // A voice forgotten before the job wrote its prints: the job's match is stale.
        offMain {
            voices.savePendingBlocking(
                sealedId,
                mapOf("new/S1" to print(23, Match(luca.id, 0.8f, Match.State.AUTO))),
                0,
            )
        }

        val outcome = voices.adoptPending(draftId) as PendingOutcome.Merged
        assertTrue(outcome.enrolled.isEmpty())
        assertNull(sealedPrints(draftId).getValue("new/S1").match)
        assertEquals(anna.id, sealedPrints(draftId).getValue("new/S2").match?.voiceId)
        val stale = voices.adoptPending(sealedId) as PendingOutcome.Merged
        assertEquals(1, stale.droppedMatches)
        assertNull(sealedPrints(sealedId).getValue("new/S1").match)
        assertEquals(listOf(anna.id), people().voices.map { it.id })
    }

    @Test fun anUnreadablePeopleFileIsReadAgainAndAWriteErrorIsAnOutcome() = runTest {
        writePeople(luca)
        assertTrue(h.paths.people.setReadable(false))
        assertEquals(VoiceProblem.UNREADABLE, people().problem)
        assertEquals(VoiceEdit.ReadOnly, voices.rename(luca.id, "Luca B."))

        // The error passed: the next edit reads the file again.
        assertTrue(h.paths.people.setReadable(true))
        // AtomicFile cannot write in a directory that is read-only.
        val directory = checkNotNull(h.paths.people.parentFile)
        assertTrue(directory.setWritable(false))
        try {
            assertEquals(VoiceEdit.Failed(Failure.SAVE), voices.rename(luca.id, "Luca B."))
            assertEquals(VoiceEdit.Failed(Failure.SAVE), voices.forget(luca.id))
        } finally {
            directory.setWritable(true)
        }

        assertEquals(VoiceEdit.Done, voices.rename(luca.id, "Luca B."))
        assertEquals(listOf("Luca B."), people().voices.map { it.name })
    }

    @Test fun aNewerPeopleFileStaysReadOnly() = runTest {
        val newer = """{"version":2,"voices":[{"id":"voice_x","name":"X"}],"extra":true}"""
        h.paths.people.apply { parentFile!!.mkdirs() }.writeText(newer)

        val list = people()
        assertEquals(VoiceProblem.NEWER, list.problem)
        assertTrue(list.readOnly)
        assertEquals(VoiceEdit.ReadOnly, voices.rename("voice_x", "Y"))
        assertEquals(VoiceEdit.ReadOnly, voices.forget("voice_x"))
        assertEquals(VoiceEdit.ReadOnly, voices.forgetEveryone())
        checkpoints.names.mutate(draftId) { it.rename("S1", "Bea").remember("S1", "", 0) }
        offMain { voices.savePendingBlocking(draftId, mapOf("new/S1" to print(21)), 0) }
        val outcome = voices.adoptPending(draftId) as PendingOutcome.Merged
        assertTrue(outcome.enrolled.isEmpty())

        assertEquals(newer, h.paths.people.readText())
        assertFalse(h.paths.generation.exists())
        assertTrue(offMain { voices.peopleBlocking() }.isEmpty())
    }

    @Test fun aNewerNoteFileIsKeptAndNotWritten() = runTest {
        val newer = """{"version":3,"model":"m","speakers":{}}"""
        sealedFile(draftId).apply { parentFile!!.mkdirs() }.writeText(newer)

        val sync = voices.syncLinks()
        assertEquals(VoiceProblem.NEWER, sync.problems[draftId])
        assertFalse(voices.setMatch(draftId, "v1", "S1", null))
        offMain { voices.savePendingBlocking(draftId, mapOf("new/S1" to print(21)), 0) }
        assertEquals(PendingOutcome.Kept(VoiceProblem.NEWER), voices.adoptPending(draftId))

        assertEquals(newer, sealedFile(draftId).readText())
        assertTrue("the pending prints wait for a Cassini that can merge them", pendingFile().exists())
    }

    @Test fun unparseableFilesAreRenamedNotDeleted() = runTest {
        h.paths.people.apply { parentFile!!.mkdirs() }.writeText("{not json")
        sealedFile().apply { parentFile!!.mkdirs() }.writeText("[]")
        pendingFile().apply { parentFile!!.mkdirs() }.writeText("""{"version":2,"model":"m"}""")

        val list = people()
        assertEquals(VoiceProblem.DAMAGED, list.problem)
        assertFalse(list.readOnly)
        assertFalse(h.paths.people.exists())
        assertEquals("{not json", File(h.paths.people.path + ".corrupt-${h.now}").readText())
        assertEquals(VoiceProblem.DAMAGED, voices.syncLinks().problems[sealedId])
        assertEquals("[]", File(sealedFile().path + ".corrupt-${h.now}").readText())
        assertEquals(PendingOutcome.NothingPending, voices.adoptPending(draftId))
        assertTrue(File(pendingFile().path + ".corrupt-${h.now}").isFile)

        // Saving people again replaces the set-aside list and clears the problem.
        checkpoints.names.mutate(draftId) { it.rename("S1", "Bea").remember("S1", "", 0) }
        offMain { voices.savePendingBlocking(draftId, mapOf("new/S1" to print(21)), 0) }
        voices.adoptPending(draftId)
        assertNull(people().problem)
        assertEquals(listOf("Bea"), people().voices.map { it.name })
    }

    @Test fun savedVoicesAreSortedAndMarkedStale() = runTest {
        val old = voice("voice_old", "Old model", seed = 3, seconds = 900.0).copy(model = "another-model")
        val short = voice("voice_short", "Short print", seed = 4, seconds = 600.0, dim = 192)
        writePeople(luca.copy(seconds = 500.0), anna.copy(seconds = 20.0), old, short)
        h.db.notes().update(h.row(sealedId).copy(selectedVariant = "v1"))
        writeSealed(sealedId, mapOf("v1/S1" to print(10, Match(anna.id, 0.8f, Match.State.AUTO))))
        voices.syncLinks()

        val list = people().voices
        assertEquals(listOf("Anna", "Old model", "Short print", "Luca"), list.map { it.name })
        assertEquals(listOf(1, 0, 0, 0), list.map { it.notes })
        assertEquals(listOf(false, true, true, false), list.map { it.stale })
        assertEquals(20.0, list.first().confirmedSeconds, 0.0)
    }

    @Test fun renameValidatesAndKeepsTheVoice() = runTest {
        writePeople(luca)
        assertEquals(VoiceEdit.Invalid, voices.rename(luca.id, "  "))
        assertEquals(VoiceEdit.Invalid, voices.rename(luca.id, "x".repeat(81)))
        assertEquals(VoiceEdit.NotFound, voices.rename("voice_nobody", "Ugo"))
        assertEquals(VoiceEdit.Done, voices.rename(luca.id, "  Luca B. "))
        val saved = VoiceCodec.decode(h.paths.people.readText()).single()
        assertEquals("Luca B.", saved.name)
        assertTrue(saved.mean.contentEquals(luca.mean))
    }

    @Test fun deleteForNoteRemovesBothFilesAndTheLinks() = runTest {
        writeSealed(draftId, mapOf("v1/S1" to print(10)))
        File(sealedFile(draftId).path + ".corrupt-3").writeText("{")
        offMain { voices.savePendingBlocking(draftId, mapOf("v2/S1" to print(11)), 0) }
        voices.syncLinks()
        assertEquals(1, links(draftId).size)

        assertTrue(voices.deleteForNote(draftId))

        assertTrue(File(h.paths.voices, "notes").list()!!.isEmpty())
        assertTrue(File(h.paths.voices, "pending").list()!!.isEmpty())
        assertTrue(links(draftId).isEmpty())
        assertTrue(voices.deleteForNote(draftId))
    }

    @Test fun syncLinksDropsTheFilesOfNotesWithoutARow() = runTest {
        val gone = noteId(9)
        writeSealed(gone, mapOf("v1/S1" to print(10)))
        writeSealed(sealedId, mapOf("v1/S1" to print(11), "v1/S2" to print(12)))
        pendingFile(gone).apply { parentFile!!.mkdirs() }.writeText("{}")

        val sync = voices.syncLinks()

        assertEquals(2, sync.links)
        assertEquals(listOf(gone), sync.deleted)
        assertFalse(sealedFile(gone).exists())
        assertFalse(pendingFile(gone).exists())
        assertEquals(2, links(sealedId).size)
    }

    @Test fun sealedNoteEditsKeepFileAndLinksTogether() = runTest {
        writePeople(luca)
        writeSealed(sealedId, mapOf("v1/S1" to print(10), "v1/S2" to print(11)))
        voices.syncLinks()

        val enrolled = voices.enrol(sealedId, "v1", "S1", luca.id, "Luca")
        assertNotNull(enrolled)
        assertEquals(2, enrolled!!.count)
        assertEquals(Match.State.CONFIRMED, voices.prints(sealedId, "v1").getValue("S1").match?.state)
        assertTrue(voices.copyPrints(sealedId, "v1", "m1", without = "S2"))
        assertEquals(Match.State.AUTO, voices.prints(sealedId, "m1").getValue("S1").match?.state)
        assertEquals(setOf("S1"), voices.prints(sealedId, "m1").keys)
        assertEquals(mapOf("v1/S1" to luca.id, "v1/S2" to null, "m1/S1" to luca.id), links(sealedId))

        assertEquals(UnenrolOutcome.Unenrolled, voices.unenrol(sealedId, "v1", "S1"))
        assertEquals(1, people().voices.single().voice.count)
        assertNull(voices.prints(sealedId, "v1").getValue("S1").match)

        val bea = voices.enrol(sealedId, "v1", "S2", null, "Bea")!!
        assertEquals(UnenrolOutcome.Forgotten, voices.unenrol(sealedId, "v1", "S2"))
        assertTrue(people().voices.none { it.id == bea.id })
        assertTrue(voices.setMatch(sealedId, "v1", "S2", Match(luca.id, 0.6f, Match.State.REJECTED)))
        val rejected = h.db.voiceLinks().links(sealedId).first { it.speakerId == "S2" && it.variantId == "v1" }
        assertEquals(Match.State.REJECTED, rejected.state)
    }

    @Test fun aNoteIdThatIsNotAUuidIsRefused() = runTest {
        val refused = runCatching { voices.adoptPending("../people") }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException)
    }
}
