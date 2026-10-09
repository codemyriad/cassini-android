@file:OptIn(ExperimentalCoroutinesApi::class)

package org.cassini.engine.job

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.cassini.core.asr.BatteryProjection
import org.cassini.core.asr.TranscriptionProgress
import org.cassini.core.asr.TranscriptionProgress.Stage
import org.cassini.core.checkpoint.CheckpointNames
import org.cassini.core.format.Word
import org.cassini.core.library.PauseReason
import org.cassini.core.models.ModelPart
import org.cassini.core.speakers.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class ProgressTrackerTest {
    // Phases ----------------------------------------------------------------------------------------------------

    @Test fun phaseFollowsTheJobBeforeTranscribing() = runTest {
        val t = Tracked(this)
        assertEquals(JobPhase.Starting, t.phase)
        t.send(JobEvent.Downloading(null))
        assertEquals(JobPhase.Downloading(null), t.phase)
        val progress = DownloadProgress(ModelPart.SPEECH, 10, 100, verifying = false)
        t.send(JobEvent.Downloading(progress))
        assertEquals(JobPhase.Downloading(progress), t.phase)
        t.send(JobEvent.WaitingForPrevious)
        assertEquals(JobPhase.WaitingForPrevious, t.phase)
        t.send(started())
        assertTrue(t.phase is JobPhase.Transcribing)
        t.send(JobEvent.Ended(JobOutcome.Failed(JobFailure.CheckpointMismatch)))
        t.close()
        assertEquals(JobOutcome.Failed(JobFailure.CheckpointMismatch), t.outcome.await())
    }

    @Test fun stoppingStaysUntilTheEndAndStopsTheTicks() = runTest {
        val t = Tracked(this)
        t.send(started(totalMs = 70_000))
        advanceTimeBy(1_500)
        t.send(JobEvent.Stopping)
        assertEquals(JobPhase.Stopping, t.phase)
        val reads = t.battery.reads.size
        t.send(JobEvent.WindowDone(28_000, 70_000, words(0, 28)))
        t.send(JobEvent.StageChanged(Stage.SAVING))
        advanceTimeBy(30_000)
        assertEquals(JobPhase.Stopping, t.phase)
        assertEquals(reads, t.battery.reads.size)
        t.send(JobEvent.Ended(JobOutcome.Paused(PauseReason.USER)))
        t.close()
        assertEquals(JobPhase.Stopping, t.phase)
        assertEquals(JobOutcome.Paused(PauseReason.USER), t.outcome.await())
    }

    @Test fun aStopBeforeInferenceKeepsStopping() = runTest {
        val t = Tracked(this)
        t.send(JobEvent.Stopping)
        t.send(started())
        advanceTimeBy(5_000)
        assertEquals(JobPhase.Stopping, t.phase)
        assertTrue(t.battery.reads.isEmpty())
        t.close()
    }

    @Test fun publishesOncePerSecondWhileTranscribing() = runTest {
        val t = Tracked(this)
        t.send(started(totalMs = 70_000))
        advanceTimeBy(300)
        t.send(JobEvent.WindowDone(28_000, 70_000, words(0, 28)))
        advanceTimeBy(699) // 999 ms after Started: no tick yet
        assertEquals(0L, t.progress.doneMs)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(28_000L, t.progress.doneMs)
        assertEquals(28, t.progress.words)
        t.close()
    }

    @Test fun refreshPublishesTheNamesAtOnce() = runTest {
        val t = Tracked(this)
        t.send(started(totalMs = 70_000))
        t.send(JobEvent.WindowDone(28_000, 70_000, words(0, 28)))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("Speaker 1", t.draft.labels["a"])
        advanceTimeBy(200)
        t.names.set(t.names.get().rename("a", "Ada"))
        t.tracker.refresh()
        runCurrent()
        assertEquals("Ada", t.draft.labels["a"])
        t.close()
    }

    // Elapsed time and resume -----------------------------------------------------------------------------------

    @Test fun elapsedIsTheCheckpointElapsedPlusTheRunTimeAndBatteryIsReadEvery10Seconds() = runTest {
        val t = Tracked(this)
        t.send(started(totalMs = 70_000, restored = JobEvent.Restored(28_000, 70_000, words(0, 28), 50_000)))
        advanceTimeBy(25_000)
        runCurrent()
        assertEquals(listOf(50_000L, 60_000L, 70_000L), t.battery.reads)
        t.close()
    }

    @Test fun restoreStartsFromTheCheckpointAndRecalculates() = runTest {
        val t = Tracked(this)
        val restored = JobEvent.Restored(28_000, 70_000, words(0, 28), 50_000)
        t.send(started(totalMs = 70_000, restored = restored))
        assertEquals(40, t.progress.percent)
        assertEquals(28, t.progress.words)
        assertEquals(28_000L, t.progress.doneMs)
        assertEquals(28, t.draft.words.size)
        assertEquals(26_000L, t.draft.tentativeFromMs)
        assertEquals(Step.READING, t.progress.step)
        // The first window after a resume includes the model reload: it does not count towards the 3 windows.
        windows(t, 54_000, 62_000)
        assertEquals(TimeLeft.Calculating, t.progress.timeLeft)
        windows(t, 66_000)
        assertTrue(t.progress.timeLeft is TimeLeft.Range)
        t.close()
    }

    // Stepper and headline --------------------------------------------------------------------------------------

    @Test fun theStepIsMonotonicAndTheHeadlineNeverGoesBackToReading() = runTest {
        val t = Tracked(this)
        t.send(started(totalMs = 120_000))
        t.send(JobEvent.Reading(10_000, 120_000))
        tick()
        assertEquals(Step.READING, t.progress.step)
        assertEquals(ProgressHeadline.READING_AUDIO, t.progress.headline)
        assertEquals(10_000L, t.progress.rangeEndMs)
        t.send(JobEvent.StageChanged(Stage.PREPARING, 0, 28_000))
        tick()
        assertEquals(Step.PREPARING, t.progress.step)
        assertEquals(ProgressHeadline.PREPARING, t.progress.headline)
        t.send(JobEvent.StageChanged(Stage.WORDS, 0, 28_000))
        t.send(JobEvent.StageChanged(Stage.SPEAKERS, 0, 28_000))
        t.send(JobEvent.WindowDone(28_000, 120_000, words(0, 28)))
        // Reading the next window, and a stray earlier stage, never move the stepper or the headline back.
        t.send(JobEvent.Reading(40_000, 120_000))
        t.send(JobEvent.StageChanged(Stage.PREPARING, 26_000, 54_000))
        repeat(3) {
            tick()
            assertEquals(Step.WORDS_AND_VOICES, t.progress.step)
            assertEquals(ProgressHeadline.FINDING_WORDS_AND_SPEAKERS, t.progress.headline)
            assertEquals(0L to 28_000L, t.progress.rangeStartMs to t.progress.rangeEndMs)
        }
        t.send(JobEvent.StageChanged(Stage.MATCHING))
        tick()
        assertEquals(Step.MATCHING, t.progress.step)
        assertEquals(ProgressHeadline.MATCHING_VOICES, t.progress.headline)
        assertFalse(t.progress.matchingSkipped)
        t.send(JobEvent.StageChanged(Stage.SAVING))
        t.send(JobEvent.StageChanged(Stage.WORDS))
        tick()
        assertEquals(Step.SAVING, t.progress.step)
        assertEquals(ProgressHeadline.SAVING, t.progress.headline)
        assertFalse(t.progress.matchingSkipped)
        t.close()
    }

    @Test fun theHeadlineFollowsTheStepperWithinTheStageDebounce() = runTest {
        val t = Tracked(this)
        t.send(started(totalMs = 70_000))
        t.send(JobEvent.Reading(27_000, 70_000))
        tick()
        assertEquals(ProgressHeadline.READING_AUDIO, t.progress.headline)
        // 100 ms later the core debounce still shows Reading; the stepper is already at Words & voices.
        t.send(JobEvent.StageChanged(Stage.WORDS, 0, 28_000))
        advanceTimeBy(100)
        t.tracker.refresh()
        runCurrent()
        assertEquals(Step.WORDS_AND_VOICES, t.progress.step)
        assertEquals(ProgressHeadline.FINDING_WORDS_AND_SPEAKERS, t.progress.headline)
        assertEquals(0L to 28_000L, t.progress.rangeStartMs to t.progress.rangeEndMs)
        // Within one step the debounce holds: a SPEAKERS stage 100 ms long keeps the WORDS window.
        tick()
        t.send(JobEvent.StageChanged(Stage.SPEAKERS, 26_000, 54_000))
        advanceTimeBy(100)
        t.tracker.refresh()
        runCurrent()
        assertEquals(0L to 28_000L, t.progress.rangeStartMs to t.progress.rangeEndMs)
        t.close()
    }

    @Test fun findingWordsWithoutADiarizer() = runTest {
        val t = Tracked(this)
        t.send(started(totalMs = 70_000, speakers = false))
        t.send(JobEvent.StageChanged(Stage.WORDS, 0, 28_000))
        tick()
        assertEquals(ProgressHeadline.FINDING_WORDS, t.progress.headline)
        assertTrue(t.progress.matchingSkipped)
        t.close()
    }

    @Test fun speakersUnavailableSwitchesToFindingWords() = runTest {
        val t = Tracked(this)
        t.send(started(totalMs = 70_000))
        t.send(JobEvent.StageChanged(Stage.SPEAKERS, 0, 28_000))
        tick()
        assertEquals(ProgressHeadline.FINDING_WORDS_AND_SPEAKERS, t.progress.headline)
        assertFalse(t.progress.matchingSkipped)
        t.send(JobEvent.SpeakersUnavailable)
        t.send(JobEvent.StageChanged(Stage.WORDS, 26_000, 54_000))
        tick()
        assertEquals(ProgressHeadline.FINDING_WORDS, t.progress.headline)
        assertTrue(t.progress.matchingSkipped)
        t.close()
    }

    @Test fun matchingIsSkippedWithoutTheVoiceprintModelOrWhenSavingBeginsWithoutIt() = runTest {
        val noModel = Tracked(this)
        noModel.send(started(totalMs = 70_000, prints = false))
        assertTrue(noModel.progress.matchingSkipped)
        noModel.close()

        val unused = Tracked(this)
        unused.send(started(totalMs = 70_000))
        assertFalse(unused.progress.matchingSkipped)
        unused.send(JobEvent.StageChanged(Stage.SAVING))
        tick()
        assertTrue(unused.progress.matchingSkipped)
        unused.close()
    }

    // Percent and time left -------------------------------------------------------------------------------------

    @Test fun percentIsCappedAt99UntilDone() = runTest {
        val t = Tracked(this)
        t.send(started(totalMs = 70_000))
        assertEquals(0, t.progress.percent)
        windows(t, 28_000, 54_000)
        assertEquals(77, t.progress.percent)
        windows(t, 70_000)
        assertEquals(99, t.progress.percent)
        assertEquals(TimeLeft.Finishing, t.progress.timeLeft)
        t.send(JobEvent.StageChanged(Stage.SAVING))
        tick()
        assertEquals(99, t.progress.percent)
        t.send(JobEvent.Ended(JobOutcome.Done))
        assertEquals(100, t.progress.percent)
        assertEquals(TimeLeft.Finishing, t.progress.timeLeft)
        t.close()
    }

    @Test fun percentIsIndeterminateWhileTheTotalIsUnknown() = runTest {
        val t = Tracked(this)
        t.send(started(totalMs = 0))
        assertNull(t.progress.percent)
        t.send(JobEvent.Reading(5_000, 90_000))
        tick()
        assertEquals(0, t.progress.percent)
        t.close()
    }

    @Test fun timeLeftIsCalculatingUntilThreeWindowsAndTheTotalIsKnown() = runTest {
        val unknown = Tracked(this)
        unknown.send(started(totalMs = 0))
        listOf(28_000L, 54_000, 80_000, 106_000).forEach { done ->
            advanceTimeBy(10_000)
            unknown.send(JobEvent.WindowDone(done, 0, words(0, (done / 1000).toInt())))
        }
        tick()
        assertEquals(TimeLeft.Calculating, unknown.progress.timeLeft)
        unknown.close()

        val known = Tracked(this)
        known.send(started(totalMs = 300_000))
        windows(known, 28_000, 54_000, totalMs = 300_000)
        assertEquals(TimeLeft.Calculating, known.progress.timeLeft)
        windows(known, 80_000, totalMs = 300_000)
        assertTrue(known.progress.timeLeft is TimeLeft.Range)
        known.close()
    }

    @Test fun theRangeIncludesTheFinalizationEstimate() = runTest {
        val finalizationMs = 90_000L
        val t = Tracked(this, finalizationMs = finalizationMs)
        val reference = TranscriptionProgress(600_000)
        t.send(started(totalMs = 600_000))
        // Windows every 10 s of run time: elapsed = run time on a fresh run.
        listOf(28_000L, 54_000, 80_000, 106_000).forEach { done ->
            advanceTimeBy(10_000)
            t.send(JobEvent.WindowDone(done, 600_000, words(0, 4)))
            reference.complete(done, 600_000, 4, testScheduler.currentTime)
        }
        tick()
        val snapshot = reference.snapshot(testScheduler.currentTime)
        val expected = TimeLeft.Range(
            TranscriptionProgress.roundedRemaining(snapshot.remainingLowMs!! + finalizationMs),
            TranscriptionProgress.roundedRemaining(snapshot.remainingHighMs!! + finalizationMs),
        )
        assertEquals(expected, t.progress.timeLeft)
        t.close()
    }

    // Battery ---------------------------------------------------------------------------------------------------

    @Test fun batteryIsHiddenWithoutAReading() = runTest {
        val t = Tracked(this, battery = ScriptedBattery { null })
        t.send(started(totalMs = 70_000))
        assertEquals(BatteryLine.Unavailable, t.progress.battery)
        t.close()
    }

    @Test fun batteryIsPluggedInWhileCharging() = runTest {
        val t = Tracked(this, battery = ScriptedBattery { BatteryProjection.Reading(it, 64, charging = true) })
        t.send(started(totalMs = 70_000))
        assertEquals(BatteryLine.PluggedIn(64), t.progress.battery)
        t.close()
    }

    @Test fun batteryMeasuresFor120SecondsThenProjects() = runTest {
        // 1 % every 60 s of run time.
        val battery = ScriptedBattery { BatteryProjection.Reading(it, 80 - (it / 60_000).toInt(), charging = false) }
        val t = Tracked(this, battery = battery)
        t.send(started(totalMs = 3_600_000))
        assertEquals(BatteryLine.Measuring(80), t.progress.battery)
        // Enough windows for a time left, every 10 s.
        var done = 0L
        repeat(11) {
            advanceTimeBy(10_000)
            done += 26_000
            t.send(JobEvent.WindowDone(done, 3_600_000, words(0, 4)))
        }
        tick()
        assertTrue(t.progress.timeLeft is TimeLeft.Range)
        assertEquals(BatteryLine.Measuring(79), t.progress.battery) // 111 s: under 120 s
        advanceTimeBy(10_000)
        tick()
        val projected = t.progress.battery
        assertTrue(projected.toString(), projected is BatteryLine.Projected)
        assertEquals(78, (projected as BatteryLine.Projected).percentNow)
        assertTrue(projected.percentAtFinish < 78)
        t.close()
    }

    @Test fun aSmallDropKeepsMeasuring() = runTest {
        val t = Tracked(this, battery = ScriptedBattery { BatteryProjection.Reading(it, 80, charging = false) })
        t.send(started(totalMs = 3_600_000))
        var done = 0L
        repeat(20) {
            advanceTimeBy(10_000)
            done += 26_000
            t.send(JobEvent.WindowDone(done, 3_600_000, words(0, 4)))
        }
        tick()
        assertEquals(BatteryLine.Measuring(80), t.progress.battery)
        t.close()
    }

    @Test fun aResumedRunMeasuresBatteryAgain() = runTest {
        val battery = ScriptedBattery { BatteryProjection.Reading(it, 90 - (it / 30_000).toInt(), charging = false) }
        val t = Tracked(this, battery = battery)
        // The checkpoint's 10 minutes of elapsed time are not this run's measurement.
        t.send(started(totalMs = 3_600_000, restored = JobEvent.Restored(600_000, 3_600_000, emptyList(), 600_000)))
        var done = 600_000L
        repeat(5) {
            advanceTimeBy(10_000)
            done += 26_000
            t.send(JobEvent.WindowDone(done, 3_600_000, words(0, 4)))
        }
        tick()
        assertTrue(t.progress.timeLeft is TimeLeft.Range)
        assertTrue(t.progress.battery.toString(), t.progress.battery is BatteryLine.Measuring)
        t.close()
    }

    // Draft ---------------------------------------------------------------------------------------------------

    @Test fun draftAppliesMergesAndLabelPrecedence() = runTest {
        val t = Tracked(this)
        t.names.set(
            CheckpointNames(
                labels = mapOf("c" to "Carla"),
                merges = mapOf("d" to "b"),
                automatic = mapOf("b" to "Bruno"),
            ),
        )
        t.send(started(totalMs = 70_000))
        val words = listOf(
            Word("a", 0, 500, "uno"),
            Word("d", 1_000, 1_500, "due"),
            Word("c", 2_000, 2_500, "tre"),
            Word("e", 3_000, 3_500, "quattro"),
            Word("b", 4_000, 4_500, "cinque"),
        )
        t.send(JobEvent.WindowDone(28_000, 70_000, words))
        tick()
        val draft = t.draft
        assertEquals(listOf("a", "b", "c", "e", "b"), draft.words.map { it.speaker })
        assertEquals(mapOf("a" to "Speaker 1", "b" to "Bruno", "c" to "Carla", "e" to "Speaker 4"), draft.labels)
        assertEquals(setOf("b"), draft.automatic)
        assertEquals(26_000L, draft.tentativeFromMs)
        assertTrue(draft.namingOpen)
        t.close()
    }

    @Test fun tentativeFollowsTheDoneWindowAndSettlesWhenTheDecodeIsComplete() = runTest {
        val t = Tracked(this)
        t.send(started(totalMs = 70_000))
        windows(t, 28_000)
        assertEquals(26_000L, t.draft.tentativeFromMs)
        windows(t, 54_000)
        assertEquals(52_000L, t.draft.tentativeFromMs)
        windows(t, 70_000)
        t.send(JobEvent.NamingClosed)
        tick()
        assertEquals(Long.MAX_VALUE, t.draft.tentativeFromMs)
        assertFalse(t.draft.namingOpen)
        t.close()
    }

    @Test fun suggestionsOnlyForPlaceholderSpeakersWithALiveSuggestedMatch() = runTest {
        val t = Tracked(this, voices = mapOf("v-luca" to "Luca", "v-ada" to "Ada", "v-gone" to "Gone"))
        t.names.set(
            CheckpointNames(
                labels = mapOf("named" to "Nora"),
                merges = emptyMap(),
                automatic = mapOf("auto" to "Ada"),
                live = mapOf(
                    "a" to Match("v-luca", 0.61f, Match.State.SUGGESTED),
                    "named" to Match("v-luca", 0.62f, Match.State.SUGGESTED),
                    "auto" to Match("v-ada", 0.9f, Match.State.AUTO),
                    "confirmed" to Match("v-ada", 0.7f, Match.State.CONFIRMED),
                    "rejected" to Match("v-luca", 0.6f, Match.State.SUGGESTED),
                    "missing" to Match("v-deleted", 0.6f, Match.State.SUGGESTED),
                ),
                rejected = mapOf("rejected" to setOf("v-luca")),
            ),
        )
        t.send(started(totalMs = 70_000))
        val speakers = listOf("a", "named", "auto", "confirmed", "rejected", "missing", "a", "b", "a")
        val spoken = speakers.mapIndexed { i, id -> Word(id, i * 1_000L, i * 1_000L + 500, "w$i") }
        t.send(JobEvent.WindowDone(28_000, 70_000, spoken))
        tick()
        // Speaker a's latest turn starts at 8 s; the other speakers have a label, an AUTO name, a CONFIRMED match, a
        // rejected voice or a voice that no longer exists.
        assertEquals(listOf(VoiceSuggestion("a", "v-luca", "Luca", 0.61f, 8_000)), t.draft.suggestions)
        // A merge into a: the turn at 6 s and 7 s is a's too, starting at 6 s.
        t.names.set(t.names.get().copy(merges = mapOf("b" to "a")))
        tick()
        assertEquals(6_000L, t.draft.suggestions.single().atMs)
        // "Not Luca" removes the card at the next publish.
        t.names.set(t.names.get().reject("a", "v-luca", "Luca"))
        tick()
        assertTrue(t.draft.suggestions.isEmpty())
        t.close()
    }

    @Test fun noSuggestionsOnceNamingIsClosed() = runTest {
        val t = Tracked(this, voices = mapOf("v-luca" to "Luca"))
        t.names.set(
            CheckpointNames(emptyMap(), emptyMap(), live = mapOf("a" to Match("v-luca", 0.6f, Match.State.SUGGESTED))),
        )
        t.send(started(totalMs = 70_000))
        t.send(JobEvent.WindowDone(28_000, 70_000, words(0, 28)))
        tick()
        assertEquals(1, t.draft.suggestions.size)
        t.send(JobEvent.NamingClosed)
        tick()
        assertTrue(t.draft.suggestions.isEmpty())
        assertFalse(t.draft.namingOpen)
        t.close()
    }

    @Test fun theLowMemoryHintIsCarried() = runTest {
        val t = Tracked(this)
        t.send(started(totalMs = 70_000, lowMemory = true))
        assertTrue(t.progress.lowMemory)
        t.close()
    }

    // Helpers ---------------------------------------------------------------------------------------------------

    private fun started(
        totalMs: Long = 70_000,
        restored: JobEvent.Restored? = null,
        speakers: Boolean = true,
        prints: Boolean = true,
        lowMemory: Boolean = false,
    ) = JobEvent.Started(totalMs, restored, speakers, prints, lowMemory)

    /** One word a second from [fromS] (inclusive) to [toS] (exclusive), all by speaker "a". */
    private fun words(fromS: Int, toS: Int) = (fromS until toS).map { second ->
        Word("a", second * 1_000L, second * 1_000L + 500, "w$second")
    }

    /** Completed windows at [done] of [totalMs], 10 s of run time apart, then one tick. */
    private fun TestScope.windows(t: Tracked, vararg done: Long, totalMs: Long = 70_000) {
        done.forEach {
            advanceTimeBy(10_000)
            t.send(JobEvent.WindowDone(it, totalMs, words(0, (it / 1000).toInt())))
        }
        tick()
    }

    /** Past the next tick and the 400 ms stage debounce. */
    private fun TestScope.tick() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    private class ScriptedBattery(private val level: (Long) -> BatteryProjection.Reading?) : BatterySource {
        val reads = mutableListOf<Long>()

        override fun read(elapsedMs: Long): BatteryProjection.Reading? {
            reads += elapsedMs
            return level(elapsedMs)
        }
    }

    /** A tracker on the test scheduler: its clock is virtual time, its events a channel the test fills. */
    private class Tracked(
        private val scope: TestScope,
        finalizationMs: Long = 0,
        val battery: ScriptedBattery = ScriptedBattery { BatteryProjection.Reading(it, 50, charging = false) },
        voices: Map<String, String> = emptyMap(),
    ) {
        val names = AtomicReference(CheckpointNames(emptyMap(), emptyMap()))
        private val events = Channel<JobEvent>(Channel.UNLIMITED)
        val tracker = ProgressTracker(
            DraftSources(names::get, { voices }, { "Speaker $it" }),
            finalizationMs,
            battery,
            clock = { scope.testScheduler.currentTime },
            dispatcher = StandardTestDispatcher(scope.testScheduler),
        )
        val outcome: Deferred<JobOutcome?> = scope.backgroundScope.async { tracker.run(events) }

        val phase: JobPhase get() = tracker.phase.value
        val progress: RunningProgress get() = (phase as JobPhase.Transcribing).progress
        val draft: DraftState get() = (phase as JobPhase.Transcribing).draft

        fun send(event: JobEvent) {
            events.trySend(event)
            scope.runCurrent()
        }

        fun close() {
            events.close()
            scope.runCurrent()
        }
    }
}
