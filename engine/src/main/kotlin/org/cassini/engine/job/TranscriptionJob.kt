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
import org.cassini.core.format.DocumentState
import org.cassini.core.format.OggOpus
import org.cassini.core.format.SpeakerMerges
import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.cassini.core.format.WordBodyBudget
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
import java.util.Locale
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
 * sample block: the job saves a checkpoint (once a window is done) and ends [JobOutcome.Paused]; after the decode a
 * stop keeps the final checkpoint and is honoured at the next check. Cancelling the coroutine that runs [run]
 * interrupts the inference thread: a native call in flight finishes first, then the native handles are released and
 * the job ends [JobOutcome.Cancelled] with the previous checkpoint. A Java OutOfMemoryError ends
 * `Paused(MEMORY)` with the checkpoint intact.
 *
 * The job writes only in `work/`, `voices/pending/` and through [JobStorage.mutateNames]; never `document.opus`.
 *
 * Length limits (docs/architecture.md section 3.7): audio longer than [JobEnvironment.maxDurationMs] (36 h) fails
 * with `Failure.LONG` before any work. The words of one transcript must fit the word body of the format code
 * ([JobEnvironment.maxBodyBytes], about 11 h of continuous speech): the job counts them after each window and fails
 * with `Failure.LONG` as soon as they do not fit, not hours later at the seal.
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

    /** Set when adopt starts: from then on the note's draft names belong to adopt. */
    @Volatile private var adopting = false

    /** The open work files of this run, owned by the job so they close on every path (also a late cancel). */
    @Volatile private var current: Run? = null

    /** What the job tells its `ProgressTracker`, in order; closed after [JobEvent.Ended]. */
    val events: ReceiveChannel<JobEvent> get() = channel

    /**
     * "Stop and keep progress": the job ends `Paused(reason)`. During the decode it saves a checkpoint at its next
     * sample block when at least one window is done (before that it leaves none). After the decode the final
     * checkpoint stays and the stop counts at the next check. A stop during the download stops waiting for it (the
     * `.part` files stay). A stop after the seal deletes `work/sealed.opus` and keeps the complete checkpoint. Any
     * thread; only the first call counts.
     */
    fun stop(reason: PauseReason = PauseReason.USER) {
        if (!stopReason.compareAndSet(null, reason)) return
        emit(JobEvent.Stopping)
        download?.cancel()
    }

    /**
     * Runs the job to its end. Call once. An [Error] other than OutOfMemoryError (for example an UnsatisfiedLinkError)
     * is thrown on; [events] closes on every path, so the tracker always ends.
     */
    suspend fun run(): JobOutcome {
        try {
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
            return outcome
        } finally {
            channel.close()
        }
    }

    private suspend fun pipeline(): JobOutcome {
        val prepared = prepare() ?: return JobOutcome.Failed(JobFailure.CheckpointMismatch)
        return installModels() ?: transcribe(prepared)
    }

    private suspend fun transcribe(prepared: Prepared): JobOutcome {
        var decodeDone = false
        try {
            val decoded = decode(prepared)
            decodeDone = true
            val began = env.nanoTime()
            val draft = onInference { attribute(decoded) }
            val matched = matchVoices(prepared, decoded, draft)
            val sealed = onInference { seal(prepared, decoded, draft, matched) }
            return adopt(sealed, began)
        } finally {
            // The inference block has ended here (withContext waits for it), so closing the clips cannot race it.
            current?.clips?.close()
            if (decodeDone && !adopting) reopenNames()
        }
    }

    /** Naming stays open while the note is paused (features section 3.2): step 6 closed it, and no adopt follows. */
    private suspend fun reopenNames() = withContext(NonCancellable + env.io) {
        bestEffort("Could not open naming again", Unit) { storage.openNames(noteId) }
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
        val knownMs = storage.knownDurationMs(noteId)?.takeIf { it > 0 }
        // Too long to transcribe: stop before the download, the model load and any decode.
        requireUser(knownMs == null || knownMs <= env.maxDurationMs, Failure.LONG)
        resumed?.let { requireWordsFit(it.recognition.chunks.words) }
        // Transcribe again over a document that can neither take a version nor be replaced stops here, before any work.
        val existing = storage.existingDocumentBlocking(noteId)?.also(::requireReplaceable)?.takeIf(::extendable)
        val noteVoices = existing?.let { noteVoices(it, saved) }.orEmpty()
        Prepared(resumed, storage.audioFile(noteId), noteVoices, generation, knownMs, env.lowMemory(knownMs))
    }

    /**
     * Named speakers of the note's sealed transcript as note-local voices ([CheckpointNames.NOTE_VOICE_PREFIX]), so a
     * Transcribe again carries their names over even when they were not remembered (0.1.0 `voicesForRecognition`).
     * Only an `ok` [document] counts: any other state gets a fresh document, whose names start over.
     */
    private fun noteVoices(document: CassiniDocument, saved: List<Voice>): List<Voice> {
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

    /**
     * Installs the missing models; null when the job can go on. Consent is checked again here (section 5.6). A
     * Parakeet without its speech detector asks for SPEECH again while online, so the installer fetches the detector
     * alone (best effort), as 0.1.0 retried it at every start.
     */
    private suspend fun installModels(): JobOutcome? = withContext(env.io) {
        val missing = ModelPart.entries.filterNot(models::ready).toSet()
        val detector = ModelPart.SPEECH !in missing && !models.detectorReady() && models.hasValidatedNetwork()
        val request = if (detector) missing + ModelPart.SPEECH else missing
        when {
            request.isEmpty() -> null
            !models.consented() -> JobOutcome.NeedsConsent(models.plan().totalBytes)
            else -> when (val outcome = download(request)) {
                is InstallOutcome.Installed -> null
                is InstallOutcome.Failed ->
                    JobOutcome.Failed(JobFailure.Download(outcome.error, outcome.part, outcome.requiredFreeBytes))
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
        val files = writing { storage.workFiles(noteId) }
        if (resumed == null) {
            files.encodedAudio.delete()
            files.clips.delete()
        }
        val clips = writing { PcmFile(files.clips, deleteOnClose = false) }
        var run: Run? = null
        try {
            resumed?.let { writing { clips.truncate(it.clipSamples) } }
            val opus = isOpus(prepared.audioFile)
            run = Run(
                prepared = prepared,
                clips = clips,
                files = files,
                sealAudio = if (opus) prepared.audioFile else files.encodedAudio,
                speakerPrefix = resumed?.speakerPrefix ?: env.speakerPrefix(),
                began = env.nanoTime() - (resumed?.elapsedMs ?: 0L) * NANOS_PER_MS,
            )
            run.printsReady = models.ready(ModelPart.VOICES)
            current = run
            return run
        } finally {
            if (run == null) clips.close()
        }
    }

    /** Step 3: draft names, encoder and diarizer state. */
    private fun restore(run: Run) {
        val resumed = run.resumed
        // Every run, fresh or resumed, starts with naming open (0.1.0 `draftNamingOpen = true`).
        writing { storage.openNames(noteId) }
        if (resumed == null) {
            // A fresh run starts with no draft names (0.1.0); there are no words yet that the user could have named.
            val generation = run.prepared.generation
            writing { storage.mutateNames(noteId) { CheckpointNames(emptyMap(), emptyMap(), generation = generation) } }
        }
        if (run.sealAudio != run.prepared.audioFile) {
            run.encoder = writing { engines.encoders.open(run.sealAudio, resumed?.encoder) }
        }
        run.speakerFailure = resumed?.speakerFailure ?: !models.ready(ModelPart.SPEAKERS)
        run.previousSaved = resumed?.doneMs ?: -1L
        run.diarizer = resumed?.diarizer?.let(engines.diarizers::open)
        val restored = resumed?.let {
            JobEvent.Restored(it.doneMs, it.durationMs, it.recognition.chunks.words, it.elapsedMs)
        }
        val speakers = !run.speakerFailure
        // A diarizer restored from the checkpoint has turns, so matching can run even after an earlier failure.
        val matching = run.printsReady && (speakers || run.diarizer != null)
        val lowMemory = run.prepared.lowMemory
        emit(JobEvent.Started(run.initialTotalMs, restored, speakers, run.printsReady, lowMemory, matching))
    }

    private fun callbacks(run: Run) = Recognizer.Callbacks(
        onChunk = { chunk, startMs, words -> onWindow(run, chunk, startMs, words) },
        onStage = { startMs, endMs, loading ->
            checkActive()
            emit(JobEvent.StageChanged(if (loading) Stage.PREPARING else Stage.WORDS, startMs, endMs))
        },
        onProgress = { progress ->
            checkActive()
            requireWordsFit(progress.words)
            emit(JobEvent.WindowDone(progress.doneMs, progress.totalMs, progress.words))
        },
    )

    /**
     * Step 4: streams the audio into the encoder and the recognizer. After each sample block that completed a window,
     * and at a stop once a window is done, the checkpoint is saved; a stop then ends the run. Returns the decoded
     * duration.
     */
    private fun decodeLoop(run: Run, recognizer: Recognizer): Long {
        recognizer.totalMs = run.initialTotalMs
        return reading { decodeAudio(run, recognizer) }
    }

    private fun decodeAudio(run: Run, recognizer: Recognizer): Long =
        engines.audio.stream(
            run.prepared.audioFile,
            run.resumed?.positionSamples ?: 0L,
            { samples, count ->
                checkActive()
                run.encoder?.let { writing { it.accept(samples, count) } }
                recognizer.accept(samples, count)
                val stopping = stopReason.get() != null
                // No checkpoint before the first window is done: a stop then leaves none ("Paused before any words").
                val progressed = recognizer.doneMs > maxOf(run.previousSaved, 0L)
                if (progressed || (stopping && recognizer.doneMs > 0L)) saveCheckpoint(run, recognizer)
                if (stopping) throw ProcessingPaused()
            },
        ) { readMs, totalMs ->
            checkActive()
            totalMs?.let {
                requireUser(it <= env.maxDurationMs, Failure.LONG)
                recognizer.totalMs = it
            }
            val now = env.nanoTime()
            if (now - run.lastReading >= READING_INTERVAL_NS) {
                run.lastReading = now
                emit(JobEvent.Reading(readMs, totalMs ?: recognizer.totalMs.takeIf { it > 0 }))
            }
        }

    /**
     * Step 4, per window: Sortformer, word assignment, the clip cache and live voice matching. Telling speakers apart
     * is best effort: a failure gives every later word one voice (Failure.SPEAKERS degrades, never fails the job).
     * The windows before the failure keep their speakers, as in 0.1.0: the seal still records
     * `x-speakerDiarization`, and matching runs on their clips. So `speakersSeparated = false` follows only from a
     * failure at the first window (no turns). This is a deliberate choice: the earlier speakers and their draft names
     * stay.
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
     * speakers with a CONFIRMED match or a user label. Rejected voices are excluded. The voices are read again after
     * the prints, so a person forgotten during the run is not matched again. The result goes through the `applyLive`
     * reducer, which re-checks the current names, so a "Not Luca" made meanwhile is never undone.
     */
    private fun matchLive(run: Run) {
        if (!run.printsReady) return
        val names = storage.names(noteId).get()
        val windows = SpeakerMerges.windows(run.clipCache.windows, names.merges)
        run.livePrints.keys.retainAll(windows.keys)
        val pending = windows.filter { (id, spans) -> names.openForLive(id) && liveDue(run, id, spans) }
        if (pending.isEmpty()) return
        bestEffort("Live voice matching unavailable", Unit) {
            if (people(run.prepared).voices.isEmpty()) return@bestEffort
            engines.prints.compute(run.clips, pending) { checkActive() }.forEach { (id, print) ->
                run.liveAttempts[id] = print.second
                run.livePrints[id] = print.first
            }
            val open = run.livePrints.filterKeys(names::openForLive)
            val thresholds = Voiceprints.THRESHOLDS.getValue(JobProvenance.PRINT_MODEL)
            val excluded = names.excluded(open.keys)
            val people = people(run.prepared)
            val matches = VoiceMatcher.match(open, people.voices, JobProvenance.PRINT_MODEL, thresholds, excluded)
            storage.mutateNames(noteId) { it.applyLive(matches, people.names) }
        }
    }

    /**
     * The voices to match against now: the saved people as they are now (a Forget during the run removes a person, a
     * rename gives the new name), none of them when "Forget everyone" changed the generation since step 1, and the
     * note-local voices of step 1. Blocking.
     */
    private fun people(prepared: Prepared): People {
        val saved = if (voices.generationBlocking() == prepared.generation) voices.peopleBlocking() else emptyList()
        return People(saved + prepared.noteVoices)
    }

    /** [people] for the final match and labels; a failed read gives no voices ("Speaker N"), never a failed job. */
    private fun savedVoices(prepared: Prepared): People =
        bestEffort("Could not read the saved voices", People.NONE) { people(prepared) }

    private fun liveDue(run: Run, id: String, spans: List<PrintWindow>): Boolean {
        val seconds = spans.sumOf { it.endSample - it.startSample }.toDouble() / Limits.ASR_RATE
        val attempted = run.liveAttempts[id] ?: 0.0
        return seconds >= LIVE_MIN_SECONDS && (attempted == 0.0 || seconds >= attempted + LIVE_STEP_SECONDS)
    }

    /** Step 4e: the clips and the encoded audio are fsynced before the checkpoint that counts them is written. */
    private fun saveCheckpoint(run: Run, recognizer: Recognizer) {
        writing { env.syncFile(run.files.clips) { run.clips.sync() } }
        val audio = run.prepared.audioFile
        val signature = CheckpointState.signature(audio)
        val state = writing { checkpointState(run, recognizer, audio.name, signature) }
        writing { storage.saveCheckpoint(noteId, state) }
        run.previousSaved = recognizer.doneMs
        emit(JobEvent.Checkpointed(recognizer.doneMs))
    }

    /** The checkpoint of [recognizer]'s position; reading the encoder state fsyncs `audio.opus.part`. */
    private fun checkpointState(run: Run, recognizer: Recognizer, source: String, signature: String) =
        CheckpointState(
            source = source,
            signature = signature,
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

    /** Step 5: the last partial window, the final (complete) checkpoint, then the end of the Opus stream. */
    private fun finish(run: Run, recognizer: Recognizer, durationMs: Long): Decoded {
        requireUser(durationMs >= MIN_DURATION_MS, Failure.SHORT)
        val transcript = recognizer.finish(durationMs)
        val inferenceMs = recognizer.inferenceMs
        saveCheckpoint(run, recognizer)
        finishing = true
        checkActive()
        run.encoder?.let {
            writing { it.finish() }
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

    /**
     * Step 6: closes naming and applies the draft merges; builds the recognition and diarization records. The
     * diarization record is kept when the diarizer failed after some windows (0.1.0): those words were told apart.
     */
    private fun attribute(decoded: Decoded): Draft {
        checkActive()
        val names = storage.closeNames(noteId)
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
        val matching = turns != null && diarization != null && decoded.run.printsReady
        emit(JobEvent.NamingClosed(matching))
        return Draft(names, transcript, recognition, diarization, matching)
    }

    /**
     * Step 7: one print per speaker (draft merges applied) and `VoiceMatcher.match` against the saved voices as they
     * are now and the note-local voices, rejected voices excluded. Best effort: any failure keeps "Speaker N".
     */
    private suspend fun matchVoices(prepared: Prepared, decoded: Decoded, draft: Draft): Matched {
        val run = decoded.run
        if (!draft.matching) return Matched.NONE
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
        val people = withContext(env.io) { savedVoices(prepared) }
        val matches = VoiceMatcher.match(embeddings, people.voices, JobProvenance.PRINT_MODEL, thresholds, excluded)
        return Matched(computed, matches)
    }

    /**
     * Step 8: the shown label of each speaker: the draft name, else the AUTO match's name, else the placeholder. An
     * AUTO match counts only while its voice is in [names] (the voices as they are at the seal).
     */
    private fun labels(draft: Draft, matched: Matched, names: Map<String, String>): Map<String, String> {
        return draft.transcript.words.map { it.speaker }.distinct().mapIndexed { index, id ->
            val automatic = matched.matches[id]?.takeIf { it.state == Match.State.AUTO }?.let { names[it.voiceId] }
            id to (draft.names.labels[id] ?: automatic ?: env.placeholderLabel(index + 1))
        }.toMap()
    }

    /** The record sealed into the document, with `x-speakerIdentification` when prints could run. */
    private fun provenance(decoded: Decoded, draft: Draft, matched: Matched, names: Map<String, String>): JSONObject {
        val diarization = draft.diarization
        if (diarization != null && decoded.turns != null && decoded.run.printsReady) {
            val prints = matched.prints.mapValues { it.value.first.size }
            val identification = JobProvenance.identification(prints, matched.matches, names)
            diarization.put("x-speakerIdentification", identification)
        }
        return JobProvenance.sealed(diarization ?: draft.recognition, draft.names.merges)
    }

    // Step 9 ----------------------------------------------------------------------------------------------------

    /**
     * Step 9: seals into `work/sealed.opus` (never `document.opus`) while holding the note's lock, re-reading the
     * existing document and the title under it. Only an `ok` document gets a new variant; a STALE_AUDIO,
     * INVALID_METADATA or UNREADABLE document is replaced by a fresh one over the same audio, without its old Cassini
     * tags, and any other state is refused ([requireReplaceable]). `CassiniDocument.create`
     * verifies the written file; the job fsyncs it, saves the pending prints (or deletes an older pending file when
     * there are none), then writes the marker. A stop or a failure before the marker deletes the sealed file and keeps
     * the checkpoint.
     */
    private fun seal(prepared: Prepared, decoded: Decoded, draft: Draft, matched: Matched): Sealed {
        checkActive()
        val names = savedVoices(prepared).names
        val labels = labels(draft, matched, names)
        val processing = provenance(decoded, draft, matched, names)
        emit(JobEvent.StageChanged(Stage.SAVING))
        val target = storage.sealTarget(noteId)
        var marked = false
        try {
            val document = storage.withNoteLockBlocking(noteId) {
                val current = storage.existingDocumentBlocking(noteId)?.also(::requireReplaceable)
                val existing = current?.takeIf(::extendable)
                val title = storage.titleBlocking(noteId)
                val audio = if (existing == null) freshAudio(decoded.run, target) else decoded.run.sealAudio
                requireSpace(target, audio.length())
                CassiniDocument.create(audio, draft.transcript, title, processing, existing, labels, target)
            }
            env.syncFile(target) { sync(target) }
            val variant = document.defaultId
            val prints = matched.pending()
            if (prints.isNotEmpty() && variant != null) {
                voices.savePendingBlocking(noteId, variant, JobProvenance.PRINT_MODEL, prints, prepared.generation)
            } else {
                // A pending file from an earlier run that never adopted must not reach this adopt.
                voices.discardPendingBlocking(noteId)
            }
            if (stopReason.get() != null) throw ProcessingPaused()
            checkActive()
            storage.markSealedBlocking(noteId, document)
            marked = true
        } finally {
            if (!marked) storage.discardSealedBlocking(noteId)
        }
        return Sealed(labels, draft.names)
    }

    // Step 10 ---------------------------------------------------------------------------------------------------

    /**
     * Step 10: installs the sealed document. A stop that came after the marker still pauses: the sealed file goes,
     * the complete checkpoint stays. Once adopt starts it runs to its end ([NonCancellable]). A failed adopt ends
     * [JobOutcome.AdoptPending], never Failed: the marker and the sealed file stay for the coordinator (section 5.3).
     */
    private suspend fun adopt(sealed: Sealed, began: Long): JobOutcome {
        if (stopReason.get() != null) {
            withContext(env.io) { storage.discardSealedBlocking(noteId) }
            throw ProcessingPaused()
        }
        currentCoroutineContext().ensureActive()
        val finalizationMs = (env.nanoTime() - began) / NANOS_PER_MS
        val adoption = Adoption(sealed.labels, sealed.names.remembered, sealed.names.generation, finalizationMs)
        adopting = true
        return withContext(NonCancellable + env.io) {
            try {
                storage.adopt(noteId, adoption)
                JobOutcome.Done
            } catch (
                @Suppress("TooGenericExceptionCaught") // any adopt failure leaves an adoptable marker
                error: Exception,
            ) {
                env.warn("Adopt failed; the sealed document waits for the next try", error)
                JobOutcome.AdoptPending
            }
        }
    }

    /**
     * The audio of a fresh seal: the seal audio itself, or, when it carries Cassini tags (the `document.opus` of a
     * document that is not `ok`), a copy in `work/` with every `CASSINI_*` tag removed, packet for packet. The old
     * transcript bodies must not stay beside the new ones.
     */
    private fun freshAudio(run: Run, target: File): File {
        val audio = run.sealAudio
        val copy = run.files.encodedAudio
        // The re-encode of a non-Opus source is written by the job and has no Cassini tags.
        val comments = if (audio == copy) emptyList() else tags(audio)
        if (comments.none(::isCassiniTag)) return audio
        requireSpace(target, 2 * audio.length())
        OggOpus.rewrite(audio, CassiniDocument.tagsPacket(comments.filterNot(::isCassiniTag)), copy)
        return copy
    }

    private fun tags(audio: File): List<String> =
        audio.inputStream().buffered().use { CassiniDocument.readComments(OggOpus.headers(it).second) }

    private fun requireSpace(target: File, bytes: Long) {
        val space = target.absoluteFile.parentFile?.usableSpace ?: 0L
        requireUser(space >= bytes + Limits.MIN_FREE_BYTES, Failure.SPACE)
    }

    // Helpers ---------------------------------------------------------------------------------------------------

    private suspend fun <T> onInference(block: () -> T): T = runInterruptible(env.inference, block)

    /** The word body of [words] fits the format code; else the job ends with `Failure.LONG`. */
    private fun requireWordsFit(words: List<Word>) =
        requireUser(WordBodyBudget.bytes(words) <= env.maxBodyBytes, Failure.LONG)

    /**
     * Runs a write to the work files. An IOException there is SPACE when `work/` is low on space, else SAVE; only
     * reading the audio gives OPEN.
     */
    private inline fun <T> writing(block: () -> T): T = try {
        block()
    } catch (error: IOException) {
        env.warn("Could not write the work files", error)
        throw UserFacingException(writeFailure()).apply { initCause(error) }
    }

    /** Runs the read of the note's audio: an IOException there is OPEN ("couldn't open the audio"). */
    private inline fun <T> reading(block: () -> T): T = try {
        block()
    } catch (error: IOException) {
        env.warn("Could not read the audio", error)
        throw UserFacingException(Failure.OPEN).apply { initCause(error) }
    }

    /** SPACE when `work/` is low on space, else SAVE. */
    private fun writeFailure(): Failure {
        val free = storage.sealTarget(noteId).absoluteFile.parentFile?.usableSpace ?: 0L
        return if (free < Limits.MIN_FREE_BYTES) Failure.SPACE else Failure.SAVE
    }

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
            error is DocumentRefusal -> JobOutcome.Failed(JobFailure.DocumentRefused(error.state))
            error is ProcessingPaused || error is CancellationException ->
                JobOutcome.Paused(stopReason.get() ?: PauseReason.USER)
            error is UserFacingException -> JobOutcome.Failed(JobFailure.Processing(error.failure))
            // The audio read maps its own IOException to OPEN ([reading]); any other is the note's files or names.
            error is IOException -> {
                env.warn("Transcription I/O failed", error)
                JobOutcome.Failed(JobFailure.Processing(writeFailure()))
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

    /**
     * What step 1 found. [noteVoices] are the note-local voices of a Transcribe again; the saved voices are read again
     * at each match ([people]). [knownMs] is the row's duration, when known.
     */
    private class Prepared(
        val resumed: CheckpointState?,
        val audioFile: File,
        val noteVoices: List<Voice>,
        val generation: Long,
        val knownMs: Long?,
        val lowMemory: Boolean,
    )

    /** The voices one match runs against, and their names by voice id. */
    private class People(val voices: List<Voice>) {
        val names: Map<String, String> = voices.associate { it.id to it.name }

        companion object {
            val NONE = People(emptyList())
        }
    }

    /** One run's work files and native state, confined to the inference thread. */
    private class Run(
        val prepared: Prepared,
        val clips: PcmFile,
        /** `audio.opus.part` (the re-encode, or for an Ogg Opus source the [freshAudio] copy) and `clips.pcm`. */
        val files: WorkFiles,
        /** The audio the seal packages: the note's Ogg Opus file, or `work/audio.opus.part` for any other source. */
        val sealAudio: File,
        val speakerPrefix: String,
        /** [JobEnvironment.nanoTime] at the start, minus the checkpoint's elapsed time. */
        val began: Long,
    ) {
        /** The voiceprint model is installed: clips are kept and voices matched. */
        var printsReady = false
        val resumed: CheckpointState? get() = prepared.resumed

        /** The selected voice clips in [clips], restored from the checkpoint on a resume. */
        val clipCache = VoiceClipCache(clips, resumed?.voiceClips?.let(CheckpointState::json))

        /** The length progress starts from: the checkpoint's, else the row's, else unknown (0). */
        val initialTotalMs: Long get() = resumed?.durationMs?.takeIf { it > 0 } ?: prepared.knownMs ?: 0L
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
        /** Step 7 runs: a diarizer, a diarization record and the voiceprint model. */
        val matching: Boolean,
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

    /** Transcribe again over a document in [state], which the job must not replace (features.md section 6). */
    private class DocumentRefusal(val state: DocumentState) : RuntimeException("Document is ${state.code}")

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val READING_INTERVAL_NS = 250_000_000L
        const val MIN_DURATION_MS = 200L
        const val LIVE_MIN_SECONDS = 4.0
        const val LIVE_STEP_SECONDS = 10.0

        /** Any header that does not read as Ogg Opus means "re-encode", as in the 0.1.0 document store. */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        fun isOpus(file: File): Boolean = try {
            file.inputStream().use { OggOpus.headers(it) }
            true
        } catch (error: Exception) {
            false
        }

        /** Flushes the sealed file to disk before the marker that says it is complete. */
        fun sync(file: File) = RandomAccessFile(file, "rw").use { it.fd.sync() }

        /** Only an `ok` document takes a new variant (`CassiniDocument.create` refuses any other state). */
        fun extendable(document: CassiniDocument) = document.state == DocumentState.OK.code

        /**
         * The states that get a fresh document (features.md section 6, "Transcribe again"), plus plain audio, which has
         * no transcript to lose.
         */
        val REPLACEABLE = setOf(
            DocumentState.PLAIN_AUDIO,
            DocumentState.STALE_AUDIO,
            DocumentState.INVALID_METADATA,
            DocumentState.UNREADABLE,
        )

        /**
         * Refuses an UNVERIFIED or UNKNOWN_FORMAT document: it can not take a new variant, and the spec does not let a
         * fresh document drop its versions (0.1.0 refused every state but `ok`).
         */
        fun requireReplaceable(document: CassiniDocument) {
            val state = document.documentState
            if (state != DocumentState.OK && state !in REPLACEABLE) throw DocumentRefusal(state)
        }

        fun isCassiniTag(comment: String) =
            comment.substringBefore('=').uppercase(Locale.ROOT).startsWith(CASSINI_TAG_PREFIX)

        const val CASSINI_TAG_PREFIX = "CASSINI_"
    }
}
