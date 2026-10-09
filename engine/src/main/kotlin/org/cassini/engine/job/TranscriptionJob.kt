package org.cassini.engine.job

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.cassini.core.Failure
import org.cassini.core.ProcessingPaused
import org.cassini.core.UserFacingException
import org.cassini.core.asr.TranscriptionProgress.Stage
import org.cassini.core.audio.Limits
import org.cassini.core.audio.PcmAudio
import org.cassini.core.audio.PcmFile
import org.cassini.core.checkpoint.CheckpointNames
import org.cassini.core.checkpoint.CheckpointState
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.OggOpus
import org.cassini.core.format.SpeakerMerges
import org.cassini.core.format.Transcript
import org.cassini.core.library.PauseReason
import org.cassini.core.models.ModelCatalog
import org.cassini.core.models.ModelPart
import org.cassini.core.requireUser
import org.cassini.core.speakers.DefaultLabels
import org.cassini.core.speakers.Diarization
import org.cassini.core.speakers.Match
import org.cassini.core.speakers.PrintWindow
import org.cassini.core.speakers.SpeakerPrint
import org.cassini.core.speakers.SpeakerTurn
import org.cassini.core.speakers.Voice
import org.cassini.core.speakers.VoiceClipCache
import org.cassini.core.speakers.VoiceMatcher
import org.cassini.core.speakers.Voiceprints
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicReference

/**
 * One run of the transcription pipeline for one note: 0.1.0 `MainActivity.transcribe()` as named steps over the ports
 * (JobPorts.kt) and seams (JobSeams.kt), architecture section 5.4.
 *
 * | # | Step | Thread |
 * |---|---|---|
 * | 1 | [prepare]: checkpoint, voices, memory preflight | IO |
 * | 2 | [installModels]: consent, then the missing models | IO |
 * | 3 | [restore]: names, clips, encoder, diarizer | inference |
 * | 4 | [decodeLoop], with [onWindow] for each 28 s window and a checkpoint after it | inference |
 * | 5 | [finish]: last window, final checkpoint, encoder end | inference |
 * | 6 | [attribute]: close naming, merges, diarization record | inference |
 * | 7 | [matchVoices]: final prints and matches | inference |
 * | 8 | [labels] and `x-speakerIdentification` | inference |
 * | 9 | [seal] into `work/sealed.opus`, pending prints, the marker | inference |
 * | 10 | [adopt] | IO, NonCancellable |
 *
 * Native and file work runs in `runInterruptible(inference)`: the loop blocks, calls the blocking port functions
 * directly, never uses `runBlocking`, and sends [events] with `trySend`. [stop] sets a flag the loop checks at each
 * sample block: the job saves a checkpoint and ends [JobOutcome.Paused]. Cancelling the coroutine that runs [run]
 * interrupts the inference thread: a native call in flight finishes first, then the native handles are released and
 * the job ends [JobOutcome.Cancelled] with the previous checkpoint. A Java OutOfMemoryError ends
 * `Paused(MEMORY)` with the checkpoint intact.
 *
 * The job writes only in `work/`, `voices/pending/` and through [JobStorage.mutateNames]; never `document.opus`.
 */
