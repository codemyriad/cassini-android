package org.cassini.engine.job

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cassini.core.asr.BatteryProjection
import org.cassini.core.asr.ProgressAnchor
import org.cassini.core.asr.ProgressPace
import org.cassini.core.asr.TranscriptionProgress
import org.cassini.core.asr.TranscriptionProgress.Stage
import org.cassini.core.checkpoint.CheckpointNames
import org.cassini.core.format.SpeakerMerges
import org.cassini.core.format.Word
import org.cassini.core.speakers.Match

/**
 * Reads the battery for [ProgressTracker] (the app's `BatteryReader` over the sticky `ACTION_BATTERY_CHANGED`
 * broadcast). Null when there is no reading. Called on the tracker's thread at most every 10 s; it must not do
 * blocking I/O.
 */
fun interface BatterySource {
    /** The reading now, stamped with [elapsedMs] (the run's elapsed time). */
    fun read(elapsedMs: Long): BatteryProjection.Reading?
}

/**
 * What the draft transcript needs besides the job's events. Each function is cheap, never blocks and may be called
 * from any thread: [names] reads the note's `DraftNames` reference, [voiceNames] the saved voices that exist now (id to
 * name, from memory), and [placeholderLabel] is the job's `JobEnvironment.placeholderLabel` ("Speaker 2").
 */
class DraftSources(
    val names: () -> CheckpointNames,
    val voiceNames: () -> Map<String, String>,
    val placeholderLabel: (Int) -> String,
)

/**
 * Turns the [JobEvent]s of one job run into [phase] (architecture section 5.5, features.md section 3.1). Use one tracker
 * per run: it owns that run's [TranscriptionProgress] and a new [BatteryProjection], so a resumed job measures battery
 * use again from its own start.
 *
 * [run] consumes the events on [dispatcher] (`Dispatchers.Default.limitedParallelism(1)` in the app): every member
 * below is confined to it. While the phase is [JobPhase.Transcribing] the tracker publishes once a second, and at once
 * after [refresh]. [clock] is monotonic milliseconds (`SystemClock.elapsedRealtime` in the app); the elapsed time of a
 * run is the checkpoint's elapsed time plus the time since [JobEvent.Started]. [battery] is read every 10 s.
 * [finalizationMs] is the measured matching-and-saving estimate (`processing.finalizationMs`), added to the time left.
 *
 * Rules:
 * - The step is monotonic: `step = max(step, Step.of(stage))`, starting at Reading on every run. The headline never
 *   shows a step below the stepper, so it never goes back to Reading once Words & voices began; the 400 ms debounce
 *   of [TranscriptionProgress.snapshot] applies within a step.
 * - The WORDS and SPEAKERS stages read "Finding words and speakers", or "Finding words" without a diarizer (none
 *   installed, or [JobEvent.SpeakersUnavailable]).
 * - Matching is drawn as skipped when the job will not match voices: [JobEvent.Started] says it can not
 *   (`matching`), [JobEvent.NamingClosed] says it will not, or Saving began without it. [JobEvent.SpeakersUnavailable]
 *   alone does not skip it, because the windows before the failure keep their speakers.
 * - The percent is capped at 99 until [JobOutcome.Done], which publishes 100.
 * - Anchor: set while the shown stage is WORDS or SPEAKERS, from the [ProgressPace] of this run (on the [clock]) and
 *   the shown window. It changes only with a completion or a new window, so equal ticks stay equal.
 * - Time left is Calculating until 3 windows completed since this run started, or while the total is unknown; Finishing
 *   when no audio is left; else the rounded range plus [finalizationMs].
 * - Battery: hidden without a reading, Plugged in while charging, Measuring until 120 s and a 0.5 % drop, else the
 *   projection at the finish.
 * - Draft: draft merges applied to the words, labels by precedence (draft name, AUTO match, placeholder numbered by
 *   first word), suggestions for placeholder speakers with a live SUGGESTED match to a voice that exists and was not
 *   rejected, while naming is open. Words that end after `doneMs - 2000` are unsettled until the decode is complete.
 */
