@file:OptIn(ExperimentalCoroutinesApi::class)

package org.cassini.engine.job

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.cassini.core.Failure
import org.cassini.core.asr.TranscriptionProgress.Stage
import org.cassini.core.checkpoint.CheckpointNames
import org.cassini.core.checkpoint.CheckpointState
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.cassini.core.library.PauseReason
import org.cassini.core.models.ModelPart
import org.cassini.core.models.ModelPlan
import org.cassini.core.speakers.Match
import org.cassini.core.speakers.SpeakerPrint
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TranscriptionJobTest {
    @get:Rule val temp = TemporaryFolder()

    private val inference: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "test-inference") }.asCoroutineDispatcher()

    @After fun closeInference() = inference.close()

    // Ids as Diarization.assign makes them with the injected prefix: speaker k of the script is diar_test_<k+1>.
    private val sp1 = "diar_test_1"
    private val sp2 = "diar_test_2"
    private val sp3 = "diar_test_3"

    @Test fun decodesIn28SecondWindowsAndSavesOneCheckpointPerWindow() = runBlocking {
        val h = Harness()
        val (outcome, events) = h.run()
        assertEquals(JobOutcome.Done, outcome)
        assertEquals(listOf(0L, 26_000L, 52_000L), h.script.windows)
        // As in 0.1.0: one checkpoint at the first sample block, one after each window, and a complete one after the
        // final partial window.
        assertEquals(listOf(0L, 28_000L, 54_000L, 70_000L), h.storage.saved.map { it.doneMs })
        val checkpointed = events.filterIsInstance<JobEvent.Checkpointed>().map { it.doneMs }
        assertEquals(listOf(0L, 28_000L, 54_000L, 70_000L), checkpointed)
        assertTrue(events.first() is JobEvent.Started)
        assertEquals(JobEvent.Ended(JobOutcome.Done), events.last())
        val stages = events.filterIsInstance<JobEvent.StageChanged>().map { it.stage }.distinct()
        assertEquals(
            listOf(
                Stage.PREPARING,
                Stage.WORDS,
                Stage.SPEAKERS,
                Stage.MATCHING,
                Stage.SAVING,
            ),
            stages,
        )
        assertTrue(events.indexOf(JobEvent.NamingClosed) > events.indexOfLast { it is JobEvent.Checkpointed })
        val sealed = h.sealed()
        assertEquals((0 until 70).map { "w$it" }, sealed.selected(null)!!.transcript!!.words.map { it.text })
        val checkpoints = listOf("checkpoint 0", "checkpoint 28000", "checkpoint 54000", "checkpoint 70000")
        assertEquals(checkpoints + listOf("pending", "marker", "adopt"), h.log)
        assertEquals(1, h.storage.adoptions.size)
        assertFalse(File(h.noteDir, "document.opus").exists())
    }

    @Test fun checkpointsOnlyCountWhatWasFsynced() = runBlocking {
        val h = Harness(source = "source.mp3")
        h.storage.onCheckpoint = { state ->
            val sink = h.script.sinks.last()
            assertEquals(state.encoder!!.length, File(h.storage.work, "audio.opus.part").length())
            assertTrue(sink.syncs > 0)
            assertTrue(File(h.storage.work, "clips.pcm").length() >= state.clipSamples * 4L)
            assertEquals("source.mp3", state.source)
            assertEquals(CheckpointState.signature(h.audio), state.signature)
        }
        assertEquals(JobOutcome.Done, h.run().first)
        val sink = h.script.sinks.single()
        assertTrue(sink.finished && sink.closed)
        assertEquals(4, h.storage.saved.size)
    }

    @Test fun stopAtAWindowThenResumeEqualsAnUninterruptedRun() = runBlocking {
        val whole = Harness(source = "source.mp3").also { assertEquals(JobOutcome.Done, it.run().first) }

        val stopped = Harness(source = "source.mp3")
        val first = stopped.job()
        stopped.script.beforeDecode = { start -> if (start == 26_000L) first.stop() }
        val (outcome, events) = stopped.run(first)
        assertEquals(JobOutcome.Paused(PauseReason.USER), outcome)
        assertTrue(JobEvent.Stopping in events)
        assertEquals(54_000L, stopped.storage.saved.last().doneMs)
        assertTrue(stopped.script.recognizers.single().closed)
        assertTrue(stopped.script.diarizers.single().closed)
        assertTrue(stopped.script.sinks.single().closed)
        assertFalse(NativeBudget.isHeld)
        assertTrue(stopped.storage.adoptions.isEmpty())

        stopped.script.beforeDecode = {}
        val checkpoint = stopped.storage.saved.last()
        val (resumedOutcome, resumedEvents) = stopped.run()
        assertEquals(JobOutcome.Done, resumedOutcome)
        // The tracker starts the resumed draft from the checkpoint's words and elapsed time.
        val restored = resumedEvents.filterIsInstance<JobEvent.Started>().single().restored!!
        assertEquals(checkpoint.recognition.chunks.words, restored.words)
        assertEquals(checkpoint.elapsedMs to checkpoint.doneMs, restored.elapsedMs to restored.doneMs)
        assertEquals(stopped.storage.saved.dropLast(1).last().positionSamples, stopped.audioSource.starts.last())
        assertEquals(listOf(0L, 26_000L, 52_000L), stopped.script.windows)

        val expected = whole.sealed().selected(null)!!
        val actual = stopped.sealed().selected(null)!!
        assertEquals(expected.transcript, actual.transcript)
        val speakers = { h: Harness -> h.sealed().manifest!!.getJSONArray("speakers").toString() }
        assertEquals(speakers(whole), speakers(stopped))
        val wholeRecord = whole.provenance()
        val resumedRecord = stopped.provenance()
        assertEquals(0L, wholeRecord.getLong("x-resumedFromMs"))
        assertEquals(54_000L, resumedRecord.getLong("x-resumedFromMs"))
        wholeRecord.remove("x-resumedFromMs")
        resumedRecord.remove("x-resumedFromMs")
        assertTrue("$wholeRecord\n$resumedRecord", sameJson(wholeRecord, resumedRecord))
    }

    @Test fun cancelInsideASlowNativeCallKeepsThePreviousCheckpointAndReleasesHandles() = runBlocking {
        val h = Harness()
        val entered = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val finishedNativeCall = AtomicBoolean()
        h.script.beforeDecode = { start ->
            if (start == 26_000L) {
                entered.countDown()
                // A native call cannot be interrupted: it notices nothing and runs to its end.
                val began = System.nanoTime()
                while (!Thread.currentThread().isInterrupted && System.nanoTime() - began < 10_000_000_000L) {
                    Thread.onSpinWait()
                }
                interrupted.set(Thread.currentThread().isInterrupted)
                finishedNativeCall.set(true)
            }
        }
        val job = h.job()
        val running = async(Dispatchers.Default) { job.run() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        running.cancel()
        withTimeout(10_000) { running.join() }
        assertTrue(interrupted.get())
        assertTrue(finishedNativeCall.get())
        assertEquals(JobEvent.Ended(JobOutcome.Cancelled), job.events.toList().last())
        assertEquals(listOf(0L, 28_000L), h.storage.saved.map { it.doneMs })
        assertEquals(28_000L, JSONObject(h.storage.checkpointJson!!).getLong("doneMs"))
        assertTrue(h.script.recognizers.single().closed)
        assertTrue(h.script.diarizers.single().closed)
        assertFalse(NativeBudget.isHeld)
        assertFalse(h.storage.marker.exists())
        assertTrue(h.storage.adoptions.isEmpty())
    }

    @Test fun javaOutOfMemoryPausesWithMemoryAndKeepsTheCheckpoint() = runBlocking {
        val h = Harness()
        h.script.beforeDecode = { start -> if (start == 26_000L) throw OutOfMemoryError("test") }
        val (outcome, _) = h.run()
        assertEquals(JobOutcome.Paused(PauseReason.MEMORY), outcome)
        assertEquals(listOf(0L, 28_000L), h.storage.saved.map { it.doneMs })
        assertTrue(h.script.recognizers.single().closed)
        assertTrue(h.script.diarizers.single().closed)
        assertFalse(NativeBudget.isHeld)
    }

    @Test fun withoutConsentNothingIsInstalledAndNothingIsDecoded() = runBlocking {
        val h = Harness()
        h.models.readyParts = emptySet()
        h.models.consent = false
        val (outcome, events) = h.run()
        assertEquals(JobOutcome.NeedsConsent(ModelPlan.of { false }.totalBytes), outcome)
        assertTrue(h.models.installs.isEmpty())
        assertTrue(h.audioSource.starts.isEmpty())
        assertTrue(h.storage.saved.isEmpty())
        assertTrue(events.none { it is JobEvent.Downloading })
    }

    @Test fun missingModelsAreInstalledWithConsentAndADownloadFailureFailsTheJob() = runBlocking {
        val installed = Harness()
        installed.models.readyParts = setOf(ModelPart.SPEECH)
        val (outcome, events) = installed.run()
        assertEquals(JobOutcome.Done, outcome)
        assertEquals(listOf(setOf(ModelPart.SPEAKERS, ModelPart.VOICES)), installed.models.installs)
        assertTrue(events.any { it is JobEvent.Downloading && it.progress?.part == ModelPart.SPEAKERS })

        val failed = Harness()
        failed.models.readyParts = emptySet()
        failed.models.outcome = InstallOutcome.Failed(ModelPart.SPEECH, DownloadError.NO_CONNECTION, 1)
        val noConnection = JobFailure.Download(DownloadError.NO_CONNECTION, ModelPart.SPEECH)
        assertEquals(JobOutcome.Failed(noConnection), failed.run().first)
        assertTrue(failed.audioSource.starts.isEmpty())
    }

    @Test fun aStopDuringTheDownloadStopsWaitingAndPauses() = runBlocking {
        val h = Harness()
        h.models.readyParts = emptySet()
        h.models.hang = true
        val job = h.job()
        val running = async(Dispatchers.Default) { h.run(job) }
        assertTrue(h.models.installing.await(10, TimeUnit.SECONDS))
        job.stop(PauseReason.SYSTEM)
        val (outcome, events) = running.await()
        assertEquals(JobOutcome.Paused(PauseReason.SYSTEM), outcome)
        assertTrue(events.any { it is JobEvent.Downloading })
        assertTrue(h.audioSource.starts.isEmpty())
        assertTrue(h.storage.saved.isEmpty())
    }

    @Test fun aCheckpointThatNoLongerMatchesFailsTheJob() = runBlocking {
        val h = Harness()
        h.storage.mismatch = true
        assertEquals(JobOutcome.Failed(JobFailure.CheckpointMismatch), h.run().first)
        assertTrue(h.audioSource.starts.isEmpty())
    }

    @Test fun theJobNeverWritesDocumentOpus() = runBlocking {
        val h = Harness(source = "document.opus")
        // Transcribe again: the note's audio is a sealed document whose speaker "old_a" the user called Anna.
        val document = CassiniDocument.create(
            FIXTURE_OPUS,
            Transcript(listOf(Word("old_a", 0, 500, "ciao"))),
            "Weekly sync",
            JSONObject().put("source", "test"),
            null,
            mapOf("old_a" to "Anna"),
            File(temp.root, "document.tmp"),
        )
        File(temp.root, "document.tmp").copyTo(h.audio, overwrite = true)
        h.audio.setLastModified(1_700_000_000_000L)
        h.storage.existing = document
        h.voices.noteVoices = mapOf("old_a" to SpeakerPrint(axis(1), 30.0, null))
        val before = sha256(h.audio) to h.audio.lastModified()

        assertEquals(JobOutcome.Done, h.run().first)

        assertEquals(before, sha256(h.audio) to h.audio.lastModified())
        val sealed = h.sealed()
        assertEquals(2, sealed.variants.size)
        val meeting = { doc: CassiniDocument -> doc.manifest!!.getJSONObject("meeting").getString("id") }
        assertEquals(meeting(document), meeting(sealed))
        // The name carries over through the note-local voice; that match is stored as no match, never a live name.
        assertEquals("Anna", sealed.speakerLabel(sp1))
        assertEquals("Anna", h.storage.adoptions.single().labels[sp1])
        assertNull(h.voices.pending.single().prints.getValue(sp1).match)
        assertTrue(h.storage.jobNames.none { sp1 in it.automatic || sp1 in it.live })
    }

    @Test fun aCrashAfterTheMarkerLeavesAnAdoptableState() = runBlocking {
        val h = Harness()
        h.voices.people = listOf(voice("marco", "Marco", axis(2)))
        h.storage.adoptFailure = IOException("Process died")
        val (outcome, _) = h.run()
        assertEquals(JobOutcome.Failed(JobFailure.Processing(Failure.SAVE)), outcome)
        assertTrue(h.storage.marker.isFile)
        val sealed = CassiniDocument.read(File(h.storage.work, "sealed.opus"))
        assertEquals("ok", sealed.state)
        assertEquals(70, sealed.selected(null)!!.transcript!!.words.size)
        // The pending prints are complete before the marker; the checkpoint stays for a re-seal if adopt cannot finish.
        assertEquals(listOf("pending", "marker", "adopt"), h.log.takeLast(3))
        assertEquals(sealed.defaultId, h.voices.pending.single().variantId)
        assertEquals(3L, h.voices.pending.single().generation)
        assertNotNull(h.storage.checkpointJson)
    }

    @Test fun aStopDuringFinalizationDeletesOnlyTheSealedFileAndKeepsTheCheckpoint() = runBlocking {
        val h = Harness()
        val job = h.job()
        h.storage.duringSeal = { job.stop() }
        assertEquals(JobOutcome.Paused(PauseReason.USER), h.run(job).first)
        assertFalse(File(h.storage.work, "sealed.opus").exists())
        assertFalse(h.storage.marker.exists())
        assertEquals(70_000L, JSONObject(h.storage.checkpointJson!!).getLong("doneMs"))
        assertTrue(h.storage.adoptions.isEmpty())
        assertFalse(File(h.noteDir, "document.opus").exists())

        // Resuming the complete checkpoint decodes nothing again.
        h.storage.duringSeal = {}
        h.storage.namingClosed = false
        assertEquals(JobOutcome.Done, h.run().first)
        assertEquals(listOf(0L, 26_000L, 52_000L), h.script.windows)
        assertEquals(1, h.storage.adoptions.size)
    }

    @Test fun aNotLucaArrivingDuringAWindowIsNotOverwrittenByTheJob() = runBlocking {
        val h = Harness()
        h.voices.people = listOf(voice("luca", "Luca", near(1, 0.6f)))
        // The second print extraction is the next live match (in the last window): the user answers "Not Luca" while it
        // runs, after the job read the names and before it writes its result.
        h.script.beforePrints = { call ->
            if (call == 2) {
                assertEquals(Match.State.SUGGESTED, h.storage.names.get().live[sp1]?.state)
                h.storage.names.updateAndGet { it.reject(sp1, "luca", "Luca") }
            }
        }
        assertEquals(JobOutcome.Done, h.run().first)
        val names = h.storage.names.get()
        assertEquals(setOf("luca"), names.rejected[sp1])
        assertNull(names.live[sp1])
        assertTrue(h.storage.jobNames.size >= 3)
        assertTrue(h.storage.jobNames.drop(2).all { it.rejected[sp1] == setOf("luca") && it.live[sp1] == null })
    }

    @Test fun aRejectedVoiceIsNeverSuggestedAgain() = runBlocking {
        val h = Harness()
        h.voices.people = listOf(voice("luca", "Luca", near(1, 0.6f)))
        val rejectedAt = AtomicLong(-1)
        h.script.beforeDecode = { start ->
            if (start == 26_000L) {
                h.storage.names.updateAndGet { it.reject(sp1, "luca", "Luca") }
                rejectedAt.set(h.storage.jobNames.size.toLong())
            }
        }
        assertEquals(JobOutcome.Done, h.run().first)
        val later = h.storage.jobNames.drop(rejectedAt.get().toInt())
        assertTrue(later.isNotEmpty())
        assertTrue(later.all { it.live[sp1]?.voiceId != "luca" })
        // Nor in the final matching: the print is stored without a match and the label stays a placeholder.
        assertNull(h.voices.pending.single().prints.getValue(sp1).match)
        assertEquals("Speaker 1", h.storage.adoptions.single().labels[sp1])
    }

    @Test fun labelsFollowDraftNameThenAutoMatchThenPlaceholder() = runBlocking {
        val h = Harness()
        h.voices.people = listOf(voice("marco", "Marco", axis(2)), voice("luca", "Luca", axis(1)))
        h.script.beforeDecode = { start ->
            if (start == 26_000L) h.storage.names.updateAndGet { it.rename(sp1, "Chiara") }
        }
        assertEquals(JobOutcome.Done, h.run().first)
        val labels = h.storage.adoptions.single().labels
        assertEquals(mapOf(sp1 to "Chiara", sp2 to "Marco", sp3 to "Speaker 3"), labels)
        assertEquals("Marco", h.storage.jobNames.last().automatic[sp2])
        val sealed = h.sealed()
        assertEquals(labels, listOf(sp1, sp2, sp3).associateWith { sealed.speakerLabel(it) })
    }

    @Test fun aDiarizerFailureDegradesToOneVoice() = runBlocking {
        val h = Harness()
        h.script.diarizerFailsFrom = 26_000L
        val (outcome, events) = h.run()
        assertEquals(JobOutcome.Done, outcome)
        assertTrue(JobEvent.SpeakersUnavailable in events)
        assertTrue(h.storage.saved.last().speakerFailure)
        val words = h.sealed().selected(null)!!.transcript!!.words
        assertTrue(words.filter { it.startMs >= 30_000 }.all { it.speaker == sp1 })
    }

    @Test fun provenanceEqualsThe010Record() = runBlocking {
        val h = Harness()
        val people = listOf(voice("marco", "Marco", axis(2)), voice("luca", "Luca", near(1, 0.6f)))
        h.voices.people = people
        // A draft merge, so the record also carries x-speakerMerges.
        h.script.beforeDecode = { start ->
            if (start == 52_000L) h.storage.names.updateAndGet { it.merge(sp3, sp1) }
        }
        assertEquals(JobOutcome.Done, h.run().first)
        val merges = mapOf(sp3 to sp1)
        val transcript = h.sealed().selected(null)!!.transcript!!
        val diarizer = h.script.diarizers.single()
        val computed = h.script.lastPrints
        val golden = Golden010.provenance(
            transcript = transcript,
            elapsed = h.script.recognizers.single().inferenceMs,
            segmentation = h.script.recognizers.single().segmentation,
            resumedDoneMs = null,
            turns = diarizer.turns,
            diarizationMs = diarizer.elapsedMs,
            computed = computed,
            knownVoices = people,
            voiceprintReady = true,
            merges = merges,
        )
        val actual = h.provenance()
        assertTrue("expected $golden\nactual   $actual", sameJson(golden, actual))
        assertTrue(listOf("x-speakerMerges", "x-speakerIdentification", "x-speakerDiarization").all(actual::has))
        assertEquals(Golden010.labels(transcript, emptyMap(), computed, people), h.storage.adoptions.single().labels)
    }

    @Test fun provenanceWithoutTheDiarizerEqualsThe010Record() = runBlocking {
        val h = Harness()
        h.models.readyParts = setOf(ModelPart.SPEECH)
        h.models.outcome = InstallOutcome.Installed(skipped = setOf(ModelPart.SPEAKERS, ModelPart.VOICES))
        assertEquals(JobOutcome.Done, h.run().first)
        val transcript = h.sealed().selected(null)!!.transcript!!
        assertTrue(transcript.words.all { it.speaker == sp1 })
        val golden = Golden010.provenance(
            transcript = transcript,
            elapsed = h.script.recognizers.single().inferenceMs,
            segmentation = h.script.recognizers.single().segmentation,
            resumedDoneMs = null,
            turns = null,
            diarizationMs = 0,
            computed = emptyMap(),
            knownVoices = emptyList(),
            voiceprintReady = false,
            merges = emptyMap(),
        )
        val actual = h.provenance()
        assertTrue("expected $golden\nactual   $actual", sameJson(golden, actual))
        assertTrue(h.script.diarizers.isEmpty())
    }

    @Test fun theTrackerTurnsTheJobEventsIntoMonotonicProgress() {
        val events = runBlocking { Harness().run().second }
        runTest {
            val channel = Channel<JobEvent>(Channel.UNLIMITED)
            val tracker = ProgressTracker(
                DraftSources({ CheckpointNames(emptyMap(), emptyMap()) }, { emptyMap() }, { "Speaker $it" }),
                finalizationMs = 0,
                battery = { null },
                clock = { testScheduler.currentTime },
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            val outcome = backgroundScope.async { tracker.run(channel) }
            val seen = mutableListOf<RunningProgress>()
            events.forEach { event ->
                advanceTimeBy(700)
                channel.trySend(event)
                runCurrent()
                (tracker.phase.value as? JobPhase.Transcribing)?.let { seen += it.progress }
            }
            channel.close()
            assertEquals(JobOutcome.Done, outcome.await())
            val percents = seen.mapNotNull { it.percent }
            assertEquals(percents.sorted(), percents)
            assertEquals(seen.map { it.step }.sorted(), seen.map { it.step })
            assertEquals(100, seen.last().percent)
            assertEquals(70, seen.last().words)
            assertTrue(seen.dropLast(1).all { (it.percent ?: 0) <= 99 })
            val headlines = seen.map { it.headline }.distinct()
            assertTrue(headlines.toString(), ProgressHeadline.FINDING_WORDS_AND_SPEAKERS in headlines)
            assertTrue(headlines.toString(), ProgressHeadline.SAVING in headlines)
            val firstWords = headlines.indexOf(ProgressHeadline.FINDING_WORDS_AND_SPEAKERS)
            assertTrue(ProgressHeadline.READING_AUDIO !in headlines.drop(firstWords))
            assertFalse(seen.last().matchingSkipped)
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private inner class Harness(source: String = "capture.opus", totalMs: Long = 70_000) {
        val noteDir: File = temp.newFolder("notes-$source-${System.nanoTime()}", "note-1")
        val audio = File(noteDir, source)
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val script = Script()
        val storage = FakeStorage(noteDir, audio, log)
        val voices = FakeVoices(log)
        val models = FakeModels()
        val audioSource = FakeAudio(totalMs)
        private val clock = AtomicLong(1_000_000_000L)
        private val engines = JobEngines(
            recognizers = { restored, callbacks ->
                FakeRecognizer(restored, callbacks, script).also { script.recognizers += it }
            },
            diarizers = { restored -> FakeDiarizer(restored, script).also { script.diarizers += it } },
            prints = FakePrints(script),
            audio = audioSource,
            encoders = { file, restored -> FakeSink(file, restored).also { script.sinks += it } },
        )

        init {
            if (source.endsWith(".opus")) {
                FIXTURE_OPUS.copyTo(audio)
            } else {
                audio.writeBytes(ByteArray(64) { it.toByte() })
            }
        }

        fun job() = TranscriptionJob(
            "note-1",
            storage,
            voices,
            models,
            engines,
            JobEnvironment(
                placeholderLabel = { "Speaker $it" },
                inference = inference,
                io = Dispatchers.IO,
                nanoTime = { clock.addAndGet(1_000_000L) },
                speakerPrefix = { "diar_test_" },
                warn = { _, _ -> },
            ),
        )

        suspend fun run(job: TranscriptionJob = job()): Pair<JobOutcome, List<JobEvent>> {
            val outcome = withTimeout(60_000) { job.run() }
            return outcome to job.events.toList()
        }

        fun sealed(): CassiniDocument =
            CassiniDocument.read(File(storage.work, "sealed.opus")).also { assertEquals("ok", it.state) }

        fun provenance(): JSONObject {
            val document = sealed()
            val speech = document.manifest!!.getJSONObject("provenance").getJSONObject("speechToText")
            return speech.getJSONObject(document.defaultId!!)
        }
    }

    /** Order-insensitive structural equality; the android.jar org.json the tests compile against has no `similar`. */
    private fun sameJson(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> {
            val keys = a.keys().asSequence().toSet()
            keys == b.keys().asSequence().toSet() && keys.all { sameJson(a.get(it), b.get(it)) }
        }
        a is org.json.JSONArray && b is org.json.JSONArray ->
            a.length() == b.length() && (0 until a.length()).all { sameJson(a.get(it), b.get(it)) }
        a is Number && b is Number -> a.toString() == b.toString() || a.toDouble() == b.toDouble()
        else -> a == b
    }

    private fun sha256(file: File) =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
