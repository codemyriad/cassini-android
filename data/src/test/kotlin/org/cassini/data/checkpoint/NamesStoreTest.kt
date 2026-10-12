package org.cassini.data.checkpoint

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import org.cassini.core.checkpoint.CheckpointNames
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.speakers.Match
import org.cassini.data.db.JobEntity
import org.cassini.data.db.offMain
import org.cassini.data.notes.NotesHarness
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

/** The draft names owner (architecture 3.6): one AtomicReference per note, reducers only, the latest value on disk. */
@RunWith(RobolectricTestRunner::class)
class NamesStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness
    private lateinit var repo: CheckpointRepository
    private val id = noteId(1)

    @Before fun setUp() = runTest {
        h = NotesHarness(folder)
        repo = CheckpointRepository(h.db, h.paths, h.dispatchers, h.locks)
        h.plainNote(id)
        h.db.jobs().insert(
            JobEntity(
                noteId = id,
                seq = 1,
                kind = JobKind.TRANSCRIBE,
                state = JobState.RUNNING,
                enqueuedAt = 1,
                updatedAt = 1,
            ),
        )
    }

    @After fun tearDown() = h.close()

    private val namesFile get() = File(h.paths.work(id), "names.json")

    /** A new repository over the same files: the in-memory names of the earlier process are gone. */
    private fun restarted() = CheckpointRepository(h.db, h.paths, h.dispatchers, h.locks)

    @Test fun namesWithoutTheNewMembersLoadAsEmpty() = runTest {
        // names.json as 0.1.0 wrote it: no live, no rejected.
        namesFile.apply { parentFile!!.mkdirs() }.writeText(
            """{"labels":{"S1":"Anna"},"merges":{"S3":"S2"},"remembered":{"S1":""},"automatic":{},"generation":4}""",
        )

        val names = repo.draftNames(id).current()
        assertEquals(mapOf("S1" to "Anna"), names.labels)
        assertEquals(mapOf("S3" to "S2"), names.merges)
        assertEquals(mapOf("S1" to ""), names.remembered)
        assertEquals(4L, names.generation)
        assertTrue(names.live.isEmpty() && names.rejected.isEmpty())

        // A change that leaves them empty does not write them.
        repo.draftNames(id).mutate { it.rename("S2", "Bruno") }
        val json = JSONObject(namesFile.readText())
        assertFalse(json.has("live") || json.has("rejected"))
        assertEquals("Bruno", json.getJSONObject("labels").getString("S2"))
    }

    @Test fun namesWithTheNewMembersRoundTrip() = runTest {
        repo.draftNames(id).mutate { names ->
            names.confirm("S1", "voice-anna", "Anna", 2, 0.81f)
                .applyLive(mapOf("S2" to Match("voice-luca", 0.62f, Match.State.SUGGESTED)), emptyMap())
                .reject("S3", "voice-marta")
        }
        val json = JSONObject(namesFile.readText())
        assertTrue(json.has("live") && json.has("rejected"))

        val reloaded = restarted().draftNames(id).current()
        assertEquals(Match("voice-anna", 0.81f, Match.State.CONFIRMED), reloaded.live["S1"])
        assertEquals(Match("voice-luca", 0.62f, Match.State.SUGGESTED), reloaded.live["S2"])
        assertEquals(setOf("voice-marta"), reloaded.rejected["S3"])
        assertEquals(mapOf("S1" to "voice-anna"), reloaded.remembered)
    }

    @Test fun namesAreLoadedOncePerProcess() = runTest {
        repo.draftNames(id).mutate { it.rename("S1", "Anna") }
        // Someone else writes the file: the owner keeps its value and the next write restores it.
        namesFile.writeText("""{"labels":{"S1":"Other"},"merges":{}}""")
        assertEquals("Anna", repo.draftNames(id).current().labels["S1"])

        repo.release(id)
        assertEquals("Other", repo.draftNames(id).current().labels["S1"])
    }

    @Test fun aChangeAfterAdoptDoesNotCreateWork() = runTest {
        repo.draftNames(id).mutate { it.rename("S1", "Anna") }
        // Adopt removed the job and deleted work/; a late change from the screen stays in memory only.
        h.db.jobs().delete(id)
        h.paths.work(id).deleteRecursively()

        val names = repo.draftNames(id).mutate { it.rename("S2", "Bruno") }

        assertEquals("Bruno", names.labels["S2"])
        assertFalse(h.paths.work(id).exists())
    }

    @Test fun observersSeeEveryWrite() = runTest {
        val draft = repo.draftNames(id)
        assertEquals(NamesStore.EMPTY, draft.observe().first())
        draft.mutate { it.rename("S1", "Anna") }
        assertEquals("Anna", draft.observe().first().labels["S1"])
    }

    @Test fun anObserverKeepsGettingValuesAfterRelease() = runTest {
        val draft = repo.draftNames(id)
        draft.mutate { it.rename("S1", "Anna") }
        val subscribed = CompletableDeferred<Unit>()
        val seen = async(Dispatchers.Default) {
            draft.observe()
                .onEach { if (it.labels["S1"] == "Anna") subscribed.complete(Unit) }
                .first { it.labels["S1"] == "Bruno" }
        }
        subscribed.await()

        repo.discard(id) // "Start over": the old draft is gone, the job row stays.
        draft.mutate { it.rename("S1", "Bruno") }

        assertEquals(mapOf("S1" to "Bruno"), seen.await().labels)
    }

    @Test fun aChangeToAReleasedDraftIsNotWritten() = runTest {
        repo.draftNames(id).mutate { it.rename("S1", "Anna") }

        // The release comes between the change in memory and the file write.
        repo.draftNames(id).mutate { names ->
            repo.names.releaseBlocking(id)
            names.rename("S1", "Bruno")
        }

        assertEquals("Anna", JSONObject(namesFile.readText()).getJSONObject("labels").getString("S1"))
        assertEquals("Anna", repo.draftNames(id).current().labels["S1"])
    }

    @Test fun mutateAllReachesOpenAndSavedDrafts() = runTest {
        val other = noteId(2)
        h.plainNote(other)
        File(h.paths.work(other), "names.json").apply { parentFile!!.mkdirs() }.writeText(
            """{"labels":{},"merges":{},"remembered":{"S1":"voice-anna"},"automatic":{},"generation":1}""",
        )
        repo.draftNames(id).mutate { it.remember("S1", "voice-anna", 1) }

        val changed = repo.names.mutateAll { names ->
            names.copy(remembered = names.remembered.filterValues { it != "voice-anna" })
        }

        assertEquals(listOf(id, other).sorted(), changed)
        assertTrue(repo.draftNames(id).current().remembered.isEmpty())
        val onDisk = CheckpointNames.decode(JSONObject(File(h.paths.work(other), "names.json").readText()))
        assertTrue(onDisk.remembered.isEmpty())
    }

    /**
     * The writes run in another order than the changes: the job changed the names first but writes last. The file
     * must still hold the UI's later change, so the writer stores the current value, not its own result.
     */
    @Test fun aLateWriteNeverStoresAnOlderValue() {
        val draft = repo.draftNames(id)
        offMain { draft.currentBlocking() }
        val lock = h.locks.lockFor(id)
        val pool = Executors.newFixedThreadPool(2)
        val holder: Future<Future<CheckpointNames>> = pool.submit(
            Callable {
                lock.withLock {
                    // The job changes the names, then waits for the lock to write.
                    val job = pool.submit(Callable { draft.mutateBlocking { it.rename("S1", "Anna") } })
                    while (!lock.hasQueuedThreads()) Thread.sleep(1)
                    // Meanwhile the UI changes them and writes first (this thread holds the lock already).
                    draft.mutateBlocking { it.rename("S2", "Bruno") }
                    job
                }
            },
        )
        holder.get(1, TimeUnit.MINUTES).get(1, TimeUnit.MINUTES)
        pool.shutdown()

        val onDisk = CheckpointNames.decode(JSONObject(namesFile.readText()))
        assertEquals(mapOf("S1" to "Anna", "S2" to "Bruno"), onDisk.labels)
    }

    /**
     * The UI confirms speakers while the job applies live matches, on two threads, 1000 times each. No confirmation
     * is lost in memory or on disk, and the file holds the final value.
     */
    @Test fun concurrentChangesLoseNothing() {
        val iterations = 1_000
        val draft = repo.draftNames(id)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val ui = pool.submit {
            start.await()
            repeat(iterations) { i ->
                draft.mutateBlocking { it.confirm("ui$i", "voice-$i", "Name $i", 0, 0.9f) }
            }
        }
        val job = pool.submit {
            start.await()
            repeat(iterations) { i ->
                val match = Match("auto-$i", 0.8f, Match.State.AUTO)
                draft.mutateBlocking { it.applyLive(mapOf("job$i" to match), mapOf("auto-$i" to "Auto $i")) }
            }
        }
        start.countDown()
        ui.get(2, TimeUnit.MINUTES)
        job.get(2, TimeUnit.MINUTES)
        pool.shutdown()

        val final = offMain { draft.currentBlocking() }
        repeat(iterations) { i ->
            assertEquals("Name $i", final.labels["ui$i"])
            assertEquals(Match("voice-$i", 0.9f, Match.State.CONFIRMED), final.live["ui$i"])
            assertEquals("voice-$i", final.remembered["ui$i"])
        }
        // Each live round replaces the open speakers' matches: only the last one is left.
        val open = final.live.filterValues { it.state == Match.State.AUTO }
        assertEquals(mapOf("job${iterations - 1}" to Match("auto-${iterations - 1}", 0.8f, Match.State.AUTO)), open)
        assertEquals(final, CheckpointNames.decode(JSONObject(namesFile.readText())))
        assertEquals(final, restarted().let { offMain { it.draftNames(id).currentBlocking() } })
    }
}
