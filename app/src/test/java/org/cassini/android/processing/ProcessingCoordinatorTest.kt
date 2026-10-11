package org.cassini.android.processing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.cassini.android.AppGraph
import org.cassini.android.Catalogue
import org.cassini.android.settings.SettingsViewModel
import org.cassini.core.Failure
import org.cassini.core.asr.IncrementalState
import org.cassini.core.checkpoint.CheckpointState
import org.cassini.core.checkpoint.RecognitionState
import org.cassini.core.format.Word
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.library.PauseReason
import org.cassini.core.models.ModelCatalog
import org.cassini.core.models.ModelPart
import org.cassini.core.speakers.Match
import org.cassini.data.AppPaths
import org.cassini.data.CassiniDispatchers
import org.cassini.data.db.JobEntity
import org.cassini.data.models.ModelFetcher
import org.cassini.data.models.ModelStatus
import org.cassini.data.settings.QueueHold
import org.cassini.engine.job.BatterySource
import org.cassini.engine.job.DownloadError
import org.cassini.engine.job.JobEvent
import org.cassini.engine.job.JobOutcome
import org.cassini.engine.job.JobPhase
import org.cassini.ui.settings.ModelStatusUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The queue of architecture 5.2-5.6 over the real AppGraph, JobRepository and Room on Robolectric, with a fake job
 * runner: each claim gets a [FakeJob] that the test ends.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessingCoordinatorTest {
    @get:Rule val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val graphs = mutableListOf<AppGraph>()
    private val fixture = File(checkNotNull(System.getProperty("cassini.fixtures")), "audio/fleurs-it-short.opus")

    /** Every job the coordinator created, in order. */
    private val created = CopyOnWriteArrayList<FakeJob>()
    private val starts = AtomicInteger()
    private val visibility = FakeVisibility()
    private val notices = RecordingNotices()
    private lateinit var paths: AppPaths
    private lateinit var graph: AppGraph

    private val coordinator: ProcessingCoordinator get() = graph.processing

    /** A graph on [root]; the service start is a drain, as `ProcessingService` does it. */
    private fun graph(
        root: AppPaths = AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder()),
        realJobs: Boolean = false,
        battery: BatterySource? = null,
        awaitReady: Boolean = true,
        fetcher: ModelFetcher = ModelFetcher { _, _, _ -> fail("No model is fetched in this test") },
    ): AppGraph {
        paths = root
        val hooks = AppGraph.ProcessingHooks(
            jobs = if (realJobs) null else ({ g -> ProcessingJobFactory { FakeJob(it, g).also(created::add) } }),
            starter = { g ->
                ServiceStarter {
                    starts.incrementAndGet()
                    g.processing.drain()
                    true
                }
            },
            notices = notices,
            visibility = visibility,
            battery = battery,
        )
        val config = AppGraph.Config(
            paths = root,
            dispatchers = CassiniDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Unconfined),
            fetcher = fetcher,
            network = { true },
            processing = hooks,
        )
        return AppGraph(context, config).also {
            graphs += it
            graph = it
            if (awaitReady) runBlocking { withTimeout(TIMEOUT_MS) { it.ready.await() } }
        }
    }

    @After fun tearDown() = graphs.forEach(AppGraph::close)

    private suspend fun catalogue(): Catalogue = graph.awaitCatalogue()

    private suspend fun consent() = graph.models.recordConsent()

    // FIFO, single RUNNING, stop and hold. ------------------------------------------------------------------------

    @Test fun jobsRunOneAtATimeInQueueOrder() = runBlocking {
        graph()
        consent()
        val ids = listOf(note("Uno"), note("Due"), note("Tre"))
        ids.forEach { assertEquals(TranscribeOutcome.Queued(changed = true), coordinator.transcribe(it)) }

        val first = job(0)
        assertEquals(ids[0], first.noteId)
        assertEquals(listOf(ids[0]), runningRows())
        val state = coordinator.state.first { it.running?.noteId == ids[0] && it.queue.size == 2 }
        assertEquals(listOf(ids[1], ids[2]), state.queue.map { it.noteId })
        assertEquals(1, state.notes.getValue(ids[2]).ahead)
        assertEquals(1, created.size)

        first.finish(JobOutcome.Done)
        assertEquals(ids[1], job(1).noteId)
        assertEquals(listOf(ids[1]), runningRows())
        job(1).finish(JobOutcome.Done)
        job(2).finish(JobOutcome.Done)
        waitUntil { catalogue().jobs.queue().first().isEmpty() }
        assertEquals(ids, created.map { it.noteId })
        coordinator.awaitIdle()
        assertNull(coordinator.state.value.running)
    }

    @Test fun stopKeepsProgressAndTheNextNoteStarts() = runBlocking {
        graph()
        consent()
        val a = note("A")
        val b = note("B")
        coordinator.transcribe(a)
        coordinator.transcribe(b)
        val first = job(0)
        checkpoint(a, doneMs = 28_000)

        assertTrue(coordinator.stop(a))
        assertEquals(PauseReason.USER, first.stopReason.get())
        assertEquals(b, job(1).noteId)
        val paused = requireNotNull(catalogue().jobs.job(a))
        assertEquals(JobState.PAUSED, paused.state)
        assertEquals(PauseReason.USER, paused.pauseReason)
        assertEquals(28_000L, paused.doneMs)
        assertFalse("a note that does not run cannot be stopped", coordinator.stop(a))
    }

    @Test fun stopAllHoldsTheQueueUntilContinue() = runBlocking {
        graph()
        consent()
        val a = note("A")
        val b = note("B")
        coordinator.transcribe(a)
        coordinator.transcribe(b)
        job(0)

        coordinator.stopAll()
        waitUntil { catalogue().jobs.job(a)?.state == JobState.PAUSED }
        coordinator.awaitIdle()
        assertEquals(QueueHold.USER, graph.settings.queueHold.first())
        assertEquals(JobState.QUEUED, catalogue().jobs.job(b)?.state)
        assertEquals("the held queue starts nothing", 1, created.size)
        val held = coordinator.state.first { it.hold == QueueHold.USER && it.running == null }
        assertEquals(listOf(b), held.queue.map { it.noteId })

        coordinator.continueQueue()
        assertEquals(b, job(1).noteId)
        assertNull(graph.settings.queueHold.first())
    }

    @Test fun anExplicitTranscribeClearsTheUserHold() = runBlocking {
        graph()
        consent()
        graph.settings.setQueueHold(QueueHold.USER)
        val a = note("A")
        coordinator.transcribe(a, origin = StartOrigin.AUTOMATIC)
        delay(SETTLE_MS)
        assertEquals("the automatic enqueue keeps the hold", 0, created.size)
        coordinator.transcribe(a)
        assertEquals(a, job(0).noteId)
    }

    // Remove from queue, Start over. ------------------------------------------------------------------------------

    @Test fun removeFromQueueDeletesTheRowOrPausesANoteWithACheckpoint() = runBlocking {
        graph()
        consent()
        val running = note("Running")
        val bare = note("Bare")
        val saved = note("Saved")
        listOf(running, bare, saved).forEach { coordinator.transcribe(it) }
        job(0)
        checkpoint(saved, doneMs = 56_000)

        assertEquals(DequeueOutcome.REMOVED, coordinator.dequeue(bare))
        assertNull(catalogue().jobs.job(bare))
        assertEquals(DequeueOutcome.PAUSED, coordinator.dequeue(saved))
        val paused = requireNotNull(catalogue().jobs.job(saved))
        assertEquals(JobState.PAUSED, paused.state)
        assertEquals(PauseReason.USER, paused.pauseReason)
        assertEquals(56_000L, paused.doneMs)
        assertEquals(DequeueOutcome.NOT_QUEUED, coordinator.dequeue(running))

        job(0).finish(JobOutcome.Done)
        coordinator.awaitIdle()
        assertEquals("nothing else was queued", 1, created.size)
    }

    @Test fun startOverDiscardsTheCheckpointAndQueuesTheNoteAgain() = runBlocking {
        graph()
        consent()
        val a = note("A")
        coordinator.transcribe(a)
        val first = job(0)
        checkpoint(a, doneMs = 28_000)
        withContext(Dispatchers.IO) { File(paths.voices, "pending").mkdirs() }
        val pending = File(paths.voices, "pending/$a.json").apply { writeText("{}") }

        // Start over on the running note: Cancel, then discard after join, then the back of the queue.
        assertEquals(TranscribeOutcome.Queued(changed = true), coordinator.startOver(a))
        assertTrue(first.cancelled.get())
        assertFalse(paths.work(a).exists())
        assertFalse(pending.exists())
        val again = job(1)
        assertEquals(a, again.noteId)

        // Start over on a paused note.
        checkpoint(a, doneMs = 28_000)
        again.finish(JobOutcome.Paused(PauseReason.USER))
        waitUntil { catalogue().jobs.job(a)?.state == JobState.PAUSED }
        assertEquals(28_000L, catalogue().jobs.job(a)?.doneMs)
        coordinator.startOver(a)
        assertEquals(a, job(2).noteId)
        assertFalse(paths.work(a).exists())
        assertEquals(0L, catalogue().jobs.job(a)?.doneMs)
    }

    @Test fun startOverAfterTheRunEndedButBeforeItsOutcomeIsWrittenStillDiscards() = runBlocking {
        graph()
        consent()
        val a = note("A")
        coordinator.transcribe(a)
        val first = job(0)
        first.holdEvents = true
        checkpoint(a, doneMs = 28_000)

        // Stop: the run ends Paused, but its events stay open, so the outcome is not written yet.
        assertTrue(coordinator.stop(a))
        withTimeout(TIMEOUT_MS) { first.ended.await() }
        val over = async { coordinator.startOver(a) }
        delay(SETTLE_MS)
        first.closeEvents()

        assertEquals(TranscribeOutcome.Queued(changed = true), withTimeout(TIMEOUT_MS) { over.await() })
        assertFalse("the checkpoint is discarded", paths.work(a).exists())
        assertEquals(a, job(1).noteId)
        assertEquals(0L, catalogue().jobs.job(a)?.doneMs)
    }

    // Cancel of a download (features.md 13.4). -----------------------------------------------------------------

    @Test fun cancelDuringTheDownloadOfAJobPutsTheNoteBackToItsStateFromBefore() = runBlocking {
        graph()
        consent()
        val fresh = note("Fresh")
        val resumed = note("Resumed")
        coordinator.transcribe(fresh)
        coordinator.transcribe(resumed)
        checkpoint(resumed, doneMs = 28_000)

        // A first Transcribe goes back to "Audio only": the row goes.
        job(0).send(JobEvent.Downloading(null))
        coordinator.state.first { it.running?.noteId == fresh && it.running?.phase is JobPhase.Downloading }
        coordinator.cancelDownload()
        assertEquals(PauseReason.USER, job(0).stopReason.get())
        assertEquals(resumed, job(1).noteId)
        assertNull(catalogue().jobs.job(fresh))

        // A note with a checkpoint goes back to PAUSED and keeps it.
        job(1).send(JobEvent.Downloading(null))
        coordinator.state.first { it.running?.noteId == resumed && it.running?.phase is JobPhase.Downloading }
        coordinator.cancelDownload()
        waitUntil { catalogue().jobs.job(resumed)?.state == JobState.PAUSED }
        val paused = requireNotNull(catalogue().jobs.job(resumed))
        assertEquals(PauseReason.USER, paused.pauseReason)
        assertEquals(28_000L, paused.doneMs)
        assertTrue(paths.work(resumed).isDirectory)
    }

    // A failure around the job. ------------------------------------------------------------------------------------

    @Test fun aFailureAroundTheJobEndsItAndTheQueueGoesOn() = runBlocking {
        graph(battery = BatterySource { error("No battery here") })
        consent()
        val a = note("A")
        val b = note("B")
        coordinator.transcribe(a)
        coordinator.transcribe(b)
        val first = job(0)

        // The tracker reads the battery when inference begins, and fails.
        first.send(JobEvent.Started(4_000, restored = null, speakers = true, prints = true, lowMemory = false))
        assertEquals(b, job(1).noteId)
        assertTrue("the job was stopped", first.cancelled.get())
        assertEquals(JobState.FAILED, catalogue().jobs.job(a)?.state)
        assertTrue("the coordinator is not stuck", withTimeout(TIMEOUT_MS) { coordinator.moveToTrash(a) })
    }

    // Move to trash (rule 9). ------------------------------------------------------------------------------------

    @Test fun trashDeletesAQueuedRowButKeepsItsWork() = runBlocking {
        graph()
        consent()
        val running = note("Running")
        val queued = note("Queued")
        coordinator.transcribe(running)
        coordinator.transcribe(queued)
        job(0)
        checkpoint(queued, doneMs = 28_000)

        assertTrue(coordinator.moveToTrash(queued))
        assertNull(catalogue().jobs.job(queued))
        assertTrue("Restore needs the checkpoint", paths.work(queued).isDirectory)
        assertEquals(JobState.QUEUED, catalogue().database.notes().note(queued)?.trashedJob?.state)
        assertNull("the running note is untouched", job(0).stopReason.get())
    }

    @Test fun restoreOfAQueuedNoteStartsTheQueue() = runBlocking {
        graph()
        consent()
        val running = note("Running")
        val queued = note("Queued")
        coordinator.transcribe(running)
        coordinator.transcribe(queued)
        job(0)
        assertTrue(coordinator.moveToTrash(queued))
        job(0).finish(JobOutcome.Done)
        coordinator.awaitIdle()
        val before = starts.get()

        assertTrue(coordinator.restore(queued))
        assertEquals("the restore starts the service", before + 1, starts.get())
        assertEquals(queued, job(1).noteId)
    }

    @Test fun trashStopsARunningJobAndKeepsItsProgress() = runBlocking {
        graph()
        consent()
        val a = note("A")
        coordinator.transcribe(a)
        val first = job(0)
        checkpoint(a, doneMs = 28_000)

        assertTrue(coordinator.moveToTrash(a))
        assertEquals(PauseReason.USER, first.stopReason.get())
        val row = requireNotNull(catalogue().jobs.job(a))
        assertEquals(JobState.PAUSED, row.state)
        assertEquals(PauseReason.USER, row.pauseReason)
        assertEquals(28_000L, row.doneMs)
        val note = requireNotNull(catalogue().database.notes().note(a))
        assertNotNull(note.trashedAt)
        assertEquals(JobState.PAUSED, note.trashedJob?.state)
        assertTrue(paths.work(a).isDirectory)
    }

    @Test fun trashKeepsAPausedJob() = runBlocking {
        graph()
        consent()
        val a = note("A")
        coordinator.transcribe(a)
        job(0).finish(JobOutcome.Paused(PauseReason.USER))
        waitUntil { catalogue().jobs.job(a)?.state == JobState.PAUSED }

        coordinator.prepareTrash(a)
        assertTrue(coordinator.moveToTrash(a))
        assertEquals(JobState.PAUSED, catalogue().jobs.job(a)?.state)
        assertEquals(JobState.PAUSED, catalogue().database.notes().note(a)?.trashedJob?.state)
    }

    // Restart and process death (architecture 5.3). --------------------------------------------------------------

    @Test fun aRestartRequeuesRunningRowsAndPausesOnesPastTheirAttempts() = runBlocking {
        val first = graph()
        val withCheckpoint = note("Checkpoint")
        val bare = note("Bare")
        val third = note("Third")
        val pausedBare = note("Paused")
        checkpoint(withCheckpoint, doneMs = 28_000)
        val jobs = catalogue().database.jobs()
        jobs.insert(row(withCheckpoint, seq = 1, JobState.RUNNING))
        jobs.insert(row(bare, seq = 2, JobState.RUNNING))
        jobs.insert(row(third, seq = 3, JobState.RUNNING, attempts = 2))
        jobs.insert(row(pausedBare, seq = 4, JobState.PAUSED, doneMs = 12_000))
        first.close()
        graphs.remove(first)

        // Process death: a new graph over the same files; the app is not visible.
        graph(paths)
        consent()
        val state = coordinator.state.first { !it.loading }
        assertEquals(listOf(withCheckpoint, bare), state.queue.map { it.noteId })
        assertEquals(28_000L, state.notes.getValue(withCheckpoint).job.doneMs)
        assertEquals(0L, state.notes.getValue(bare).job.doneMs)
        assertEquals(1, state.notes.getValue(bare).job.attempts)
        assertEquals(PauseReason.INTERRUPTED, state.notes.getValue(third).job.pauseReason)
        assertEquals(JobState.PAUSED, state.notes.getValue(pausedBare).state)
        assertEquals("a PAUSED row without a checkpoint starts from 0", 0L, state.notes.getValue(pausedBare).job.doneMs)
        waitUntil { notices.interrupted.isNotEmpty() }
        assertEquals(listOf(third), notices.interrupted.toList())
        assertEquals("nothing starts while the app is not visible", 0, starts.get())

        // ON_START: the visible app starts the queue, the checkpointed note first.
        visibility.state.value = true
        assertEquals(withCheckpoint, job(0).noteId)
        assertEquals(1, starts.get())
        job(0).finish(JobOutcome.Done)
        assertEquals(bare, job(1).noteId)
        job(1).finish(JobOutcome.Done)
        coordinator.awaitIdle()
        assertEquals("PAUSED jobs never resume on their own", 2, created.size)
    }

    @Test fun aVisibleAppStartsAWaitingQueueWithoutAnyReadOfTheCoordinator() = runBlocking {
        val first = graph()
        consent()
        val id = note("Waiting")
        catalogue().database.jobs().insert(row(id, seq = 1, JobState.QUEUED))
        first.close()
        graphs.remove(first)

        // A new process that the launcher opens: nothing here reads graph.processing before the start.
        visibility.state.value = true
        val fresh = graph(paths)
        waitUntil { starts.get() == 1 }
        assertEquals(id, job(0).noteId)
        job(0).finish(JobOutcome.Done)
        fresh.processing.awaitIdle()
        assertEquals(1, starts.get())
    }

    // Consent (architecture 5.6). --------------------------------------------------------------------------------

    @Test fun withoutConsentTranscribeEndsNeedsConsentWithoutAnyJobOrNetwork() = runBlocking {
        graph()
        val a = note("A")

        assertEquals(TranscribeOutcome.NeedsConsent, coordinator.transcribe(a))
        assertEquals(JobState.NEEDS_CONSENT, catalogue().jobs.job(a)?.state)
        assertEquals(0, starts.get())
        assertEquals(0, created.size)
        coordinator.drain().join()
        assertEquals(0, created.size)
    }

    @Test fun aRefusedStartBeforeTheStartupWorkEndedStillPostsReadyToTranscribe() = runBlocking {
        val first = graph()
        val a = note("A")
        catalogue().jobs.enqueue(a, JobKind.TRANSCRIBE)
        first.close()
        graphs.remove(first)

        // A new process: the service is refused at once, while the startup work still reads the queue.
        graph(paths, awaitReady = false)
        coordinator.onServiceRefused()
        waitUntil { notices.ready.isNotEmpty() }
        assertEquals(listOf(a), notices.ready.toList())
        assertEquals(JobState.QUEUED, catalogue().jobs.job(a)?.state)
    }

    @Test fun aQueuedJobWithoutConsentEndsNeedsConsentInTheRealJob() = runBlocking {
        // A redelivered intent after the models were deleted: the row is QUEUED, consent is gone.
        graph(realJobs = true)
        val a = note("A")
        catalogue().jobs.enqueue(a, JobKind.TRANSCRIBE)

        coordinator.drain().join()
        assertEquals(JobState.NEEDS_CONSENT, catalogue().jobs.job(a)?.state)
        assertEquals(listOf(a), notices.ready.toList())
    }

    // onTimeout (architecture 5.7). ------------------------------------------------------------------------------

    @Test fun aSystemStopPausesTheRowAtOnceAndHoldsTheQueue() = runBlocking {
        graph()
        consent()
        val a = note("A")
        val b = note("B")
        coordinator.transcribe(a)
        coordinator.transcribe(b)
        val first = job(0)
        first.pauseOnStop = false
        checkpoint(a, doneMs = 28_000)

        coordinator.systemStop(a)
        assertEquals(PauseReason.SYSTEM, first.stopReason.get())
        assertEquals(QueueHold.SYSTEM, graph.settings.queueHold.first())
        val row = requireNotNull(catalogue().jobs.job(a))
        assertEquals("every screen shows Paused at once", JobState.PAUSED, row.state)
        assertEquals(PauseReason.SYSTEM, row.pauseReason)
        assertEquals("the row has the numbers of the saved checkpoint", 28_000L, row.doneMs)

        first.finish(JobOutcome.Paused(PauseReason.SYSTEM))
        coordinator.awaitIdle()
        assertEquals(JobState.QUEUED, catalogue().jobs.job(b)?.state)
        assertEquals(1, created.size)

        // A Transcribe that is not a Resume, or a Resume from a notification, keeps the SYSTEM hold.
        coordinator.transcribe(b)
        coordinator.transcribe(a, origin = StartOrigin.NOTIFICATION)
        delay(SETTLE_MS)
        assertEquals(1, created.size)
        assertEquals(QueueHold.SYSTEM, graph.settings.queueHold.first())
        // Continue from a visible screen clears it; B was queued first.
        coordinator.continueQueue()
        assertEquals(b, job(1).noteId)
    }

    @Test fun aResumeFromAVisibleScreenClearsTheSystemHold() = runBlocking {
        graph()
        consent()
        val a = note("A")
        coordinator.transcribe(a)
        job(0).pauseOnStop = false
        coordinator.systemStop(a)
        job(0).finish(JobOutcome.Paused(PauseReason.SYSTEM))
        coordinator.awaitIdle()

        coordinator.transcribe(a, origin = StartOrigin.SCREEN)
        assertNull(graph.settings.queueHold.first())
        assertEquals(a, job(1).noteId)
    }

    @Test fun aSystemStopEndsAStandaloneDownloadAndOffersItAgain() = runBlocking {
        // The first fetch of Parakeet writes a .part file and blocks until the install is cancelled; later fetches
        // write sparse files of the pinned sizes.
        val started = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val fetches = AtomicInteger()
        val fetcher = ModelFetcher { model, directory, progress ->
            if (model == ModelCatalog.PARAKEET && fetches.getAndIncrement() == 0) {
                File(directory, PART_FILE).writeBytes(ByteArray(PART_BYTES))
                progress(PART_BYTES.toLong(), false)
                started.countDown()
                try {
                    Thread.sleep(TIMEOUT_MS)
                } catch (error: InterruptedException) {
                    interrupted.set(true)
                    throw error
                }
                fail("The stopped download went on")
            }
            model.artifacts.forEach { artifact ->
                RandomAccessFile(File(directory, artifact.name), "rw").use { it.setLength(artifact.size) }
            }
            progress(model.downloadBytes, false)
        }
        graph(fetcher = fetcher)
        // The banners follow the downloads from their first use, as the Library makes it at the start.
        val notices = graph.notices
        consent()
        val download = coordinator.downloadModels()
        assertTrue(withContext(Dispatchers.IO) { started.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) })
        withTimeout(TIMEOUT_MS) { coordinator.state.first { it.downloading } }

        // onTimeout with no running job: the service has stopped, so the download must stop too.
        coordinator.systemStop(null)
        withTimeout(TIMEOUT_MS) { download.join() }
        withTimeout(TIMEOUT_MS) { graph.installOwner.state.first { it == InstallState.Idle } }
        withTimeout(TIMEOUT_MS) { coordinator.awaitIdle() }
        assertTrue("the fetch was interrupted", interrupted.get())
        assertFalse(withTimeout(TIMEOUT_MS) { coordinator.state.first { !it.downloading } }.downloading)
        val part = File(ModelCatalog.PARAKEET.directory(paths.modelRoot), PART_FILE)
        assertTrue("the .part file stays for a Range resume", part.isFile)
        val stopped = ModelStatus.Failed(Failure.DOWNLOAD, DownloadError.INTERRUPTED.name)
        assertEquals("Settings shows the stop", stopped, graph.models.status.value[ModelPart.SPEECH])
        assertEquals(ModelStatusUi.Failed, SettingsViewModel.status(stopped))
        val failure = withTimeout(TIMEOUT_MS) { notices.facts.first { it.downloadFailure != null } }
        assertEquals("the Library offers Retry", DownloadError.INTERRUPTED, failure.downloadFailure?.error)
        assertEquals(1, fetches.get())

        // Retry starts it again; it finishes and every part is ready.
        coordinator.downloadModels().join()
        withTimeout(TIMEOUT_MS) { coordinator.awaitIdle() }
        assertEquals(ModelPart.entries.associateWith { ModelStatus.Ready }, graph.models.status.value)
    }

    // Outcomes and notifications. ----------------------------------------------------------------------------------

    @Test fun outcomesAreWrittenAndToldOnlyWhileTheAppIsHidden() = runBlocking {
        graph()
        consent()
        val failed = note("Failed")
        val consentless = note("Consent")
        val shown = note("Shown")
        listOf(failed, consentless, shown).forEach { coordinator.transcribe(it) }

        job(0).finish(JobOutcome.Failed(org.cassini.engine.job.JobFailure.CheckpointMismatch))
        job(1).finish(JobOutcome.NeedsConsent(missingBytes = 1))
        waitUntil { catalogue().jobs.job(consentless)?.state == JobState.NEEDS_CONSENT }
        visibility.state.value = true
        job(2).finish(JobOutcome.Failed(org.cassini.engine.job.JobFailure.CheckpointMismatch))
        coordinator.awaitIdle()

        assertEquals("CHECKPOINT_MISMATCH", catalogue().jobs.job(failed)?.failure)
        assertEquals(JobState.FAILED, catalogue().jobs.job(shown)?.state)
        assertEquals(listOf(failed), notices.failed.toList())
        assertEquals(listOf(consentless), notices.ready.toList())
    }

    @Test fun theForegroundNeedFollowsThePhaseAndTheApiLevel() {
        val running = { phase: JobPhase -> ProcessingState(running = RunningJob("n", "t", phase)) }
        val transcribing = running(JobPhase.Transcribing(PROGRESS, org.cassini.engine.job.DraftState.EMPTY))
        assertEquals(ForegroundNeed.NONE, ProcessingState.foregroundNeed(ProcessingState(), 35))
        assertEquals(ForegroundNeed.DATA_SYNC, ProcessingState.foregroundNeed(running(JobPhase.Downloading(null)), 35))
        assertEquals(ForegroundNeed.DATA_SYNC, ProcessingState.foregroundNeed(running(JobPhase.Starting), 35))
        assertEquals(ForegroundNeed.MEDIA_PROCESSING, ProcessingState.foregroundNeed(transcribing, 35))
        assertEquals(ForegroundNeed.DATA_SYNC, ProcessingState.foregroundNeed(transcribing, 34))
        assertEquals(ForegroundNeed.DATA_SYNC, ProcessingState.foregroundNeed(ProcessingState(downloading = true), 35))
    }

    // Draft names. -------------------------------------------------------------------------------------------------

    @Test fun aDraftConfirmRacingTheJobsLiveUpdatesLosesNeither() = runBlocking {
        graph()
        consent()
        val a = note("A")
        coordinator.transcribe(a)
        job(0)
        val storage = catalogue().jobStorage
        val live = mapOf("S2" to Match("v2", 0.6f, Match.State.SUGGESTED), "S3" to Match("v3", 0.9f, Match.State.AUTO))

        val updates = async(Dispatchers.IO) {
            repeat(RACE_ROUNDS) { storage.mutateNames(a) { it.applyLive(live, mapOf("v3" to "Marta")) } }
        }
        val confirm = async(Dispatchers.Default) { coordinator.draft(a).confirm("S1", "v1", "Luca", 0.8f) }
        updates.await()
        assertTrue(confirm.await() is DraftOutcome.Changed)

        val names = catalogue().checkpoints.names.current(a)
        assertEquals("Luca", names.labels["S1"])
        assertEquals(Match.State.CONFIRMED, names.live["S1"]?.state)
        assertEquals(Match.State.SUGGESTED, names.live["S2"]?.state)
        assertEquals("Marta", names.automatic["S3"])
        assertEquals("v1", names.remembered["S1"])
        val onDisk = File(paths.work(a), "names.json").takeIf { it.isFile }?.readText().orEmpty()
        assertTrue("names.json holds the confirmed label", onDisk.contains("Luca"))

        // From the attribute step naming is closed: the UI change is refused.
        withContext(Dispatchers.IO) { storage.closeNames(a) }
        assertEquals(DraftOutcome.Closed, coordinator.draft(a).rename("S2", "Paolo"))
        assertNull(catalogue().checkpoints.names.current(a).labels["S2"])
    }

    // Helpers. -----------------------------------------------------------------------------------------------------

    /** A PLAIN note over a copy of the FLEURS clip, as a finished recording leaves it. */
    private suspend fun note(title: String): String {
        val notes = catalogue().notes
        val id = notes.createRecording(title, 1_000, "opus")
        withContext(Dispatchers.IO) { fixture.copyTo(File(paths.note(id), "capture.opus")) }
        assertTrue(notes.finishRecording(id, DURATION_MS))
        return id
    }

    /** A checkpoint of [noteId]'s capture that validates (as the :data CheckpointFixtures write it). */
    private suspend fun checkpoint(noteId: String, doneMs: Long) = withContext(Dispatchers.IO) {
        val checkpoints = catalogue().checkpoints
        checkpoints.openWorkBlocking(noteId)
        val words = listOf(Word("S1", 0, 400, "uno"), Word("S2", 500, 900, "due"))
        val state = CheckpointState(
            source = "capture.opus",
            signature = CheckpointState.signature(File(paths.note(noteId), "capture.opus")),
            model = ModelCatalog.PARAKEET_REVISION,
            speakers = ModelCatalog.diarizationModel.sha256,
            recognition = RecognitionState(1_200, IncrementalState(0, doneMs * 16, FloatArray(4), words)),
            encoder = null,
            diarizer = null,
            speakerPrefix = "S",
            speakerFailure = false,
            clipSamples = 0,
            voiceClips = null,
            positionSamples = doneMs * 16,
            doneMs = doneMs,
            durationMs = 60_000,
            elapsedMs = 9_000,
        )
        checkpoints.saveBlocking(noteId, state)
    }

    private fun row(id: String, seq: Long, state: JobState, attempts: Int = 0, doneMs: Long = 0) = JobEntity(
        noteId = id,
        seq = seq,
        kind = JobKind.TRANSCRIBE,
        state = state,
        pauseReason = if (state == JobState.PAUSED) PauseReason.USER else null,
        doneMs = doneMs,
        attempts = attempts,
        enqueuedAt = 1,
        updatedAt = 1,
    )

    private suspend fun runningRows(): List<String> =
        catalogue().jobs.queue().first().filter { it.job.state == JobState.RUNNING }.map { it.job.noteId }

    /** The [index]th job the coordinator created, once it runs. */
    private suspend fun job(index: Int): FakeJob {
        waitUntil { created.size > index }
        return created[index].also { it.started.await() }
    }

    private suspend fun waitUntil(condition: suspend () -> Boolean) = withTimeout(TIMEOUT_MS) {
        while (!condition()) delay(POLL_MS)
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 20L
        const val SETTLE_MS = 300L
        const val DURATION_MS = 4_000L
        const val RACE_ROUNDS = 200
        const val PART_FILE = "encoder.int8.onnx.part"
        const val PART_BYTES = 4_096

        val PROGRESS = org.cassini.engine.job.RunningProgress(
            headline = org.cassini.engine.job.ProgressHeadline.FINDING_WORDS_AND_SPEAKERS,
            rangeStartMs = 0,
            rangeEndMs = 0,
            percent = 40,
            step = org.cassini.engine.job.Step.WORDS_AND_VOICES,
            matchingSkipped = false,
            timeLeft = org.cassini.engine.job.TimeLeft.Calculating,
            doneMs = 1_000,
            totalMs = 4_000,
            battery = org.cassini.engine.job.BatteryLine.Unavailable,
            words = 3,
            lowMemory = false,
        )
    }
}