@Suppress("TooManyFunctions", "LargeClass") // one function per named step of architecture section 5.4
class TranscriptionJob(
    val noteId: String,
    private val storage: JobStorage,
    private val voices: JobVoices,
    private val models: ModelAccess,
    private val engines: JobEngines,
    private val env: JobEnvironment,
) {
    private val channel = Channel<JobEvent>(Channel.UNLIMITED)
    private val stopReason = AtomicReference<PauseReason?>(null)

    @Volatile private var download: Job? = null

    /** Set after the final checkpoint: from then on a stop is honoured at the next check, not at a sample block. */
    @Volatile private var finishing = false

    /** Set from the seal on: an I/O error is a SAVE failure, not an OPEN one. */
    @Volatile private var sealing = false

    /** What the job tells its `ProgressTracker`, in order; closed after [JobEvent.Ended]. */
    val events: ReceiveChannel<JobEvent> get() = channel

    /**
     * "Stop and keep progress": the job saves a checkpoint at its next sample block and ends `Paused(reason)`. A stop
     * during the download stops waiting for it (the `.part` files stay). A stop after the seal deletes
     * `work/sealed.opus` and keeps the complete checkpoint. Any thread; only the first call counts.
     */
    fun stop(reason: PauseReason = PauseReason.USER) {
        if (!stopReason.compareAndSet(null, reason)) return
        emit(JobEvent.Stopping)
        download?.cancel()
    }

    /** Runs the job to its end. Call once. */
    suspend fun run(): JobOutcome {
        val outcome = try {
            pipeline()
        } catch (error: OutOfMemoryError) {
            env.warn("Not enough memory", error)
            JobOutcome.Paused(PauseReason.MEMORY)
        } catch (
            @Suppress("TooGenericExceptionCaught") // the job boundary: every failure becomes an outcome
            error: Exception,
        ) {
            outcomeOf(error)
        }
        emit(JobEvent.Ended(outcome))
        channel.close()
        return outcome
    }

    private suspend fun pipeline(): JobOutcome {
        val prepared = prepare() ?: return JobOutcome.Failed(JobFailure.CheckpointMismatch)
        return installModels() ?: transcribe(prepared)
    }

    private suspend fun transcribe(prepared: Prepared): JobOutcome {
        val decoded = decode(prepared)
        try {
            val began = env.nanoTime()
            val draft = onInference { attribute(decoded) }
            val matched = matchVoices(prepared, decoded, draft)
            val sealed = onInference { seal(prepared, decoded, draft, matched) }
            return adopt(sealed, began)
        } finally {
            decoded.run.clips.close()
        }
    }

    // Step 1 ----------------------------------------------------------------------------------------------------

    /** Loads the checkpoint and the voices. Null when the checkpoint no longer matches. */
    private suspend fun prepare(): Prepared? = withContext(env.io) {
        val resumed = when (val load = storage.loadCheckpoint(noteId)) {
            CheckpointLoad.Missing -> null
            is CheckpointLoad.Valid -> load.state
            is CheckpointLoad.Mismatch -> return@withContext null
        }
        // The generation first: a "Forget everyone" in between makes adopt drop these matches, never keep them.
        val generation = voices.generationBlocking()
        val saved = voices.peopleBlocking()
        Prepared(resumed, storage.audioFile(noteId), saved + noteVoices(saved), generation, env.lowMemory())
    }

    /**
     * Named speakers of the note's sealed transcript as note-local voices ([CheckpointNames.NOTE_VOICE_PREFIX]), so a
     * Transcribe again carries their names over even when they were not remembered (0.1.0 `voicesForRecognition`).
     */
    private fun noteVoices(saved: List<Voice>): List<Voice> {
        val document = storage.existingDocumentBlocking(noteId) ?: return emptyList()
        val savedIds = saved.mapTo(HashSet()) { it.id }
        return voices.noteVoicesBlocking(noteId).mapNotNull { (id, print) ->
            val label = document.speakerLabel(id)
            val named = label != null && !DefaultLabels.isDefault(id, label)
            val usable = named && print.match?.state != Match.State.REJECTED && print.match?.voiceId !in savedIds
            if (usable) {
                val voiceId = CheckpointNames.NOTE_VOICE_PREFIX + id
                Voice(voiceId, label, JobProvenance.PRINT_MODEL, print.embedding, 1, print.seconds, 0, 0)
            } else {
                null
            }
        }
    }

    // Step 2 ----------------------------------------------------------------------------------------------------

    /** Installs the missing models; null when the job can go on. Consent is checked again here (section 5.6). */
    private suspend fun installModels(): JobOutcome? = withContext(env.io) {
        val missing = ModelPart.entries.filterNot(models::ready).toSet()
        when {
            missing.isEmpty() -> null
            !models.consented() -> JobOutcome.NeedsConsent(models.plan().totalBytes)
            else -> when (val outcome = download(missing)) {
                is InstallOutcome.Installed -> null
                is InstallOutcome.Failed -> JobOutcome.Failed(JobFailure.Download(outcome.error, outcome.part))
                is InstallOutcome.NeedsConsent -> JobOutcome.NeedsConsent(outcome.missingBytes)
            }
        }
    }

    /** Waits for the shared install; [stop] cancels the wait only, and the job pauses. */
    private suspend fun download(parts: Set<ModelPart>): InstallOutcome = coroutineScope {
        if (stopReason.get() != null) throw ProcessingPaused()
        emit(JobEvent.Downloading(null))
        val task = async { models.install(parts) { emit(JobEvent.Downloading(it)) } }
        download = task
        if (stopReason.get() != null) task.cancel()
        try {
            task.await()
        } catch (cancelled: CancellationException) {
            ensureActive()
            throw ProcessingPaused().apply { initCause(cancelled) }
        } finally {
            download = null
        }
    }

    // Steps 3 to 5 ----------------------------------------------------------------------------------------------

    /** Steps 3 to 5 while holding [NativeBudget]; every native handle is released before it returns. */
    private suspend fun decode(prepared: Prepared): Decoded {
        if (NativeBudget.isHeld) emit(JobEvent.WaitingForPrevious)
        return NativeBudget.withBudget { onInference { restoreAndDecode(prepared) } }
    }

    private fun restoreAndDecode(prepared: Prepared): Decoded {
        val run = openRun(prepared)
        var decoded: Decoded? = null
        try {
            restore(run)
            decoded = engines.recognizers.open(run.resumed?.recognition, callbacks(run)).use { recognizer ->
                finish(run, recognizer, decodeLoop(run, recognizer))
            }
            return decoded
        } finally {
            release(run, keepClips = decoded != null)
        }
    }

    /** The work files: a fresh run starts them empty, a resumed one truncates them to the checkpoint. */
    private fun openRun(prepared: Prepared): Run {
        val resumed = prepared.resumed
        val files = storage.workFiles(noteId)
        if (resumed == null) {
            files.encodedAudio.delete()
            files.clips.delete()
        }
        val clips = PcmFile(files.clips, deleteOnClose = false)
        var run: Run? = null
        try {
            resumed?.let { clips.truncate(it.clipSamples) }
            val cache = VoiceClipCache(clips, resumed?.voiceClips?.let(CheckpointState::json))
            val opus = isOpus(prepared.audioFile)
            run = Run(
                prepared = prepared,
                clips = clips,
                clipCache = cache,
                sealAudio = if (opus) prepared.audioFile else files.encodedAudio,
                speakerPrefix = resumed?.speakerPrefix ?: env.speakerPrefix(),
                began = env.nanoTime() - (resumed?.elapsedMs ?: 0L) * NANOS_PER_MS,
            )
            run.printsReady = models.ready(ModelPart.VOICES)
            return run
        } finally {
            if (run == null) clips.close()
        }
    }

    /** Step 3: draft names, encoder and diarizer state. */
    private fun restore(run: Run) {
        val resumed = run.resumed
        if (resumed == null) {
            // A fresh run starts with no draft names (0.1.0); there are no words yet that the user could have named.
            val generation = run.prepared.generation
            storage.mutateNames(noteId) { CheckpointNames(emptyMap(), emptyMap(), generation = generation) }
        }
        if (run.sealAudio != run.prepared.audioFile) {
            run.encoder = engines.encoders.open(run.sealAudio, resumed?.encoder)
        }
        run.speakerFailure = resumed?.speakerFailure ?: !models.ready(ModelPart.SPEAKERS)
        run.previousSaved = resumed?.doneMs ?: -1L
        run.diarizer = resumed?.diarizer?.let(engines.diarizers::open)
        val restored = resumed?.let {
            JobEvent.Restored(it.doneMs, it.durationMs, it.recognition.chunks.words.size, it.elapsedMs)
        }
        val canMatch = run.printsReady && run.prepared.people.isNotEmpty()
        val totalMs = resumed?.durationMs ?: 0L
        emit(JobEvent.Started(totalMs, restored, !run.speakerFailure, canMatch, run.prepared.lowMemory))
    }

    private fun callbacks(run: Run) = Recognizer.Callbacks(
        onChunk = { chunk, startMs, words -> onWindow(run, chunk, startMs, words) },
        onStage = { startMs, endMs, loading ->
            checkActive()
            emit(JobEvent.StageChanged(if (loading) Stage.PREPARING else Stage.WORDS, startMs, endMs))
        },
        onProgress = { progress ->
            checkActive()
            emit(JobEvent.WindowDone(progress.doneMs, progress.totalMs, progress.words))
        },
    )

    /**
     * Step 4: streams the audio into the encoder and the recognizer. After each sample block that completed a window,
     * and at a stop, the checkpoint is saved; a stop then ends the run. Returns the decoded duration.
     */
    private fun decodeLoop(run: Run, recognizer: Recognizer): Long {
        recognizer.totalMs = run.resumed?.durationMs ?: 0L
        return engines.audio.stream(
            run.prepared.audioFile,
            run.resumed?.positionSamples ?: 0L,
            { samples, count ->
                checkActive()
                run.encoder?.accept(samples, count)
                recognizer.accept(samples, count)
                val stopping = stopReason.get() != null
                if (recognizer.doneMs > run.previousSaved || stopping) saveCheckpoint(run, recognizer)
                if (stopping) throw ProcessingPaused()
            },
        ) { readMs, totalMs ->
            checkActive()
            totalMs?.let { recognizer.totalMs = it }
            val now = env.nanoTime()
            if (now - run.lastReading >= READING_INTERVAL_NS) {
                run.lastReading = now
                emit(JobEvent.Reading(readMs, totalMs ?: recognizer.totalMs.takeIf { it > 0 }))
            }
        }
    }

    /**
     * Step 4, per window: Sortformer, word assignment, the clip cache and live voice matching. Telling speakers apart
     * is best effort: a failure gives every later word one voice (Failure.SPEAKERS degrades, never fails the job).
     */
    private fun onWindow(run: Run, chunk: PcmAudio, startMs: Long, words: Transcript): Transcript {
        checkActive()
        if (run.speakerFailure) return oneVoice(run, words)
        return try {
            emit(JobEvent.StageChanged(Stage.SPEAKERS, startMs, startMs + chunk.durationMs))
            val diarizer = run.diarizer ?: engines.diarizers.open(null).also { run.diarizer = it }
            val found = diarizer.process(chunk, startMs)
            val assigned = if (found.isEmpty()) {
                oneVoice(run, words).words
            } else {
                Diarization.assign(words.words, found, run.speakerPrefix, stableIds = true)
            }
            if (run.printsReady) run.clipCache.accept(chunk, found, assigned, startMs)
            matchLive(run)
            words.copy(words = assigned)
        } catch (
            @Suppress("TooGenericExceptionCaught") // 0.1.0: any diarizer failure degrades to one voice
            error: Exception,
        ) {
            rethrowStop(error)
            run.speakerFailure = true
            env.warn("Incremental speakers unavailable", error)
            emit(JobEvent.SpeakersUnavailable)
            oneVoice(run, words)
        }
    }

    /**
     * Live voice matching for the speakers with at least 4 s of clips and 10 s more than at the last try, skipping
     * speakers with a CONFIRMED match or a user label. Rejected voices are excluded. The result goes through the
     * `applyLive` reducer, which re-checks the current names, so a "Not Luca" made meanwhile is never undone.
     */
    private fun matchLive(run: Run) {
        if (run.prepared.people.isEmpty() || !run.printsReady) return
        val names = storage.names(noteId).get()
        val windows = SpeakerMerges.windows(run.clipCache.windows, names.merges)
        run.livePrints.keys.retainAll(windows.keys)
        val pending = windows.filter { (id, spans) -> names.openForLive(id) && liveDue(run, id, spans) }
        if (pending.isEmpty()) return
        bestEffort("Live voice matching unavailable", Unit) {
            engines.prints.compute(run.clips, pending) { checkActive() }.forEach { (id, print) ->
                run.liveAttempts[id] = print.second
                run.livePrints[id] = print.first
            }
            val open = run.livePrints.filterKeys(names::openForLive)
            val thresholds = Voiceprints.THRESHOLDS.getValue(JobProvenance.PRINT_MODEL)
            val excluded = names.excluded(open.keys)
            val matches = VoiceMatcher.match(open, run.prepared.people, JobProvenance.PRINT_MODEL, thresholds, excluded)
            storage.mutateNames(noteId) { it.applyLive(matches, run.prepared.voiceNames) }
        }
    }

    private fun liveDue(run: Run, id: String, spans: List<PrintWindow>): Boolean {
        val seconds = spans.sumOf { it.endSample - it.startSample }.toDouble() / Limits.ASR_RATE
        val attempted = run.liveAttempts[id] ?: 0.0
        return seconds >= LIVE_MIN_SECONDS && (attempted == 0.0 || seconds >= attempted + LIVE_STEP_SECONDS)
    }

    /** Step 4e: the clips and the encoded audio are fsynced before the checkpoint that counts them is written. */
    private fun saveCheckpoint(run: Run, recognizer: Recognizer) {
        run.clips.sync()
        val audio = run.prepared.audioFile
        val state = CheckpointState(
            source = audio.name,
            signature = CheckpointState.signature(audio),
            model = ModelCatalog.PARAKEET_REVISION,
            speakers = ModelCatalog.diarizationModel.sha256,
            recognition = recognizer.checkpoint(),
            encoder = run.encoder?.checkpoint(),
            diarizer = run.diarizer?.checkpoint(),
            speakerPrefix = run.speakerPrefix,
            speakerFailure = run.speakerFailure,
            clipSamples = run.clips.sampleCount,
            voiceClips = CheckpointState.voiceClips(run.clipCache.checkpoint()),
            positionSamples = recognizer.positionSamples,
            doneMs = recognizer.doneMs,
            durationMs = recognizer.totalMs,
            elapsedMs = (env.nanoTime() - run.began) / NANOS_PER_MS,
        )
        storage.saveCheckpoint(noteId, state)
        run.previousSaved = recognizer.doneMs
        emit(JobEvent.Checkpointed(recognizer.doneMs))
    }

    /** Step 5: the last partial window, the final (complete) checkpoint, then the end of the Opus stream. */
    private fun finish(run: Run, recognizer: Recognizer, durationMs: Long): Decoded {
        requireUser(durationMs >= MIN_DURATION_MS, Failure.SHORT)
        val transcript = recognizer.finish(durationMs)
        val inferenceMs = recognizer.inferenceMs
        saveCheckpoint(run, recognizer)
        finishing = true
        checkActive()
        run.encoder?.let {
            it.finish()
            it.close()
        }
        val diarizer = run.diarizer
        val diarizationMs = diarizer?.elapsedMs ?: 0L
        return Decoded(run, transcript, inferenceMs, recognizer.segmentation, diarizer?.turns, diarizationMs)
    }

    private fun release(run: Run, keepClips: Boolean) {
        try {
            run.encoder?.close()
        } finally {
            try {
                run.diarizer?.close()
            } finally {
                if (!keepClips) run.clips.close()
            }
        }
    }

    // Steps 6 to 8 ----------------------------------------------------------------------------------------------

    /** Step 6: closes naming and applies the draft merges; builds the recognition and diarization records. */
    private fun attribute(decoded: Decoded): Draft {
        checkActive()
        val names = storage.closeNames(noteId)
        emit(JobEvent.NamingClosed)
        val transcript = SpeakerMerges.apply(decoded.transcript, names.merges)
        val turns = decoded.turns
        checkActive()
        val recognition = JobProvenance.recognition(
            transcript.language,
            decoded.inferenceMs,
            decoded.segmentation,
            decoded.run.resumed?.doneMs ?: 0L,
        )
        val diarization = if (turns.isNullOrEmpty() || transcript.words.isEmpty()) {
            null
        } else {
            JobProvenance.diarization(transcript.words, turns, recognition, decoded.diarizationMs)
        }
        checkActive()
        return Draft(names, transcript, recognition, diarization)
    }

    /**
     * Step 7: one print per speaker (draft merges applied) and `VoiceMatcher.match` against the saved and note-local
     * voices, rejected voices excluded. Best effort: any failure keeps "Speaker N".
     */
    private suspend fun matchVoices(prepared: Prepared, decoded: Decoded, draft: Draft): Matched {
        val run = decoded.run
        if (decoded.turns == null || draft.diarization == null || !run.printsReady) return Matched.NONE
        emit(JobEvent.StageChanged(Stage.MATCHING))
        val windows = SpeakerMerges.windows(run.clipCache.windows, draft.names.merges)
        val computed = try {
            bestEffort("Voiceprints failed", emptyMap()) {
                NativeBudget.withBudget { onInference { engines.prints.compute(run.clips, windows) { checkActive() } } }
            }
        } catch (error: OutOfMemoryError) {
            env.warn("Voiceprints failed", error)
            emptyMap()
        }
        onInference { checkActive() }
        val thresholds = Voiceprints.THRESHOLDS.getValue(JobProvenance.PRINT_MODEL)
        val embeddings = computed.mapValues { it.value.first }
        val excluded = draft.names.excluded(computed.keys)
        val matches = VoiceMatcher.match(embeddings, prepared.people, JobProvenance.PRINT_MODEL, thresholds, excluded)
        return Matched(computed, matches)
    }

    /** Step 8: the shown label of each speaker: the draft name, else the AUTO match's name, else the placeholder. */
    private fun labels(prepared: Prepared, draft: Draft, matched: Matched): Map<String, String> {
        val names = prepared.voiceNames
        return draft.transcript.words.map { it.speaker }.distinct().mapIndexed { index, id ->
            val automatic = matched.matches[id]?.takeIf { it.state == Match.State.AUTO }?.let { names[it.voiceId] }
            id to (draft.names.labels[id] ?: automatic ?: env.placeholderLabel(index + 1))
        }.toMap()
    }

    /** The record sealed into the document, with `x-speakerIdentification` when prints could run. */
    private fun provenance(prepared: Prepared, decoded: Decoded, draft: Draft, matched: Matched): JSONObject {
        val diarization = draft.diarization
        if (diarization != null && decoded.turns != null && decoded.run.printsReady) {
            val prints = matched.prints.mapValues { it.value.first.size }
            val identification = JobProvenance.identification(prints, matched.matches, prepared.voiceNames)
            diarization.put("x-speakerIdentification", identification)
        }
        return JobProvenance.sealed(diarization ?: draft.recognition, draft.names.merges)
    }

    // Step 9 ----------------------------------------------------------------------------------------------------

    /**
     * Step 9: seals into `work/sealed.opus` (never `document.opus`) while holding the note's lock, re-reading the
     * existing document and the title under it. `CassiniDocument.create` verifies the written file; the job fsyncs it,
     * saves the pending prints, then writes the marker. A stop or a failure before the marker deletes the sealed file
     * and keeps the checkpoint.
     */
    private fun seal(prepared: Prepared, decoded: Decoded, draft: Draft, matched: Matched): Sealed {
        checkActive()
        val labels = labels(prepared, draft, matched)
        val processing = provenance(prepared, decoded, draft, matched)
        emit(JobEvent.StageChanged(Stage.SAVING))
        sealing = true
        val target = storage.sealTarget(noteId)
        val audio = decoded.run.sealAudio
        var marked = false
        try {
            val document = storage.withNoteLockBlocking(noteId) {
                val existing = storage.existingDocumentBlocking(noteId)
                val title = storage.titleBlocking(noteId)
                val space = target.absoluteFile.parentFile?.usableSpace ?: 0L
                requireUser(space >= audio.length() + Limits.MIN_FREE_BYTES, Failure.SPACE)
                CassiniDocument.create(audio, draft.transcript, title, processing, existing, labels, target)
            }
            sync(target)
            val variant = document.defaultId
            val prints = matched.pending()
            if (prints.isNotEmpty() && variant != null) {
                voices.savePendingBlocking(noteId, variant, JobProvenance.PRINT_MODEL, prints, prepared.generation)
            }
            if (stopReason.get() != null) throw ProcessingPaused()
            checkActive()
            storage.markSealedBlocking(noteId)
            marked = true
        } finally {
            if (!marked) storage.discardSealedBlocking(noteId)
        }
        return Sealed(labels, draft.names)
    }

    // Step 10 ---------------------------------------------------------------------------------------------------

    /**
     * Step 10: installs the sealed document. A stop that came after the marker still pauses: the sealed file goes,
     * the complete checkpoint stays. Once adopt starts it runs to its end ([NonCancellable]).
     */
    private suspend fun adopt(sealed: Sealed, began: Long): JobOutcome {
        if (stopReason.get() != null) {
            withContext(env.io) { storage.discardSealedBlocking(noteId) }
            throw ProcessingPaused()
        }
        currentCoroutineContext().ensureActive()
        val finalizationMs = (env.nanoTime() - began) / NANOS_PER_MS
        val adoption = Adoption(sealed.labels, sealed.names.remembered, sealed.names.generation, finalizationMs)
        withContext(NonCancellable + env.io) { storage.adopt(noteId, adoption) }
        return JobOutcome.Done
    }

    // Helpers ---------------------------------------------------------------------------------------------------

    private suspend fun <T> onInference(block: () -> T): T = runInterruptible(env.inference, block)

    private fun emit(event: JobEvent) {
        channel.trySend(event)
    }

    /** Cancellation is an interrupt; a stop counts here only once the decode is complete (0.1.0 `checkActive`). */
    private fun checkActive() {
        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
        if (finishing && stopReason.get() != null) throw ProcessingPaused()
    }

    /** Runs [block]; a failure is logged and gives [fallback], except a cancellation or a stop. */
    private inline fun <T> bestEffort(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (
        @Suppress("TooGenericExceptionCaught") // best effort, as 0.1.0: any failure keeps the transcript unchanged
        error: Exception,
    ) {
        rethrowStop(error)
        env.warn(what, error)
        fallback
    }

    private fun rethrowStop(error: Exception) {
        val cancelled = error is InterruptedException ||
            (error is UserFacingException && error.failure == Failure.CANCELLED)
        if (cancelled) throw UserFacingException(Failure.CANCELLED).apply { initCause(error) }
        if (error is CancellationException || error is ProcessingPaused) throw error
    }

    private suspend fun outcomeOf(error: Exception): JobOutcome {
        val cancelled = !currentCoroutineContext().isActive ||
            (error is UserFacingException && error.failure == Failure.CANCELLED) ||
            (error is CancellationException && stopReason.get() == null)
        return when {
            cancelled -> JobOutcome.Cancelled
            error is ProcessingPaused || error is CancellationException ->
                JobOutcome.Paused(stopReason.get() ?: PauseReason.USER)
            error is UserFacingException -> JobOutcome.Failed(JobFailure.Processing(error.failure))
            error is IOException -> {
                env.warn("Transcription I/O failed", error)
                JobOutcome.Failed(JobFailure.Processing(if (sealing) Failure.SAVE else Failure.OPEN))
            }
            else -> {
                env.warn("Transcription failed", error)
                JobOutcome.Failed(JobFailure.Processing(Failure.UNKNOWN))
            }
        }
    }

    private fun oneVoice(run: Run, words: Transcript) =
        words.copy(words = words.words.map { it.copy(speaker = "${run.speakerPrefix}1") })

    // State shared by the steps -----------------------------------------------------------------------------------

    /** What step 1 found. [people] are the saved voices plus the note-local ones. */
    private class Prepared(
        val resumed: CheckpointState?,
        val audioFile: File,
        val people: List<Voice>,
        val generation: Long,
        val lowMemory: Boolean,
    ) {
        val voiceNames: Map<String, String> = people.associate { it.id to it.name }
    }

    /** One run's work files and native state, confined to the inference thread. */
    private class Run(
        val prepared: Prepared,
        val clips: PcmFile,
        val clipCache: VoiceClipCache,
        /** The audio the seal packages: the note's Ogg Opus file, or `work/audio.opus.part` for any other source. */
        val sealAudio: File,
        val speakerPrefix: String,
        /** [JobEnvironment.nanoTime] at the start, minus the checkpoint's elapsed time. */
        val began: Long,
    ) {
        /** The voiceprint model is installed: clips are kept and voices matched. */
        var printsReady = false
        val resumed: CheckpointState? get() = prepared.resumed
        var encoder: OpusSink? = null
        var diarizer: StreamingDiarizer? = null
        var speakerFailure = false
        var previousSaved = -1L
        var lastReading = Long.MIN_VALUE / 2
        val livePrints = HashMap<String, FloatArray>()
        val liveAttempts = HashMap<String, Double>()
    }

    /** What steps 3 to 5 produced. [turns] is null without a diarizer. */
    private class Decoded(
        val run: Run,
        val transcript: Transcript,
        val inferenceMs: Long,
        val segmentation: String,
        val turns: List<SpeakerTurn>?,
        val diarizationMs: Long,
    )

    /** Step 6: the closed names, the merged transcript and its provenance records. */
    private class Draft(
        val names: CheckpointNames,
        val transcript: Transcript,
        val recognition: JSONObject,
        val diarization: JSONObject?,
    )

    /** Step 7: the final prints and their raw matches (note-local voices included). */
    private class Matched(val prints: Map<String, ExtractedPrint>, val matches: Map<String, Match>) {
        /** The prints for `voices/pending/`: a match to a note-local voice is stored as no match. */
        fun pending(): Map<String, SpeakerPrint> = prints.mapValues { (id, print) ->
            val match = matches[id]?.takeUnless { it.voiceId.startsWith(CheckpointNames.NOTE_VOICE_PREFIX) }
            SpeakerPrint(print.first, print.second, match)
        }

        companion object {
            val NONE = Matched(emptyMap(), emptyMap())
        }
    }

    private class Sealed(val labels: Map<String, String>, val names: CheckpointNames)

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val READING_INTERVAL_NS = 250_000_000L
        const val MIN_DURATION_MS = 200L
        const val LIVE_MIN_SECONDS = 4.0
        const val LIVE_STEP_SECONDS = 10.0

        /** Any header that does not read as Ogg Opus means "re-encode", as 0.1.0 `DocumentStore.hasOpusAudio`. */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        fun isOpus(file: File): Boolean = try {
            file.inputStream().use { OggOpus.headers(it) }
            true
        } catch (error: Exception) {
            false
        }

        /** Flushes the sealed file to disk before the marker that says it is complete. */
        fun sync(file: File) = RandomAccessFile(file, "rw").use { it.fd.sync() }
    }
}