class ProgressTracker(
    private val sources: DraftSources,
    private val finalizationMs: Long,
    private val battery: BatterySource,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val state = MutableStateFlow<JobPhase>(JobPhase.Starting)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var current: RunState? = null
    private var stopping = false

    /** What the Note screen and the notification show for this run. Starts at [JobPhase.Starting]. */
    val phase: StateFlow<JobPhase> = state.asStateFlow()

    /** Publishes at once instead of at the next tick, after the user changed the draft names. Any thread. */
    fun refresh() {
        wake.trySend(Unit)
    }

    /**
     * Consumes [events] until the job closes the channel, and returns the outcome of [JobEvent.Ended] (null when the
     * channel closed without one). Call once.
     */
    suspend fun run(events: ReceiveChannel<JobEvent>): JobOutcome? = withContext(dispatcher) {
        coroutineScope {
            var ticker: Job? = null
            var outcome: JobOutcome? = null
            for (event in events) {
                when (event) {
                    is JobEvent.Started -> {
                        start(event)
                        if (!stopping) ticker = launch { tick() }
                    }
                    JobEvent.Stopping -> {
                        stopping = true
                        ticker?.cancel()
                        state.value = JobPhase.Stopping
                    }
                    is JobEvent.Ended -> {
                        outcome = event.outcome
                        ticker?.cancel()
                        if (event.outcome == JobOutcome.Done) publish(done = true)
                    }
                    else -> on(event)
                }
            }
            ticker?.cancel()
            outcome
        }
    }

    private suspend fun tick() {
        while (true) {
            // A lost wake-up only delays one publish to the next tick.
            withTimeoutOrNull(TICK_MS) { wake.receive() }
            publish(done = false)
        }
    }

    private fun start(event: JobEvent.Started) {
        val restored = event.restored
        val progress = TranscriptionProgress(event.totalMs)
        restored?.let { progress.restore(it.doneMs, it.totalMs, it.words.size, it.elapsedMs) }
        current = RunState(
            progress = progress,
            startedAt = clock(),
            checkpointElapsedMs = restored?.elapsedMs ?: 0L,
            lowMemory = event.lowMemory,
        ).apply {
            speakers = event.speakers
            matching = event.matching
            words = restored?.words.orEmpty()
        }
        publish(done = false)
    }

    private fun on(event: JobEvent) {
        val run = current
        when {
            event is JobEvent.WaitingForPrevious -> if (run == null && !stopping) state.value = JobPhase.WaitingForPrevious
            event is JobEvent.Downloading -> if (run == null && !stopping) state.value = JobPhase.Downloading(event.progress)
            run == null -> Unit
            event is JobEvent.StageChanged -> stage(run, event.stage, event.startMs, event.endMs)
            event is JobEvent.Reading -> {
                event.totalMs?.let(run.progress::total)
                stage(run, Stage.READING, 0, event.readMs)
            }
            event is JobEvent.WindowDone -> {
                run.progress.complete(event.doneMs, event.totalMs, event.words.size, run.elapsed())
                run.pace.complete(event.doneMs, clock())
                run.words = event.words
            }
            event is JobEvent.SpeakersUnavailable -> run.speakers = false
            event is JobEvent.NamingClosed -> {
                run.decoded = true
                run.matching = event.matching
            }
            else -> Unit
        }
    }

    /** Stages below the stepper are dropped (a Reading between windows): the stepper and its top stage only move forward. */
    private fun stage(run: RunState, stage: Stage, startMs: Long, endMs: Long) {
        val step = Step.of(stage)
        if (stage == Stage.MATCHING) run.matched = true
        if (step < run.step) return
        run.progress.stage(stage, run.elapsed(), startMs, endMs)
        run.step = step
        run.top = Shown(stage, startMs, endMs)
    }

    private fun publish(done: Boolean) {
        val run = current ?: return
        if (stopping && !done) return
        val progress = progress(run, done)
        state.value = JobPhase.Transcribing(progress, draft(run, done, progress.doneMs))
    }

    private fun progress(run: RunState, done: Boolean): RunningProgress {
        val elapsed = run.elapsed()
        val snapshot = run.progress.snapshot(elapsed)
        // The headline never shows a step below the stepper (a late or debounced Reading, for example).
        val shown = if (Step.of(snapshot.stage) < run.step) {
            run.top
        } else {
            Shown(snapshot.stage, snapshot.startMs, snapshot.endMs)
        }
        return RunningProgress(
            headline = headline(shown.stage, run.speakers),
            rangeStartMs = shown.startMs,
            rangeEndMs = shown.endMs,
            percent = if (done) FULL else snapshot.percent?.coerceAtMost(CAP),
            step = if (done) Step.SAVING else run.step,
            matchingSkipped = !run.matched && (run.step == Step.SAVING || done || !run.matching),
            timeLeft = if (done) TimeLeft.Finishing else timeLeft(snapshot, finalizationMs),
            doneMs = snapshot.doneMs,
            totalMs = snapshot.totalMs,
            battery = battery(run, snapshot, elapsed),
            words = snapshot.words,
            lowMemory = run.lowMemory,
            anchor = if (done) null else anchor(run.pace, shown, snapshot.totalMs),
        )
    }

    /** 0.1.0 `renderTranscriptionProgress`: a reading every 10 s into this run's projection. */
    private fun battery(run: RunState, snapshot: TranscriptionProgress.Snapshot, elapsed: Long): BatteryLine {
        val readAt = run.batteryReadAt
        if (readAt == null || elapsed - readAt >= BATTERY_INTERVAL_MS) {
            run.batteryReadAt = elapsed
            run.reading = battery.read(elapsed)
        }
        if (snapshot.remainingMs == 0L && run.finalizationAt < 0) run.finalizationAt = elapsed
        val reading = run.reading ?: return BatteryLine.Unavailable
        val left = batteryLeft(snapshot, finalizationMs, elapsed - run.finalizationAt)
        return line(run.projection.sample(reading, left))
    }

    private fun draft(run: RunState, done: Boolean, doneMs: Long): DraftState {
        val decoded = run.decoded || done
        return DraftView.build(
            run.words,
            sources,
            namingOpen = !decoded,
            tentativeFromMs = if (decoded) Long.MAX_VALUE else doneMs - SETTLE_MS,
        )
    }

    /** A stage with its window, as the headline shows it. */
    private data class Shown(val stage: Stage, val startMs: Long, val endMs: Long)

    /** One run's numbers; confined to the tracker's dispatcher. */
    private inner class RunState(
        val progress: TranscriptionProgress,
        val startedAt: Long,
        val checkpointElapsedMs: Long,
        val lowMemory: Boolean,
    ) {
        val projection = BatteryProjection()
        val pace = ProgressPace()
        var speakers = false
        var words: List<Word> = emptyList()
        var step = Step.READING
        var top = Shown(Stage.READING, 0, 0)

        /** The MATCHING stage began. */
        var matched = false

        /** The job can still match voices in this run (from [JobEvent.Started], then [JobEvent.NamingClosed]). */
        var matching = false

        /** Naming closed: the decode is complete. */
        var decoded = false
        var batteryReadAt: Long? = null
        var reading: BatteryProjection.Reading? = null
        var finalizationAt = -1L

        fun elapsed(): Long = checkpointElapsedMs + (clock() - startedAt)
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val BATTERY_INTERVAL_MS = 10_000L
        const val SETTLE_MS = 2_000L
        const val MIN_LEFT_MS = 5_000L
        const val CAP = 99
        const val FULL = 100

        fun headline(stage: Stage, speakers: Boolean): ProgressHeadline = when (stage) {
            Stage.READING -> ProgressHeadline.READING_AUDIO
            Stage.PREPARING -> ProgressHeadline.PREPARING
            Stage.WORDS, Stage.SPEAKERS ->
                if (speakers) ProgressHeadline.FINDING_WORDS_AND_SPEAKERS else ProgressHeadline.FINDING_WORDS
            Stage.MATCHING -> ProgressHeadline.MATCHING_VOICES
            Stage.SAVING -> ProgressHeadline.SAVING
        }

        /** The anchor of the [shown] window while words are found, from this run's [pace]. */
        fun anchor(pace: ProgressPace, shown: Shown, totalMs: Long): ProgressAnchor? =
            if (shown.stage == Stage.WORDS || shown.stage == Stage.SPEAKERS) {
                pace.anchor(totalMs, shown.endMs, CAP / FULL.toFloat())
            } else {
                null
            }

        fun timeLeft(snapshot: TranscriptionProgress.Snapshot, finalizationMs: Long): TimeLeft {
            val remaining = snapshot.remainingMs
            return when {
                remaining == null -> TimeLeft.Calculating
                remaining <= 0 -> TimeLeft.Finishing
                else -> TimeLeft.Range(
                    TranscriptionProgress.roundedRemaining((snapshot.remainingLowMs ?: remaining) + finalizationMs),
                    TranscriptionProgress.roundedRemaining((snapshot.remainingHighMs ?: remaining) + finalizationMs),
                )
            }
        }

        /**
         * The time the battery projection extrapolates over (0.1.0): the pessimistic remaining time plus finalization,
         * or, once no audio is left, what is left of the finalization estimate [sinceFinishing] after the decode ended.
         */
        fun batteryLeft(snapshot: TranscriptionProgress.Snapshot, finalizationMs: Long, sinceFinishing: Long): Long? {
            val remaining = snapshot.remainingMs
            return when {
                remaining == null -> null
                remaining > 0 -> (snapshot.remainingHighMs ?: remaining) + finalizationMs
                finalizationMs > 0 -> (finalizationMs - sinceFinishing).coerceAtLeast(MIN_LEFT_MS)
                else -> null
            }
        }

        fun line(estimate: BatteryProjection.Estimate): BatteryLine {
            val now = estimate.percentNow ?: return BatteryLine.Unavailable
            val after = estimate.percentAfter
            return when {
                estimate.charging -> BatteryLine.PluggedIn(now)
                after != null -> BatteryLine.Projected(now, after)
                else -> BatteryLine.Measuring(now)
            }
        }
    }
}

/** The draft transcript of a running job: merges, labels by precedence and suggestion cards (features.md 3.1). */
internal object DraftView {
    /**
     * [words] (raw speaker ids) with the current draft names of [sources]: merges applied, each speaker's label (draft
     * name, then AUTO name, then the placeholder numbered by first word), and the suggestion cards while [namingOpen].
     */
    fun build(words: List<Word>, sources: DraftSources, namingOpen: Boolean, tentativeFromMs: Long): DraftState {
        val names = sources.names()
        val merged = if (names.merges.isEmpty()) {
            words
        } else {
            words.map { it.copy(speaker = SpeakerMerges.resolve(it.speaker, names.merges)) }
        }
        val labels = LinkedHashMap<String, String>()
        val automatic = LinkedHashSet<String>()
        val placeholders = HashSet<String>()
        merged.map { it.speaker }.distinct().forEachIndexed { index, id ->
            val named = names.labels[id]
            val auto = names.automatic[id]
            labels[id] = named ?: auto ?: sources.placeholderLabel(index + 1)
            if (named == null && auto != null) automatic += id
            if (named == null && auto == null) placeholders += id
        }
        return DraftState(
            words = merged,
            labels = labels,
            automatic = automatic,
            suggestions = if (namingOpen) suggestions(merged, placeholders, names, sources) else emptyList(),
            tentativeFromMs = tentativeFromMs,
            namingOpen = namingOpen,
        )
    }

    /**
     * "Is this Luca?" for each placeholder speaker with a live SUGGESTED match to a voice that still exists and was
     * not rejected, at that speaker's latest turn.
     */
    private fun suggestions(
        words: List<Word>,
        placeholders: Set<String>,
        names: CheckpointNames,
        sources: DraftSources,
    ): List<VoiceSuggestion> {
        if (placeholders.isEmpty() || names.live.isEmpty()) return emptyList()
        val voices = sources.voiceNames()
        val latestTurn = HashMap<String, Long>()
        var previous: String? = null
        for (word in words) {
            if (word.speaker != previous) latestTurn[word.speaker] = word.startMs
            previous = word.speaker
        }
        return latestTurn.keys.filter { it in placeholders }.mapNotNull { id ->
            val match = names.live[id]?.takeIf { it.state == Match.State.SUGGESTED } ?: return@mapNotNull null
            val name = voices[match.voiceId]
            if (name == null || match.voiceId in names.rejected[id].orEmpty()) return@mapNotNull null
            VoiceSuggestion(id, match.voiceId, name, match.score, latestTurn.getValue(id))
        }.sortedBy { it.atMs }
    }
}
