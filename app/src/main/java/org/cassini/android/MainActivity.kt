package org.cassini.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.Selection
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ui: DeckViews
    private lateinit var models: ModelStore
    private lateinit var sessions: SessionStore
    private lateinit var library: LibraryStore
    private var autoTranscribe = false
    private var session = Session()
    private var document: CassiniDocument? = null
    private lateinit var documents: DocumentStore
    private var player: MediaPlayer? = null
    private var playerReady = false
    private var cancellation: java.util.concurrent.atomic.AtomicBoolean? = null
    private var busy = false
    private val stopRequested = java.util.concurrent.atomic.AtomicBoolean()
    private var leaveAfterStopping = false
    @Volatile private var checkpointSaved = false
    private var checkpointUnreadable = false
    private var checkpointDoneMs = 0L
    private var batteryProjection = BatteryProjection()
    private var batteryReading: BatteryProjection.Reading? = null
    private var finalizationEstimateMs = 0L
    private var finalizationAtMs = -1L
    /** Live words; durable snapshots are saved at completed chunk boundaries. */
    private var interim: Transcript? = null
    private var transcriptionProgress: TranscriptionProgress? = null
    private var transcriptionBeganNs = 0L
    private var progressRenderedSecond = -1L
    private val draftSpeakerLabels = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val draftRememberedVoices = mutableMapOf<String, String>()
    private val draftAutomaticNames = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var draftSpeakerMerges = emptyMap<String, String>()
    @Volatile private var draftNamingOpen = false
    private var highlightedWord = -1
    private var wordRanges = emptyList<IntRange>()
    private val activeBackground = BackgroundColorSpan(DeckViews.amber)
    private val activeForeground = ForegroundColorSpan(DeckViews.ink)
    private var touchingTranscript = false
    private var followCompletedSeeks = false
    private var screen = ""
    /** The speaker whose name is being edited, kept across recreation until the document is open again. */
    private var pendingSpeaker: String? = null
    private var pendingName: String? = null
    private var speakerDialog: AlertDialog? = null
    /** This note's speaker prints and matches and the saved people, app-private; reloaded when a document is adopted. */
    private var notePrints: Map<String, SpeakerPrint> = emptyMap()
    private var people: Map<String, Voice> = emptyMap()
    private val app get() = application as CassiniApplication

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    private val ticker = object : Runnable {
        override fun run() {
            if (playerReady) player?.let { media ->
                updatePlayback(media)
            }
            if (transcriptionProgress != null && busy) {
                val second = (System.nanoTime() - transcriptionBeganNs) / 1_000_000_000
                if (second != progressRenderedSecond) renderTranscriptionProgress()
            }
            handler.postDelayed(this, 150)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessions = SessionStore(filesDir)
        library = LibraryStore(filesDir)
        documents = DocumentStore(this)
        val preferences = sessions.load()
        val noteId = intent.getStringExtra(NOTE_ID)
        screen = savedInstanceState?.getString("screen") ?: UUID.randomUUID().toString()
        pendingSpeaker = savedInstanceState?.getString("pendingSpeaker"); pendingName = savedInstanceState?.getString("pendingName")
        val restored = savedInstanceState?.let { state ->
            val notes = try { library.load() } catch (error: Exception) { Log.e(TAG, "Could not load notes", error); emptyList() }
            LibraryNote.shown(screen, state.getString(NOTE_ID), state.getString("uri"), preferences, notes)
        }
        val importing = intent.getBooleanExtra(REQUEST_IMPORT, false)
        // A recreated activity keeps its launch intent. It is imported again only while this screen has no note yet.
        val viewing = restored == null && intent.action == Intent.ACTION_VIEW && intent.data != null
        val selected = when {
            restored != null -> restored
            // An import starts a new note. The last one must not sit behind the file picker or the file being opened.
            importing || viewing -> Session()
            noteId == null -> preferences
            else -> try { library.load().firstOrNull { it.id == noteId }?.session ?: Session() }
                catch (error: Exception) { Log.e(TAG, "Could not load note", error); Session() }
        }
        session = selected
        autoTranscribe = savedInstanceState == null && intent.getBooleanExtra(AUTO_TRANSCRIBE, false)
        models = ModelStore(File(filesDir, "parakeet-v3"))
        ui = DeckViews(this)
        setContentView(ui.root)
        ui.library.setOnClickListener { returnToLibrary() }
        ui.menu.setOnClickListener { showMenu() }
        ui.transcribe.setOnClickListener { confirmTranscription() }
        ui.cancelOperation.setOnClickListener { cancelOperation() }
        ui.play.setOnClickListener {
            if (playerReady) player?.let {
                if (it.isPlaying) it.pause() else { it.start(); highlightWord() }
            }
        }
        ui.scroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> touchingTranscript = true
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> touchingTranscript = false
            }
            false
        }
        ui.back.setOnClickListener { skip(-10_000) }
        ui.forward.setOnClickListener { skip(10_000) }
        ui.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) seekTo(value)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        ui.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderTranscript() }
            override fun afterTextChanged(s: Editable?) {}
        })
        ui.clearSearch.setOnClickListener { ui.search.text.clear() }
        ui.transcript.movementMethod = LinkMovementMethod.getInstance()
        ui.search.setText(savedInstanceState?.getString("query") ?: intent.getStringExtra(SEARCH_QUERY).orEmpty())
        restoreCheckpoint()
        renderScreen()
        defaultStatus()
        session.uri?.let { preparePlayer(Uri.parse(it)) }
        session.document?.takeIf { !viewing }?.let { path -> runWork(R.string.opening_document) {
            val loaded = CassiniDocument.read(File(path))
            onUi {
                adoptDocument(loaded); renderScreen(); defaultStatus()
                pendingSpeaker?.let { speaker -> handler.post { if (!isDestroyed) showSpeakerDialog(speaker, pendingName) } }
            }
        } }
        if (viewing) intent.data?.let { importFile(it) }
        if (importing && savedInstanceState == null) chooseFile()
        savedInstanceState?.let { state -> ui.scroll.post { ui.scroll.scrollTo(0, state.getInt("scroll")) } }
    }

    private fun chooseFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "application/ogg")); addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, OPEN_FILE)
    }

    /** The first transcription asks once before the large download; later ones quietly retry any optional model still missing. */
    private fun confirmTranscription() {
        if (busy || session.uri == null) return
        val bundle = ModelBundle.inFiles(filesDir)
        if (bundle.consented()) transcribe()
        else AlertDialog.Builder(this).setTitle(R.string.models_download)
            .setMessage(getString(R.string.models_download_summary, bundle.missingBytes().let {
                getString(R.string.models_size_mib, ModelBundle.mebibytes(it)) }))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.download_and_transcribe) { _, _ -> transcribe() }.show()
    }

    private fun cancelOperation() {
        stopRequested.set(true)
        ui.cancelOperation.isEnabled = false
        ui.cancelOperation.setText(R.string.stopping_processing)
    }
    private fun checkpointStore() = session.uri?.let { ProcessingCheckpoint(filesDir, it) }
    private fun saveDraftNames() {
        synchronized(draftSpeakerLabels) { checkpointStore()?.saveNames(draftSpeakerLabels.toMap(), draftSpeakerMerges, draftRememberedVoices.toMap(), draftAutomaticNames.toMap()) }
    }
    private fun restoreCheckpoint() {
        val store = checkpointStore() ?: return
        val saved = try { store.load() } catch (error: Exception) { checkpointUnreadable = true; checkpointSaved = true; Log.e(TAG, "Could not read processing checkpoint", error); setStatus(R.string.checkpoint_error, error = true); return }
        checkpointUnreadable = false
        checkpointSaved = saved != null
        if (saved == null) return
        checkpointDoneMs = saved.getLong("doneMs")
        val raw = saved.getJSONObject("recognition").getJSONObject("chunks").getJSONObject("words")
        interim = Transcript.fromJson(raw.toString())
        val (labels, merges) = store.loadNames()
        val (remembered, automatic) = store.nameExtras()
        draftRememberedVoices.clear(); draftRememberedVoices.putAll(remembered)
        draftAutomaticNames.clear(); draftAutomaticNames.putAll(automatic)
        synchronized(draftSpeakerLabels) { draftSpeakerLabels.clear(); draftSpeakerLabels.putAll(labels); draftSpeakerMerges = merges; draftNamingOpen = true }
        session = session.copy(processingPaused = true, transcript = if (session.document == null) draftTranscript() else session.transcript, inferenceMs = saved.getJSONObject("recognition").getLong("inferenceMs"), processingMs = saved.getLong("elapsedMs"))
        ui.cancelOperation.setText(R.string.stop_processing)
        ui.cancelOperation.isEnabled = true
    }
    private fun discardCheckpoint() {
        AlertDialog.Builder(this).setTitle(R.string.discard_processing).setMessage(R.string.discard_processing_confirm)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.discard_processing) { _, _ ->
                checkpointStore()?.discard(); checkpointSaved = false; checkpointUnreadable = false; interim = null; draftNamingOpen = false
                session = session.copy(processingPaused = false, transcript = if (session.document == null) null else session.transcript)
                persistSession(); renderScreen(); defaultStatus()
            }.show()
    }

    private fun showDownload(progress: ModelStore.Progress, label: Int = R.string.downloading) {
        ui.progress.isIndeterminate = false
        ui.progress.progress = progress.percent
        setStatus(if (progress.verifying) R.string.verifying else label, progress.percent)
    }

    /** One action: stream audio through encoding, recognition and speaker attribution. */
    private fun transcribe() {
        if (busy || (document != null && document?.state != "ok")) return
        val uri = session.uri?.let(Uri::parse) ?: return
        val chosenModel = models
        val knownVoices = voicesForRecognition()
        val checkpoint = ProcessingCheckpoint(filesDir, uri.toString())
        val resumed = try { checkpoint.load() } catch (_: Exception) { setStatus(R.string.checkpoint_error, error = true); return }
        stopRequested.set(false)
        ui.cancelOperation.setText(R.string.stop_processing)
        ui.cancelOperation.isEnabled = true
        var finishing = false
        val speakerModels = DiarizationModels.inFiles(filesDir)
        val voiceprintModel = VoiceprintModel.inFiles(filesDir)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        cancellation = cancelled
        ui.cancelOperation.visibility = View.VISIBLE
        fun checkActive() {
            requireUser(!cancelled.get() && !Thread.currentThread().isInterrupted, Failure.CANCELLED)
            if (finishing && stopRequested.get()) throw ProcessingPaused()
        }
        fun download(install: ((ModelStore.Progress) -> Unit) -> Unit) = install { progress -> onUi { if (!cancelled.get()) showDownload(progress) } }
        runWork(if (chosenModel.ready()) R.string.decoding else R.string.preparing_download) {
            if (!chosenModel.ready()) { download(chosenModel::install); onUi { fetchSpeechDetector() } }
            checkActive()
            val speakersReady = speakerModels.ready() || try { download(speakerModels::install); true }
                catch (error: IOException) { Log.w(TAG, "Speaker model unavailable", error); false }
            checkActive()
            // Voiceprints only name speakers; without the model the transcript is unchanged.
            if (speakersReady && !voiceprintModel.ready()) try {
                voiceprintModel.install { progress -> onUi { if (!cancelled.get()) showDownload(progress, R.string.voiceprint_model_progress) } }
            } catch (error: IOException) { Log.w(TAG, "Voiceprint model unavailable", error) }
            catch (error: UserFacingException) { if (error.failure == Failure.CANCELLED) throw error; Log.w(TAG, "Voiceprint model unavailable", error) }
            checkActive()
            onUi { ui.progress.isIndeterminate = true; setStatus(R.string.decoding) }
            val totalBegan = System.nanoTime() - (resumed?.optLong("elapsedMs") ?: 0L) * 1_000_000
            onUi {
                transcriptionBeganNs = totalBegan
                transcriptionProgress = TranscriptionProgress(session.durationMs)
                batteryProjection = BatteryProjection()
                batteryReading = null
                finalizationAtMs = -1L
                finalizationEstimateMs = getSharedPreferences("processing-estimates", MODE_PRIVATE).getLong("finalizationMs", 0)
                resumed?.let { saved ->
                    val words = Transcript.fromJson(saved.getJSONObject("recognition").getJSONObject("chunks").getJSONObject("words").toString()).words
                    transcriptionProgress?.restore(saved.getLong("doneMs"), saved.getLong("durationMs"), words.size, saved.getLong("elapsedMs"))
                }
                session = session.copy(processingPaused = false)
                ui.status.minLines = 5
                renderTranscriptionProgress()
            }
            checkpoint.directory.mkdirs()
            if (resumed == null) { checkpoint.audio.delete(); checkpoint.clips.delete() }
            PcmFile(checkpoint.clips, deleteOnClose = false).use { audio ->
                resumed?.let { audio.truncate(it.getInt("clipSamples")) }
                val voiceClips = VoiceClipCache(audio, resumed?.optJSONObject("voiceClips"))
                val encoded = checkpoint.audio
                var encoder: StreamingOpusEncoder? = null
                var durationMs = 0L
                try {
                    if (!documents.hasOpusAudio(uri)) encoder = StreamingOpusEncoder(encoded, resumed?.optJSONObject("encoder"))
                    val speakerPrefix = resumed?.getString("speakerPrefix") ?: "diar_${UUID.randomUUID().toString().replace('-', '_')}_"
                    var streamDiarizer: Diarization.Streaming? = null
                    var speakerFailure = resumed?.optBoolean("speakerFailure") ?: !speakersReady
                    onUi { synchronized(draftSpeakerLabels) {
                        if (resumed == null) { draftSpeakerLabels.clear(); draftRememberedVoices.clear(); draftAutomaticNames.clear(); draftSpeakerMerges = emptyMap(); checkpoint.saveNames(emptyMap(), emptyMap()) }
                        draftNamingOpen = true
                    } }
                    val detector = ModelStore.vadPath(filesDir).takeIf { ModelStore.vadReady(filesDir) }
                    var lastDecodeUpdate = 0L
                    val livePrints = mutableMapOf<String, Pair<FloatArray, Double>>()
                    val liveAttemptSeconds = mutableMapOf<String, Double>()
                    val rememberedPeople = knownVoices
                    fun matchLiveVoices() {
                        if (rememberedPeople.isEmpty() || !voiceprintModel.ready()) return
                        val windows = SpeakerMerges.windows(voiceClips.windows, synchronized(draftSpeakerLabels) { draftSpeakerMerges.toMap() })
                        livePrints.keys.retainAll(windows.keys)
                        val pending = windows.filter { (id, spans) ->
                            val seconds = spans.sumOf { it.endSample - it.startSample }.toDouble() / Limits.ASR_RATE
                            val attempted = liveAttemptSeconds[id] ?: 0.0
                            seconds >= 4 && (attempted == 0.0 || seconds >= attempted + 10)
                        }
                        if (pending.isEmpty()) return
                        try {
                            val computed = Voiceprints.compute(File(voiceprintModel.modelPath), audio, pending, leaseHeld = true) { checkActive() }
                            computed.forEach { (id, print) -> liveAttemptSeconds[id] = print.second; livePrints[id] = print }
                            val names = rememberedPeople.associate { it.id to it.name }
                            val matches = VoiceMatcher.match(livePrints.mapValues { it.value.first }, rememberedPeople,
                                VoiceprintModel.model.sha256, Voiceprints.THRESHOLDS.getValue(VoiceprintModel.model.sha256))
                            onUi {
                                draftAutomaticNames.clear()
                                matches.filterValues { it.state == Match.State.AUTO }.forEach { (id, match) -> names[match.voiceId]?.let { draftAutomaticNames[id] = it } }
                                saveDraftNames()
                            }
                        } catch (error: UserFacingException) { if (error.failure == Failure.CANCELLED) throw error; Log.w(TAG, "Live voice matching unavailable", error) }
                        catch (error: Exception) { Log.w(TAG, "Live voice matching unavailable", error) }
                    }
                    val rawTranscript: Transcript
                    val elapsed: Long
                    NativeInference.acquire()
                    try {
                        if (resumed?.optJSONObject("diarizer") != null) streamDiarizer = Diarization.Streaming(speakerModels.modelPath, resumed.getJSONObject("diarizer"))
                        Parakeet.Incremental(chosenModel, detector, leaseHeld = true, restored = resumed?.getJSONObject("recognition"), onChunk = { chunk, startMs, words ->
                            checkActive()
                            if (speakerFailure) words.copy(words = words.words.map { it.copy(speaker = "${speakerPrefix}1") })
                            else try {
                                onUi {
                                    if (!cancelled.get()) transcriptionProgress?.stage(TranscriptionProgress.Stage.SPEAKERS,
                                        (System.nanoTime() - totalBegan) / 1_000_000, startMs, startMs + chunk.durationMs)
                                }
                                val diarizer = streamDiarizer ?: Diarization.Streaming(speakerModels.modelPath).also { streamDiarizer = it }
                                val found = diarizer.process(chunk, startMs)
                                val assigned = if (found.isEmpty()) words.words.map { it.copy(speaker = "${speakerPrefix}1") }
                                    else Diarization.assign(words.words, found, speakerPrefix, stableIds = true)
                                if (voiceprintModel.ready()) voiceClips.accept(chunk, found, assigned, startMs)
                                matchLiveVoices()
                                words.copy(words = assigned)
                            } catch (error: UserFacingException) {
                                if (error.failure == Failure.CANCELLED) throw error
                                speakerFailure = true
                                Log.w(TAG, "Incremental speakers unavailable", error)
                                words.copy(words = words.words.map { it.copy(speaker = "${speakerPrefix}1") })
                            } catch (error: Exception) {
                                speakerFailure = true
                                Log.w(TAG, "Incremental speakers unavailable", error)
                                words.copy(words = words.words.map { it.copy(speaker = "${speakerPrefix}1") })
                            }
                        }, onStage = { startMs, endMs, loading ->
                            checkActive()
                            onUi {
                                if (!cancelled.get()) transcriptionProgress?.stage(
                                    if (loading) TranscriptionProgress.Stage.PREPARING else TranscriptionProgress.Stage.WORDS,
                                    (System.nanoTime() - totalBegan) / 1_000_000, startMs, endMs)
                            }
                        }) { progress ->
                            checkActive()
                            onUi { if (!cancelled.get()) showProgress(progress) }
                        }.use { recognition ->
                            var previousSaved = resumed?.optLong("doneMs") ?: -1L
                            fun saveCheckpoint() {
                                audio.sync()
                                val state = JSONObject().put("recognition", recognition.checkpoint())
                                    .put("encoder", encoder?.checkpoint()).put("diarizer", streamDiarizer?.checkpoint())
                                    .put("speakerPrefix", speakerPrefix).put("speakerFailure", speakerFailure)
                                    .put("clipSamples", audio.sampleCount).put("voiceClips", voiceClips.checkpoint())
                                    .put("positionSamples", recognition.positionSamples).put("doneMs", recognition.doneMs)
                                    .put("durationMs", recognition.totalMs).put("elapsedMs", (System.nanoTime() - totalBegan) / 1_000_000)
                                checkpoint.save(state)
                                previousSaved = recognition.doneMs
                                checkpointSaved = true
                            }
                            durationMs = AudioDecoder.stream(this, uri, onSamples = { samples, count ->
                                checkActive()
                                encoder?.accept(samples, count)
                                recognition.accept(samples, count)
                                if (recognition.doneMs > previousSaved || stopRequested.get()) saveCheckpoint()
                                if (stopRequested.get()) throw ProcessingPaused()
                            }, startSample = resumed?.optLong("positionSamples") ?: 0L) { decodedMs, durationMs ->
                                checkActive()
                                recognition.totalMs = durationMs ?: session.durationMs
                                val now = System.nanoTime()
                                if (now - lastDecodeUpdate >= 250_000_000L) {
                                    lastDecodeUpdate = now
                                    onUi {
                                        if (!cancelled.get()) {
                                            transcriptionProgress?.total(durationMs ?: recognition.totalMs)
                                            transcriptionProgress?.stage(TranscriptionProgress.Stage.READING,
                                                (now - totalBegan) / 1_000_000, end = decodedMs)
                                        }
                                    }
                                }
                            }
                            requireUser(durationMs >= 200, Failure.SHORT)
                            rawTranscript = recognition.finish(durationMs)
                            elapsed = recognition.inferenceMs
                            saveCheckpoint()
                            finishing = true
                            checkActive()
                            encoder?.finish()
                            encoder?.close()
                        }
                    } finally {
                        try { streamDiarizer?.close() } finally { NativeInference.lease.release() }
                    }
                    val finalizationBeganNs = System.nanoTime()
                    val draftRemember = synchronized(draftSpeakerLabels) { draftRememberedVoices.toMap() }
                    val draftEdits = synchronized(draftSpeakerLabels) {
                        draftNamingOpen = false
                        draftSpeakerLabels.toMap() to draftSpeakerMerges.toMap()
                    }
                    onUi { speakerDialog?.dismiss(); renderTranscript() }
                    val transcript = SpeakerMerges.apply(rawTranscript, draftEdits.second)
                    val turns = streamDiarizer?.turns
                    val diarizationMs = streamDiarizer?.elapsedMs ?: 0L
                    checkActive()
                    // Processing records are machine-readable provenance, independent of UI language.
                    try {
                        File(filesDir, "latest.words.json").writeText(transcript.json())
                        File(filesDir, "latest.processing.json").writeText(processing(chosenModel.precision, chosenModel.revision).put("language", transcript.language)
                            .put("durationMs", durationMs).put("inferenceMs", elapsed)
                            .put("segmentation", Parakeet.incrementalProvenance(detector))
                            .toString(2))
                    } catch (error: IOException) { Log.e(TAG, "Could not cache processing artifacts", error) }
                    val recognition = processing(chosenModel.precision, chosenModel.revision).put("language", transcript.language)
                        .put("x-inferenceMs", elapsed).put("x-segmentation", Parakeet.incrementalProvenance(detector))
                        .put("x-resumedFromMs", resumed?.optLong("doneMs") ?: 0L)
                    val attributed = if (turns.isNullOrEmpty() || transcript.words.isEmpty()) null else
                        SpeakerAttribution.Result(transcript, SpeakerAttribution.record(transcript.words, turns, recognition, null, diarizationMs)
                            .put("x-incrementalDiarization", "28 s windows with 2 s overlap, persistent Sortformer speaker cache"))
                    checkActive()
                    val speakerTurns = turns
                    val printModel = VoiceprintModel.model.sha256
                    // Naming speakers is best effort, like telling them apart: any failure keeps "Speaker N".
                    val prints: Map<String, SpeakerPrint> = if (speakerTurns == null || attributed == null || !voiceprintModel.ready()) emptyMap() else try {
                        onUi { transcriptionProgress?.stage(TranscriptionProgress.Stage.MATCHING, (System.nanoTime() - totalBegan) / 1_000_000) }
                        val computed = Voiceprints.compute(File(voiceprintModel.modelPath), audio,
                            SpeakerMerges.windows(voiceClips.windows, draftEdits.second)) { checkActive() }
                        val matches = VoiceMatcher.match(computed.mapValues { it.value.first }, knownVoices, printModel, Voiceprints.THRESHOLDS.getValue(printModel))
                        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) for ((id, print) in computed)
                            Log.d(TAG, "Voiceprint $id ${"%.1f".format(java.util.Locale.ROOT, print.second)} s: ${matches[id]?.let { "${it.state} ${"%.3f".format(java.util.Locale.ROOT, it.score)}" } ?: "no match"}")
                        computed.mapValues { (id, print) -> SpeakerPrint(print.first, print.second, matches[id]) }
                    } catch (error: UserFacingException) { if (error.failure == Failure.CANCELLED) throw error; Log.w(TAG, "Voiceprints failed", error); emptyMap() }
                    catch (error: RuntimeException) { Log.w(TAG, "Voiceprints failed", error); emptyMap() }
                    catch (error: OutOfMemoryError) { Log.w(TAG, "Voiceprints failed", error); emptyMap() }
                    checkActive()
                    val names = knownVoices.associate { it.id to it.name }
                    val draftNames = draftEdits.first
                    val labels = transcript.words.map { it.speaker }.distinct().mapIndexed { index, id ->
                        id to (draftNames[id] ?: prints[id]?.match?.takeIf { it.state == Match.State.AUTO }?.let { names[it.voiceId] } ?: getString(R.string.speaker_label, index + 1))
                    }.toMap()
                    // Counts only: which person a voice resembles never enters the document.
                    if (attributed != null && speakerTurns != null && voiceprintModel.ready()) {
                        val t = Voiceprints.THRESHOLDS.getValue(printModel)
                        attributed.processing.put("x-speakerIdentification", JSONObject()
                            .put("model", "3dspeaker_speech_campplus_sv_en_voxceleb_16k").put("sha256", printModel).put("dim", prints.values.firstOrNull()?.embedding?.size ?: 512)
                            .put("metric", "cosine").put("autoThreshold", t.auto.toDouble()).put("suggestThreshold", t.suggest.toDouble()).put("margin", t.margin.toDouble())
                            .put("speakersWithPrints", prints.size).put("autoApplied", prints.values.count { it.match?.state == Match.State.AUTO && it.match.voiceId in names })
                            .put("suggested", prints.values.count { it.match?.state == Match.State.SUGGESTED }))
                    }
                    onUi { transcriptionProgress?.stage(TranscriptionProgress.Stage.SAVING, (System.nanoTime() - totalBegan) / 1_000_000) }
                    onUi { draftNamingOpen = false; speakerDialog?.dismiss() }
                    val sourceUri = if (encoder != null) Uri.fromFile(encoded) else uri
                    val finalProcessing = (attributed?.processing ?: recognition).let {
                        if (draftEdits.second.isEmpty()) it else SpeakerMerges.processing(it, draftEdits.second)
                    }
                    val (file, portable) = documents.createFromOpus(sourceUri, attributed?.transcript ?: transcript, session.name,
                        finalProcessing, document, labels)
                    if (stopRequested.get()) { file.delete(); throw ProcessingPaused() }
                    val processingMs = (System.nanoTime() - totalBegan) / 1_000_000
                    val measuredFinalizationMs = (System.nanoTime() - finalizationBeganNs) / 1_000_000
                    // A completed native call may outlive its screen. Never publish a cancelled result,
                    // and remove only this job's new immutable document when it cannot be adopted.
                    handler.post {
                        if (isDestroyed || isFinishing || cancelled.get() || stopRequested.get()) file.delete()
                        else {
                            val position = if (playerReady) player?.currentPosition ?: session.positionMs else session.positionMs
                            interim = null
                            session = session.copy(uri = Uri.fromFile(file).toString(), document = file.absolutePath,
                                name = "${session.name.substringBeforeLast('.')}.opus", selectedVariant = portable.defaultId,
                                durationMs = durationMs, inferenceMs = elapsed, resultPrecision = chosenModel.precision, positionMs = position,
                                processingMs = processingMs, processingPaused = false)
                            getSharedPreferences("processing-estimates", MODE_PRIVATE).edit().putLong("finalizationMs",
                                if (finalizationEstimateMs > 0) (finalizationEstimateMs * 3 + measuredFinalizationMs) / 4 else measuredFinalizationMs).apply()
                            adoptDocument(portable)
                            preparePlayer(Uri.fromFile(file))
                            persistSession()
                            checkpoint.discard(); checkpointSaved = false
                            portable.manifest?.optJSONObject("meeting")?.optString("id")?.takeIf { it.isNotEmpty() && prints.isNotEmpty() }?.let { meeting ->
                                try { app.noteVoices.merge(meeting, portable.defaultId ?: return@let, printModel, prints) } catch (error: Exception) { Log.w(TAG, "Could not save voiceprints", error) }
                            }
                            loadVoices()
                            draftRemember.forEach { (id, voice) -> labels[id]?.let { name -> rememberVoice(id, name, voice.takeIf { it.isNotEmpty() }, true) } }
                            renderScreen()
                            if (attributed == null && transcript.words.isNotEmpty()) setStatus(R.string.speakers_unavailable) else defaultStatus()
                        }
                    }
                } finally { encoder?.close() }
            }
        }
    }

    private fun processing(precision: String, revision: String? = null): JSONObject = JSONObject()
        .put("backend", "sherpa-onnx").put("engine", "Parakeet TDT")
        .put("model", "nvidia/parakeet-tdt-0.6b-v3 $precision")
        .put("device", "Android CPU").put("language", "it")
        .put("source", "recording").put("version", "sherpa-onnx 1.13.7${revision?.let { "; model revision $it" }.orEmpty()}")

    private fun saveCassiniDocument() {
        if (session.document == null) {
            val transcript = session.transcript ?: return
            val uri = session.uri?.let(Uri::parse) ?: return
            // Migrate prototype sessions without running speech recognition again.
            runWork(R.string.packaging_document) {
                val (file, portable) = documents.create(uri, { AudioDecoder.decode(this, uri) }, transcript, session.name, processing(session.resultPrecision).put("x-inferenceMs", session.inferenceMs), null)
                onUi {
                    val position = if (playerReady) player?.currentPosition ?: session.positionMs else session.positionMs
                    session = session.copy(uri = Uri.fromFile(file).toString(), document = file.absolutePath,
                        name = "${session.name.substringBeforeLast('.')}.opus", selectedVariant = portable.defaultId, positionMs = position)
                    adoptDocument(portable); preparePlayer(Uri.fromFile(file)); persistSession(); renderScreen(); defaultStatus()
                    saveDocumentPicker()
                }
            }
        } else saveDocumentPicker()
    }

    private fun saveDocumentPicker() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "audio/ogg"; addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, "${session.name.ifBlank { getString(R.string.recording) }.substringBeforeLast('.')}.opus")
        }, SAVE_DOCUMENT)
    }

    private fun importFile(uri: Uri) {
        if (busy) return
        val name = try { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } } catch (_: Exception) { null }
        runWork(R.string.opening_document) {
            val (file, portable) = documents.import(uri)
            onUi {
                val location = Uri.fromFile(file).toString()
                // Imports are named by content. Opening a file that is already on screen changes nothing.
                if (session.uri != location) {
                    // A file opened before returns to its note, with its position and chosen transcript.
                    val known = try { library.load().firstOrNull { it.session.uri == location }?.session }
                        catch (error: Exception) { Log.e(TAG, "Could not load note", error); null }
                    playerReady = false; player?.release(); player = null
                    session = (known ?: Session(uri = location, name = name ?: uri.lastPathSegment.orEmpty(),
                        document = file.absolutePath.takeIf { portable.state != "plain-audio" }, selectedVariant = portable.defaultId,
                        resultPrecision = ""))
                    ui.search.text.clear(); ui.scroll.scrollTo(0, 0)
                    preparePlayer(Uri.fromFile(file))
                }
                adoptDocument(portable.takeIf { it.state != "plain-audio" }); restoreCheckpoint()
                persistSession(); renderScreen(); defaultStatus()
            }
        }
    }

    private fun adoptDocument(portable: CassiniDocument?) {
        document = portable
        if (portable != null) {
            val selected = portable.selected(session.selectedVariant)
            val inference = portable.manifest?.optJSONObject("provenance")?.optJSONObject("speechToText")
                ?.optJSONObject(selected?.id.orEmpty())?.optLong("x-inferenceMs", session.inferenceMs) ?: session.inferenceMs
            session = session.copy(transcript = selected?.transcript, selectedVariant = selected?.id, inferenceMs = inference)
        }
        loadVoices()
        highlightedWord = -1
    }

    private fun meetingId() = document?.manifest?.optJSONObject("meeting")?.optString("id")?.takeIf { it.isNotEmpty() }

    private fun loadVoices() {
        val meeting = meetingId(); val variant = session.selectedVariant
        notePrints = if (meeting == null || variant == null) emptyMap() else try { app.noteVoices.load(meeting, variant) }
            catch (error: Exception) { Log.w(TAG, "Could not load voiceprints", error); emptyMap() }
        people = app.voices.load().associateBy { it.id }
    }

    /** Retain names when reprocessing this meeting, even if the user chose not to remember them globally. */
    private fun voicesForRecognition(): List<Voice> {
        val saved = app.voices.load()
        val model = VoiceprintModel.model.sha256
        val local = notePrints.mapNotNull { (id, print) ->
            val label = document?.speakerLabel(id) ?: return@mapNotNull null
            if (isDefaultLabel(id, label) || print.match?.state == Match.State.REJECTED ||
                print.match?.voiceId in saved.map { it.id }) return@mapNotNull null
            Voice("note_$id", label, model, print.embedding, 1, print.seconds, 0, 0)
        }
        return saved + local
    }

    /** The suggestion or auto mark after a speaker label: a saved person this voice resembles. */
    private fun speakerMark(speaker: String, label: String): Pair<String, () -> Unit>? {
        val match = notePrints[speaker]?.match ?: return null
        val person = people[match.voiceId] ?: return null
        return when {
            match.state == Match.State.SUGGESTED && isDefaultLabel(speaker, label) ->
                getString(R.string.speaker_suggestion, person.name) to { applySpeakerName(speaker, person.name, person.id, remember = true) }
            match.state == Match.State.AUTO && label == person.name -> getString(R.string.speaker_auto_mark) to { showSpeakerDialog(speaker) }
            else -> null
        }
    }

    private fun canRename() = (interim != null && draftNamingOpen) || (document?.state == "ok" && interim == null && !busy)

    /** "Speaker 2" in any interface language: a note sealed before the language changed still has no name. */
    private val defaultLabels by lazy {
        listOf("en", "it").map { tag -> createConfigurationContext(android.content.res.Configuration(resources.configuration)
            .apply { setLocale(Locale.forLanguageTag(tag)) }).getString(R.string.speaker_label, 0).substringBefore('0') }.toSet()
    }
    private fun isDefaultLabel(id: String, label: String) = label == id || label.isBlank() ||
        defaultLabels.any { label.startsWith(it) && label.removePrefix(it).toIntOrNull() != null }

    private fun showSpeakers() {
        if (document?.manifest == null) return
        val speakers = session.transcript?.words.orEmpty().map { it.speaker }.distinct()
        AlertDialog.Builder(this).setTitle(R.string.speakers_menu)
            .setItems(speakers.map { document?.speakerLabel(it)?.ifBlank { it } ?: it }.toTypedArray()) { _, index -> showSpeakerDialog(speakers[index]) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showSpeakerDialog(speakerId: String, draft: String? = null) {
        if (interim != null && draftNamingOpen) { showDraftSpeakerDialog(speakerId, draft); return }
        if (!canRename()) return
        val current = document?.speakerLabel(speakerId) ?: return
        val input = SpeakerDialog.nameInput(this, draft ?: current.takeUnless { isDefaultLabel(speakerId, it) }.orEmpty())
        val print = notePrints[speakerId]
        val match = print?.match?.takeIf { it.state != Match.State.REJECTED && it.state != Match.State.NONE && it.voiceId in people }
        // A label that already names the matched person keeps that person unless the name is changed.
        var chosen: String? = match?.voiceId?.takeIf { people[it]?.name == input.text.toString().trim() }
        val model = VoiceprintModel.model.sha256
        val t = Voiceprints.THRESHOLDS.getValue(model)
        val ranked = people.values.filter { it.model == model }.map { it to (print?.let { p -> Voiceprints.cosine(p.embedding, it.mean) } ?: 0f) }
            .sortedWith(compareByDescending<Pair<Voice, Float>> { it.second }.thenBy { it.first.name.lowercase() })
        val list = SpeakerDialog.people(this, ranked.map { (voice, score) ->
            (if (score >= t.suggest) getString(R.string.speaker_person_likely, voice.name) else voice.name) to {
                input.setText(voice.name); input.setSelection(input.text.length); chosen = voice.id }
        })
        val remember = SpeakerDialog.remember(this, print != null)
        val builder = AlertDialog.Builder(this).setTitle(R.string.rename_speaker_title)
            .setView(SpeakerDialog.frame(this, SpeakerDialog.column(this, input, mergeChoices(speakerId), list, remember)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> applySpeakerName(speakerId, input.text.toString().trim(), chosen, remember.isChecked) }
        if (match != null) builder.setNeutralButton(R.string.speaker_not_this_person) { _, _ -> rejectMatch(speakerId) }
        val dialog = builder.create()
        // Dismissal is delivered later; only the dialog still on screen may clear the draft.
        dialog.setOnDismissListener { if (speakerDialog === dialog && !isChangingConfigurations) { speakerDialog = null; pendingSpeaker = null; pendingName = null } }
        speakerDialog?.dismiss()
        speakerDialog = dialog; pendingSpeaker = speakerId; pendingName = draft
        dialog.show()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        // Keeping the same name still confirms an unconfirmed match.
        fun validate() { save.isEnabled = input.text.isNotBlank() && (input.text.toString().trim() != current ||
            print != null && print.match?.state != Match.State.CONFIRMED) }
        validate()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pendingName = s?.toString(); if (chosen != null && people[chosen]?.name != s?.toString()?.trim()) chosen = null; validate() }
            override fun afterTextChanged(s: Editable?) {}
        })
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    /** Draft names are keyed by the diarizer's persistent speaker ID and sealed with the result. */
    private fun showDraftSpeakerDialog(speakerId: String, draft: String?) {
        val current = draftSpeakerLabels[speakerId] ?: draftAutomaticNames[speakerId] ?: defaultLabel(speakerId)
        val input = SpeakerDialog.nameInput(this, draft ?: draftSpeakerLabels[speakerId] ?: draftAutomaticNames[speakerId].orEmpty())
        val savedPeople = app.voices.load().sortedBy { it.name.lowercase() }
        var chosen = draftRememberedVoices[speakerId]?.takeIf(String::isNotEmpty)
        val remember = SpeakerDialog.remember(this, VoiceprintModel.inFiles(filesDir).ready())
        if (draftSpeakerLabels.containsKey(speakerId)) remember.isChecked = speakerId in draftRememberedVoices
        val choices = SpeakerDialog.people(this, savedPeople.map { voice -> voice.name to {
            chosen = voice.id; input.setText(voice.name); input.setSelection(input.text.length)
        } })
        fun saveName(name: String, target: String?) {
            synchronized(draftSpeakerLabels) {
                if (draftNamingOpen && name.isNotEmpty()) {
                    draftSpeakerLabels[speakerId] = name
                    if (remember.isChecked) draftRememberedVoices[speakerId] = target.orEmpty()
                    else draftRememberedVoices.remove(speakerId)
                }
            }
            saveDraftNames(); renderTranscript()
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.rename_speaker_title)
            .setView(SpeakerDialog.frame(this, SpeakerDialog.column(this, input, mergeChoices(speakerId), choices, remember)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                val target = chosen?.takeIf { id -> savedPeople.any { it.id == id && it.name == name } }
                val same = savedPeople.firstOrNull { it.name.equals(name, ignoreCase = true) }
                if (remember.isChecked && target == null && same != null) {
                    AlertDialog.Builder(this).setMessage(getString(R.string.speaker_same_person, same.name))
                        .setPositiveButton(R.string.speaker_same_person_yes) { _, _ -> saveName(name, same.id) }
                        .setNegativeButton(R.string.speaker_same_person_no) { _, _ -> saveName(name, null) }.show()
                } else saveName(name, target)
            }.create()
        speakerDialog?.dismiss()
        speakerDialog = dialog; pendingSpeaker = speakerId; pendingName = draft
        dialog.setOnDismissListener {
            if (speakerDialog === dialog && !isChangingConfigurations) {
                speakerDialog = null; pendingSpeaker = null; pendingName = null
            }
        }
        dialog.show()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        fun validate() { save.isEnabled = input.text.isNotBlank() && (input.text.toString().trim() != current || remember.isChecked || speakerId in draftRememberedVoices) }
        validate()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { pendingName = s?.toString(); validate() }
            override fun afterTextChanged(s: Editable?) {}
        })
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun draftTranscript(): Transcript? = interim?.let { raw ->
        synchronized(draftSpeakerLabels) { SpeakerMerges.apply(raw, draftSpeakerMerges) }
    }

    /** Targets are already named voices used by this transcript, not saved people from other notes. */
    private fun mergeChoices(source: String): View {
        val live = interim != null
        val words = (draftTranscript() ?: session.transcript)?.words.orEmpty()
        val targets = words.map { it.speaker }.distinct().filter { it != source }.mapNotNull { id ->
            val name = if (live) draftSpeakerLabels[id] ?: draftAutomaticNames[id] else document?.speakerLabel(id)
            name?.takeUnless { isDefaultLabel(id, it) }?.let { id to it }
        }
        return SpeakerDialog.merges(this, targets.map { (id, name) -> name to { confirmSpeakerMerge(source, id) } })
    }

    /** Selecting a target never mutates a transcript. Only this dialog's second click commits it. */
    private fun confirmSpeakerMerge(source: String, target: String) {
        if (!canRename()) return
        val live = interim != null
        val sourceName = if (live) draftSpeakerLabels[source] ?: draftAutomaticNames[source] ?: defaultLabel(source) else document?.speakerLabel(source) ?: return
        val targetName = if (live) draftSpeakerLabels[target] ?: draftAutomaticNames[target] ?: return else document?.speakerLabel(target) ?: return
        speakerDialog?.dismiss()
        val dialog = AlertDialog.Builder(this).setTitle(R.string.speaker_merge_title)
            .setMessage(getString(R.string.speaker_merge_confirm, sourceName, targetName) + "\n\n" +
                getString(if (live) R.string.speaker_merge_live else R.string.speaker_merge_saved))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.speaker_merge_action) { _, _ ->
                if (live) {
                    synchronized(draftSpeakerLabels) {
                        if (draftNamingOpen) {
                            draftSpeakerMerges = SpeakerMerges.merge(draftSpeakerMerges, source, target)
                            draftSpeakerLabels[target] = targetName
                            draftSpeakerLabels.remove(source)
                            draftRememberedVoices.remove(source)
                            draftAutomaticNames.remove(source)
                        }
                    }
                    saveDraftNames(); renderTranscript()
                } else applySpeakerMerge(source, target)
            }.create()
        speakerDialog = dialog
        dialog.setOnDismissListener { if (speakerDialog === dialog) speakerDialog = null }
        dialog.show()
    }

    /** A saved merge creates a variant; the earlier transcript remains selectable. No voice profile is taught. */
    private fun applySpeakerMerge(source: String, target: String) {
        val existing = document ?: return
        val path = session.document ?: return
        val previous = existing.selected(session.selectedVariant) ?: return
        val words = previous.transcript ?: return
        if (!canRename() || source == target || words.words.none { it.speaker == source } || words.words.none { it.speaker == target }) return
        val edited = SpeakerMerges.apply(words, mapOf(source to target))
        val process = SpeakerMerges.processing(existing.manifest?.optJSONObject("provenance")?.optJSONObject("speechToText")?.optJSONObject(previous.id),
            mapOf(source to target), previous.id)
        val prints = notePrints.filterKeys { it != source }
        val name = existing.speakerLabel(target) ?: target
        runWork(R.string.saving) {
            val (file, merged) = documents.createFromOpus(Uri.fromFile(File(path)), edited, session.name, process, existing, emptyMap())
            handler.post {
                if (isDestroyed || isFinishing) { file.delete(); return@post }
                val position = if (playerReady) player?.currentPosition ?: session.positionMs else session.positionMs
                session = session.copy(uri = Uri.fromFile(file).toString(), document = file.absolutePath,
                    selectedVariant = merged.defaultId, positionMs = position)
                merged.manifest?.optJSONObject("meeting")?.optString("id")?.let { meeting ->
                    try { app.noteVoices.merge(meeting, merged.defaultId ?: return@let, VoiceprintModel.model.sha256, prints) }
                    catch (error: Exception) { Log.w(TAG, "Could not carry voiceprints to merged transcript", error) }
                }
                adoptDocument(merged)
                preparePlayer(Uri.fromFile(file))
                if (persistSession()) retire(File(path)) else Log.w(TAG, "Kept $path: the note could not be saved")
                loadVoices(); renderScreen(); setStatus(R.string.speaker_merged, name)
            }
        }
    }

    /** Seals a new file with the name and retires the old one only once the note points at the new one. */
    private fun applySpeakerName(speakerId: String, name: String, voiceId: String? = null, remember: Boolean = false, asked: Boolean = false) {
        val existing = document ?: return
        val path = session.document ?: return
        if (name.isEmpty() || !canRename()) return
        val print = notePrints[speakerId]
        if (remember && print != null && voiceId == null && !asked) people.values.firstOrNull {
            it.name.equals(name, ignoreCase = true) && it.model == VoiceprintModel.model.sha256 }?.let { same ->
            AlertDialog.Builder(this).setMessage(getString(R.string.speaker_same_person, same.name))
                .setPositiveButton(R.string.speaker_same_person_yes) { _, _ -> applySpeakerName(speakerId, name, same.id, true, true) }
                .setNegativeButton(R.string.speaker_same_person_no) { _, _ -> applySpeakerName(speakerId, name, null, true, true) }.show()
            return
        }
        // Editing a confirmed person's name is usually a correction, not a new person.
        val confirmed = print?.match?.takeIf { it.state == Match.State.CONFIRMED }?.let { people[it.voiceId] }
        if (remember && confirmed != null && voiceId == null && !asked && !confirmed.name.equals(name, ignoreCase = true)) {
            AlertDialog.Builder(this).setMessage(getString(R.string.speaker_rename_person, confirmed.name, name))
                .setPositiveButton(R.string.speaker_rename_person_yes) { _, _ -> applySpeakerName(speakerId, name, confirmed.id, true, true) }
                .setNegativeButton(R.string.speaker_same_person_no) { _, _ -> applySpeakerName(speakerId, name, null, true, true) }.show()
            return
        }
        if (existing.speakerLabel(speakerId) == name) { rememberVoice(speakerId, name, voiceId, remember); renderTranscript(); setStatus(R.string.speaker_renamed, name); return }
        runWork(R.string.saving) {
            val (file, renamed) = documents.relabel(File(path), existing, mapOf(speakerId to name))
            handler.post {
                if (isDestroyed || isFinishing) { file.delete(); return@post }
                rememberVoice(speakerId, name, voiceId, remember)
                val position = if (playerReady) player?.currentPosition ?: session.positionMs else session.positionMs
                session = session.copy(uri = Uri.fromFile(file).toString(), document = file.absolutePath, positionMs = position)
                adoptDocument(renamed)
                preparePlayer(Uri.fromFile(file))
                if (persistSession()) retire(File(path)) else Log.w(TAG, "Kept $path: the note could not be saved")
                renderScreen()
                setStatus(R.string.speaker_renamed, name.trim())
            }
        }
    }

    /** Enrols a named voice. Only a person's confirmation teaches it, so a wrong automatic name never reinforces itself. */
    private fun rememberVoice(speakerId: String, name: String, voiceId: String?, remember: Boolean) {
        val meeting = meetingId() ?: return; val variant = session.selectedVariant ?: return
        val print = notePrints[speakerId] ?: return
        val previous = print.match?.takeIf { it.state == Match.State.CONFIRMED }
        val target = voiceId ?: previous?.voiceId?.takeIf { people[it]?.name.equals(name, ignoreCase = true) }
        try {
            // A voice confirmed as someone else no longer belongs to that person.
            if (previous != null && previous.voiceId != target) {
                app.voices.unenrol(previous.voiceId, print.embedding); app.noteVoices.setMatch(meeting, variant, speakerId, null)
            }
            if (remember) {
                val score = people[target]?.let { Voiceprints.cosine(print.embedding, it.mean) } ?: 1f
                val voice = if (previous != null && previous.voiceId == target) people.getValue(target).also { app.voices.rename(it.id, name) }
                    else app.voices.enrol(target, name, VoiceprintModel.model.sha256, print.embedding, print.seconds, System.currentTimeMillis())
                app.noteVoices.setMatch(meeting, variant, speakerId, Match(voice.id, score, Match.State.CONFIRMED))
            }
        } catch (error: Exception) { Log.w(TAG, "Could not remember voice", error) }
        loadVoices()
    }

    /** "Not this person": the match is never offered again here, and an automatic or confirmed name goes back to "Speaker N". */
    private fun rejectMatch(speakerId: String) {
        val meeting = meetingId() ?: return; val variant = session.selectedVariant ?: return
        val print = notePrints[speakerId] ?: return; val match = print.match ?: return
        val name = people[match.voiceId]?.name
        try {
            if (match.state == Match.State.CONFIRMED) app.voices.unenrol(match.voiceId, print.embedding)
            app.noteVoices.setMatch(meeting, variant, speakerId, match.copy(state = Match.State.REJECTED))
        } catch (error: Exception) { Log.w(TAG, "Could not reject voice", error) }
        loadVoices()
        if (name != null && document?.speakerLabel(speakerId) == name) applySpeakerName(speakerId, defaultLabel(speakerId)) else renderTranscript()
    }

    private fun defaultLabel(speakerId: String): String {
        // Merging another voice must not renumber the remaining anonymous voices.
        interim?.let {
            return getString(R.string.speaker_label, it.words.map { word -> word.speaker }.distinct().indexOf(speakerId) + 1)
        }
        val speakers = document?.manifest?.optJSONArray("speakers")
        val index = (0 until (speakers?.length() ?: 0)).indexOfFirst { speakers!!.getJSONObject(it).optString("id") == speakerId }
        return getString(R.string.speaker_label, index + 1)
    }

    /** A document another note still opens stays. */
    private fun retire(old: File) {
        val location = Uri.fromFile(old).toString()
        val used = try { library.load().any { it.session.document == old.absolutePath || it.session.uri == location } }
            catch (error: Exception) { Log.e(TAG, "Could not load notes", error); true }
        if (!used && !old.delete()) Log.w(TAG, "Could not delete $old")
    }

    private fun chooseVariant() {
        val portable = document ?: return
        val labels = portable.variants.map { variant ->
            val words = variant.transcript?.words?.size
            "${variant.id} · ${variant.descriptor.optString("language")}" + if (words == null) " · ${getString(R.string.variant_unavailable)}"
                else " · ${resources.getQuantityString(R.plurals.word_count, words, words)}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.choose_transcript)
            .setSingleChoiceItems(labels, portable.variants.indexOfFirst { it.id == session.selectedVariant }) { dialog, index ->
                session = session.copy(selectedVariant = portable.variants[index].id, inferenceMs = 0, processingMs = 0)
                adoptDocument(portable); persistSession(); renderScreen(); defaultStatus(); dialog.dismiss()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showDocumentInfo() {
        val portable = document ?: return
        val content = buildString {
            append(getString(trustResource(portable.state)))
            portable.manifest?.let { manifest ->
                append("\n\n"); append(manifest.getJSONObject("meeting").optString("title"))
                append("\n"); append(manifest.getJSONObject("meeting").optString("createdAtUtc"))
                append("\n\n"); append(getString(R.string.document_contents, portable.variants.size, manifest.getJSONArray("speakers").length()))
                manifest.optJSONObject("provenance")?.optJSONObject("speechToText")?.optJSONObject(session.selectedVariant.orEmpty())?.let { step ->
                    append("\n\n"); append(getString(R.string.document_processing))
                    ProcessingSpeed.realtime(session.durationMs, session.inferenceMs)?.let { speed ->
                        append("\n"); append(getString(R.string.transcription_speed, session.inferenceMs / 1000.0, speed))
                    }
                    for (key in listOf("engine", "model", "backend", "device", "language", "version")) {
                        step.optString(key).takeIf { it.isNotEmpty() }?.let { append("\n"); append(it) }
                    }
                }
            }
            if (portable.selected(session.selectedVariant)?.error != null) { append("\n\n"); append(getString(R.string.variant_unavailable_hint)) }
        }
        AlertDialog.Builder(this).setTitle(R.string.document_info).setMessage(content)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun trustResource(state: String) = when (state) {
        "ok" -> R.string.document_verified
        "stale-audio" -> R.string.document_stale
        "invalid-cassini-metadata" -> R.string.document_invalid
        "unknown-cassini-format" -> R.string.document_unknown
        else -> R.string.document_unverified
    }

    private fun runWork(message: Int, action: () -> Unit) {
        if (busy) return
        busy = true
        ui.pinOperation(true)
        setStatus(message)
        ui.progress.visibility = View.VISIBLE
        ui.progress.isIndeterminate = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshControls()
        worker.execute {
            try { action() } catch (_: ProcessingPaused) {
                onUi { restoreCheckpoint(); persistSession(); renderScreen(); defaultStatus() }
            } catch (error: UserFacingException) {
                Log.e(TAG, "Operation failed: ${error.failure}", error)
                onUi { setStatus(error.failure.stringRes, error = true) }
            } catch (error: IOException) {
                Log.e(TAG, "I/O operation failed", error)
                val failure = when (message) {
                    R.string.preparing_download -> Failure.DOWNLOAD
                    R.string.saving -> Failure.SAVE
                    else -> Failure.OPEN
                }
                onUi { setStatus(failure.stringRes, error = true) }
            } catch (error: Exception) {
                Log.e(TAG, "Operation failed", error)
                onUi { setStatus(R.string.error_unknown, error = true) }
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "Insufficient memory", error)
                onUi { setStatus(R.string.error_memory, error = true) }
            } finally {
                onUi {
                    busy = false
                    transcriptionProgress = null
                    ui.status.minLines = 0
                    draftNamingOpen = checkpointSaved
                    cancellation = null
                    ui.cancelOperation.visibility = View.GONE
                    // Restore the last durable chunk after a stop or failure.
                    if (checkpointSaved) { restoreCheckpoint(); renderScreen(); defaultStatus() }
                    else if (interim != null) { interim = null; renderScreen() }
                    // Speaker labels become tappable once nothing is running.
                    else if (document != null) renderTranscript()
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    ui.progress.visibility = View.GONE
                    ui.pinOperation(false)
                    refreshControls()
                    if (leaveAfterStopping) { leaveAfterStopping = false; returnToLibrary() } else maybeAutoTranscribe()
                }
            }
        }
    }

    /**
     * The speech detector is optional and small. It is fetched on its own thread once Parakeet is installed, also
     * on an installation from before it existed, so neither the model download nor a transcription waits for it.
     */
    private fun fetchSpeechDetector() {
        if (!models.ready() || ModelStore.vadReady(filesDir) || !detectorAttempted.compareAndSet(false, true)) return
        val directory = filesDir
        Thread { installSpeechDetector(directory) }.start()
    }

    private fun renderTranscriptionProgress() {
        val state = transcriptionProgress ?: return
        val elapsedMs = (System.nanoTime() - transcriptionBeganNs) / 1_000_000
        progressRenderedSecond = elapsedMs / 1000
        val snapshot = state.snapshot(elapsedMs)
        ui.progress.isIndeterminate = snapshot.percent == null
        ui.progress.progress = snapshot.percent ?: 0
        val overall = if (snapshot.percent == null) getString(R.string.progress_audio_unknown, clock(snapshot.doneMs))
            else getString(R.string.progress_audio, clock(snapshot.doneMs), clock(snapshot.totalMs), snapshot.percent)
        val stage = when (snapshot.stage) {
            TranscriptionProgress.Stage.READING -> getString(R.string.progress_reading, clock(snapshot.endMs))
            TranscriptionProgress.Stage.PREPARING -> getString(R.string.preparing_recognizer)
            TranscriptionProgress.Stage.WORDS -> getString(R.string.progress_words, clock(snapshot.startMs), clock(snapshot.endMs))
            TranscriptionProgress.Stage.SPEAKERS -> getString(R.string.progress_speakers, clock(snapshot.startMs), clock(snapshot.endMs))
            TranscriptionProgress.Stage.MATCHING -> getString(R.string.matching_voices)
            TranscriptionProgress.Stage.SAVING -> getString(R.string.packaging_document)
        }
        val remaining = if (snapshot.remainingMs == 0L) getString(R.string.progress_finishing)
            else snapshot.remainingMs?.let { getString(R.string.processing_eta_range, clock(TranscriptionProgress.roundedRemaining(snapshot.remainingLowMs ?: it)), clock(TranscriptionProgress.roundedRemaining(snapshot.remainingHighMs ?: it))) }
                ?: getString(R.string.progress_estimating)
        val priorBattery = batteryReading
        if (priorBattery == null || elapsedMs - priorBattery.elapsedMs >= 10000) batteryReading = BatteryProjection.read(this, elapsedMs)
        if (snapshot.remainingMs == 0L && finalizationAtMs < 0) finalizationAtMs = elapsedMs
        val batteryRemaining = snapshot.remainingMs?.let { remaining ->
            if (remaining > 0) (snapshot.remainingHighMs ?: remaining) + finalizationEstimateMs
            else finalizationEstimateMs.takeIf { it > 0 }?.let { (it - (elapsedMs - finalizationAtMs)).coerceAtLeast(5000) }
        }
        val projection = batteryReading?.let { batteryProjection.sample(it, batteryRemaining) }
        val battery = when {
            projection == null -> getString(R.string.battery_unavailable)
            projection.charging -> getString(R.string.battery_charging, projection.percentNow)
            projection.percentAfter != null -> getString(R.string.battery_completion, projection.percentNow, projection.percentAfter)
            else -> getString(R.string.battery_estimating, projection.percentNow)
        }
        val text = listOf(overall, stage, getString(R.string.progress_counts, snapshot.words, clock(elapsedMs)), remaining, battery).joinToString("\n")
        if (ui.status.text.toString() != text) {
            ui.status.text = text
            ui.status.setTextColor(DeckViews.muted)
        }
    }

    /** Only a completed words-and-speakers chunk advances the overall bar. */
    private fun showProgress(progress: Parakeet.Progress) {
        if (!busy) return
        transcriptionProgress?.complete(progress.doneMs, progress.totalMs, progress.words.size,
            (System.nanoTime() - transcriptionBeganNs) / 1_000_000)
        renderTranscriptionProgress()
        if (progress.words != interim?.words && (progress.words.isNotEmpty() || interim != null)) {
            val first = interim == null
            val follow = ui.scroll.getChildAt(0).height - ui.scroll.height - ui.scroll.scrollY <= ui.scroll.height / 4
            interim = Transcript(progress.words)
            highlightedWord = -1
            renderScreen()
            if (first || follow) ui.transcript.post {
                if (busy && !isDestroyed) {
                    val bounds = android.graphics.Rect()
                    ui.transcript.getDrawingRect(bounds)
                    ui.scroll.offsetDescendantRectToMyCoords(ui.transcript, bounds)
                    ui.scroll.smoothScrollTo(0, if (first) bounds.top else (bounds.bottom - ui.scroll.height).coerceAtLeast(0))
                }
            }
        }
    }

    private fun onUi(action: () -> Unit) = runOnUiThread { if (!isDestroyed) action() }
    private fun setStatus(resource: Int, vararg args: Any, error: Boolean = false) {
        ui.status.text = getString(resource, *args)
        ui.status.setTextColor(if (error) DeckViews.warning else DeckViews.muted)
    }
    private fun defaultStatus() {
        when {
            checkpointUnreadable -> setStatus(R.string.checkpoint_error, error = true)
            checkpointSaved && !busy -> setStatus(R.string.processing_paused, clock(checkpointDoneMs), clock(session.durationMs))
            session.transcript != null && session.processingMs > 0 -> setStatus(R.string.processing_speed,
                session.processingMs / 1000.0, ProcessingSpeed.realtime(session.durationMs, session.processingMs) ?: 0.0)
            session.transcript != null && session.inferenceMs > 0 -> setStatus(R.string.transcription_speed,
                session.inferenceMs / 1000.0, ProcessingSpeed.realtime(session.durationMs, session.inferenceMs) ?: 0.0)
            document != null -> ui.status.text = ""
            session.uri != null -> setStatus(R.string.file_ready)
            else -> ui.status.text = ""
        }
    }

    private fun refreshControls() {
        ui.menu.isEnabled = !busy
        ui.library.isEnabled = !busy
        ui.transcribe.isEnabled = !busy && session.uri != null && (document == null || document?.state == "ok")
        // Once there are words, transcribing again is a menu action.
        ui.transcribe.setText(if (checkpointSaved && !busy) R.string.resume_processing else R.string.transcribe)
        ui.transcribe.visibility = if ((checkpointSaved && !busy) || (session.uri != null && session.transcript == null && interim == null)) View.VISIBLE else View.GONE
        ui.play.isEnabled = playerReady
        ui.seek.isEnabled = playerReady
        ui.back.isEnabled = playerReady
        ui.forward.isEnabled = playerReady
        listOf(ui.menu, ui.transcribe, ui.play, ui.back, ui.forward)
            .forEach { it.alpha = if (it.isEnabled) 1f else .4f }
    }

    private fun showMenu() {
        if (busy) return
        val menu = android.widget.PopupMenu(this, ui.menu)
        val actions = mutableMapOf<Int, () -> Unit>()
        fun add(title: Int, enabled: Boolean = true, action: () -> Unit) {
            val item = menu.menu.add(0, actions.size, actions.size, title)
            item.isEnabled = enabled
            actions[item.itemId] = action
        }
        if (session.transcript != null) add(R.string.transcribe_again, document == null || document?.state == "ok") { confirmTranscription() }
        if ((document?.variants?.size ?: 0) > 1) add(R.string.choose_transcript) { chooseVariant() }
        if (session.document != null || session.transcript != null) add(R.string.export) { saveCassiniDocument() }
        if (checkpointSaved) add(R.string.discard_processing) { discardCheckpoint() }
        if ((document?.manifest?.optJSONArray("speakers")?.length() ?: 0) > 0) add(R.string.speakers_menu, canRename()) { showSpeakers() }
        if (document != null) add(R.string.document_info) { showDocumentInfo() }
        add(R.string.settings) { showSettings() }
        menu.setOnMenuItemClickListener { item -> actions[item.itemId]?.invoke(); true }
        menu.show()
    }

    private fun renderScreen() {
        ui.draft.visibility = if (interim != null) View.VISIBLE else View.GONE
        ui.draft.setText(if (checkpointSaved && !busy) R.string.paused_draft else R.string.transcript_draft)
        ui.filename.text = document?.title?.takeIf { it.isNotBlank() } ?: session.name.ifBlank { getString(R.string.no_file) }
        ui.caption.text = if (document == null) getString(R.string.voice_caption, getString(R.string.italian)) else "${getString(R.string.cassini_document)}\n${session.name}"
        // Words of a running transcription belong to no document yet: not to its trust state, variants or speakers.
        ui.trust.visibility = if (document == null || interim != null) View.GONE else View.VISIBLE
        document?.let {
            ui.trust.setText(trustResource(it.state))
            ui.trust.setTextColor(if (it.state == "ok") DeckViews.amber else DeckViews.warning)
        }
        ui.seek.max = session.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        ui.position.text = getString(R.string.position, clock(session.positionMs.toLong()), clock(session.durationMs))
        val transcript = interim ?: session.transcript
        ui.details.visibility = if (transcript == null) View.GONE else View.VISIBLE
        transcript?.let {
            ui.details.text = getString(R.string.transcript_details,
                resources.getQuantityString(R.plurals.word_count, it.words.size, it.words.size),
                clock(session.durationMs), when {
                    interim != null -> models.precision
                    document == null -> session.resultPrecision
                    else -> session.selectedVariant.orEmpty()
                })
        }
        val hasWords = transcript?.words?.isNotEmpty() == true
        ui.searchRow.visibility = if (hasWords) View.VISIBLE else View.GONE
        ui.voice.visibility = if (hasWords && document == null) View.VISIBLE else View.GONE
        ui.transcript.visibility = if (hasWords) View.VISIBLE else View.GONE
        ui.empty.visibility = if (hasWords) View.GONE else View.VISIBLE
        ui.emptyTitle.setText(when {
            document != null && transcript == null -> R.string.variant_unavailable
            transcript != null -> R.string.no_speech
            session.uri != null -> R.string.loaded_title
            else -> R.string.empty_title
        })
        ui.emptyHint.setText(when {
            document != null && transcript == null -> R.string.variant_unavailable_hint
            transcript != null -> R.string.no_speech_hint
            session.uri != null -> R.string.loaded_hint
            else -> R.string.empty_hint
        })
        renderTranscript()
        refreshControls()
    }

    private fun updatePlayback(media: MediaPlayer, followSeek: Boolean = false) {
        val time = media.currentPosition
        if (!ui.seek.isPressed) ui.seek.progress = time
        val clock = getString(R.string.position, clock(time.toLong()), clock(session.durationMs))
        if (ui.position.text.toString() != clock) ui.position.text = clock
        val playText = getString(if (media.isPlaying) R.string.pause else R.string.play)
        if (ui.play.text.toString() != playText) ui.play.text = playText
        val active = (interim ?: session.transcript)?.words?.indexOfFirst { time >= it.startMs && time < it.endMs } ?: -1
        if (active != highlightedWord || followSeek) {
            highlightedWord = active
            highlightWord(followPausedSeek = followSeek)
        }
    }

    private fun renderTranscript() {
        val words = (draftTranscript() ?: session.transcript)?.words ?: emptyList()
        val builder = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        val labels = mutableListOf<Pair<IntRange, String>>()
        val marks = mutableListOf<Pair<IntRange, () -> Unit>>()
        words.forEachIndexed { index, word ->
            if (index > 0) {
                val previous = words[index - 1]
                builder.append(if (previous.speaker != word.speaker || previous.text.lastOrNull() in listOf('.', '?', '!')) "\n\n" else " ")
            }
            if ((document != null || interim != null) && (index == 0 || words[index - 1].speaker != word.speaker)) {
                val start = builder.length
                builder.append(if (interim != null) draftSpeakerLabels[word.speaker] ?: draftAutomaticNames[word.speaker] ?: defaultLabel(word.speaker) else document?.speakerLabel(word.speaker)?.takeUnless { it == word.speaker } ?: getString(R.string.unknown_speaker, word.speaker))
                labels += (start until builder.length) to word.speaker
                (if (interim == null) speakerMark(word.speaker, builder.substring(start)) else null).let { mark ->
                    if (mark != null) { builder.append("  · "); val at = builder.length; builder.append(mark.first); marks += (at until builder.length) to mark.second }
                }
                builder.append("\n")
            }
            val start = builder.length
            builder.append(word.text)
            ranges += start until builder.length
        }
        val text = builder.toString()
        val spannable = SpannableString(text)
        val query = ui.search.text.toString().trim()
        var matchCount = 0
        if (query.isNotEmpty()) {
            var start = text.indexOf(query, ignoreCase = true)
            while (start >= 0) {
                spannable.setSpan(BackgroundColorSpan(android.graphics.Color.rgb(76, 86, 45)), start, start + query.length, 0)
                matchCount++
                start = text.indexOf(query, start + query.length, ignoreCase = true)
            }
        }
        words.forEachIndexed { index, word ->
            val range = ranges[index]
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    if (playerReady) player?.let {
                        seekTo(word.startMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        it.start()
                    }
                }
                override fun updateDrawState(ds: android.text.TextPaint) { ds.isUnderlineText = false }
            }, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (canRename()) labels.forEach { (range, speaker) ->
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = showSpeakerDialog(speaker)
                override fun updateDrawState(ds: android.text.TextPaint) { ds.isUnderlineText = false; ds.color = DeckViews.amber }
            }, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        marks.forEach { (range, action) ->
            spannable.setSpan(if (canRename()) object : ClickableSpan() {
                override fun onClick(widget: View) { if (canRename()) action() }
                override fun updateDrawState(ds: android.text.TextPaint) { ds.isUnderlineText = false; ds.color = DeckViews.muted }
            } else ForegroundColorSpan(DeckViews.muted), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        ui.transcript.setText(spannable, TextView.BufferType.SPANNABLE)
        wordRanges = ranges
        ui.clearSearch.isEnabled = query.isNotEmpty()
        ui.clearSearch.alpha = if (query.isEmpty()) .4f else 1f
        ui.matches.visibility = if (query.isEmpty() || words.isEmpty()) View.GONE else View.VISIBLE
        ui.matches.text = if (matchCount == 0) getString(R.string.no_matches)
            else resources.getQuantityString(R.plurals.search_matches, matchCount, matchCount)
        highlightWord()
    }

    /** Playback moves two spans. Rebuilding every word span per spoken word stalls long documents. */
    private fun highlightWord(followPausedSeek: Boolean = false) {
        val text = ui.transcript.text as? Spannable ?: return
        // A word activated from the keyboard stays selected, drawn in the same amber as the active word.
        Selection.removeSelection(text)
        text.removeSpan(activeBackground); text.removeSpan(activeForeground)
        val range = wordRanges.getOrNull(highlightedWord) ?: return
        text.setSpan(activeBackground, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(activeForeground, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        // A rebuilt transcript may have requested a new text layout. Measure after that layout settles.
        ui.transcript.post {
            if (!isDestroyed && playerReady && (player?.isPlaying == true || followPausedSeek) && !touchingTranscript) {
                followWord(range)
            }
        }
    }

    private fun followWord(range: IntRange) {
        val layout = ui.transcript.layout ?: return
        if (range.last >= ui.transcript.text.length || ui.scroll.height == 0) return
        val firstLine = layout.getLineForOffset(range.first)
        val lastLine = layout.getLineForOffset(range.last)
        val bounds = Rect(0, ui.transcript.totalPaddingTop + layout.getLineTop(firstLine),
            ui.transcript.width, ui.transcript.totalPaddingTop + layout.getLineBottom(lastLine))
        ui.scroll.offsetDescendantRectToMyCoords(ui.transcript, bounds)
        val margin = (ui.scroll.height / 6).coerceAtMost(ui.scroll.height / 2 - bounds.height() / 2).coerceAtLeast(0)
        val visibleTop = ui.scroll.scrollY + margin
        val visibleBottom = ui.scroll.scrollY + ui.scroll.height - margin
        if (bounds.top < visibleTop || bounds.bottom > visibleBottom) {
            // Leave room to read ahead; don't move the page for every word on the same line.
            val maximum = (ui.scroll.getChildAt(0).height - ui.scroll.height).coerceAtLeast(0)
            ui.scroll.smoothScrollTo(0, (bounds.centerY() - ui.scroll.height / 2).coerceIn(0, maximum))
        }
    }

    @Deprecated("Framework activity result API keeps this prototype dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            // A cancelled library import leaves nothing to show on this screen.
            if (requestCode == OPEN_FILE && session.uri == null && intent.getBooleanExtra(REQUEST_IMPORT, false)) returnToLibrary()
            return
        }
        val uri = data?.data ?: return
        when (requestCode) {
            OPEN_FILE -> importFile(uri)
            SAVE_DOCUMENT -> {
                val path = session.document ?: return
                runWork(R.string.saving) {
                    val output = contentResolver.openOutputStream(uri, "wt") ?: throw UserFacingException(Failure.SAVE)
                    output.use { sink -> File(path).inputStream().use { it.copyTo(sink) } }
                    onUi { setStatus(R.string.saved) }
                }
            }
        }
    }

    private fun preparePlayer(uri: Uri) {
        playerReady = false
        followCompletedSeeks = false
        player?.release()
        val media = MediaPlayer()
        player = media
        refreshControls()
        media.setOnSeekCompleteListener {
            if (!isDestroyed && player === media && playerReady && followCompletedSeeks) {
                updatePlayback(media, followSeek = true)
            }
        }
        media.setOnPreparedListener {
            if (isDestroyed || player !== media) return@setOnPreparedListener
            playerReady = true
            session = session.copy(durationMs = if (document?.state == "ok") document!!.manifest!!.getJSONObject("audio").getLong("durationMs") else it.duration.toLong())
            ui.seek.max = session.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            it.seekTo(session.positionMs.coerceIn(0, it.duration))
            refreshControls()
            persistSession()
        }
        media.setOnErrorListener { _, _, _ ->
            playerReady = false
            media.release()
            if (player === media) player = null
            setStatus(R.string.error_playback, error = true)
            refreshControls()
            true
        }
        try { media.setDataSource(this, uri); media.prepareAsync() }
        catch (error: Exception) {
            Log.e(TAG, "Playback setup failed", error)
            media.release(); player = null; playerReady = false
            setStatus(R.string.error_playback, error = true)
            refreshControls()
        }
    }

    private fun skip(delta: Int) {
        if (playerReady) player?.let { seekTo(it.currentPosition + delta) }
    }

    private fun seekTo(position: Int) {
        if (playerReady) player?.let {
            // Follow every completion, including coalesced seeks during a quick slider drag.
            // Initial session restoration uses a direct seek and leaves this disabled.
            followCompletedSeeks = true
            it.seekTo(position.coerceIn(0, it.duration))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let { importFile(it) }
    }

    private fun showSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun persistSession(): Boolean {
        if (playerReady) player?.let { session = session.copy(positionMs = it.currentPosition) }
        val preferences = sessions.load()
        session = session.copy(screen = screen)
        // An empty viewer has nothing to remember, and session.json may hold a session that exists nowhere else.
        if (session.uri == null) return false
        try {
            // The session another screen left there, or one from before the notes library, reaches the catalogue before it is replaced.
            if (preferences.screen != screen) library.adopt(preferences)
            sessions.save(session); session = library.save(session); sessions.save(session)
            return true
        } catch (error: Exception) { Log.e(TAG, "Could not persist session", error); setStatus(R.string.library_error, error = true); return false }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        persistSession()
        outState.putString("query", ui.search.text.toString())
        outState.putInt("scroll", ui.scroll.scrollY)
        outState.putString(NOTE_ID, session.libraryId)
        outState.putString("uri", session.uri)
        outState.putString("screen", screen)
        outState.putString("pendingSpeaker", pendingSpeaker); outState.putString("pendingName", pendingName)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        if (!busy) refreshControls()
        fetchSpeechDetector()
        maybeAutoTranscribe()
        handler.post(ticker)
    }
    override fun onPause() {
        handler.removeCallbacks(ticker)
        // Pausing a player that is prepared but not playing is a MediaPlayer error, which ends playback for this screen.
        if (playerReady) player?.takeIf { it.isPlaying }?.pause()
        persistSession()
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        speakerDialog?.dismiss()
        cancellation?.set(true)
        worker.shutdownNow()
        player?.release()
        super.onDestroy()
    }
    private fun maybeAutoTranscribe() {
        if (autoTranscribe && !busy && session.uri != null && ModelBundle.inFiles(filesDir).consented()) {
            autoTranscribe = false
            transcribe()
        }
    }
    private fun returnToLibrary() {
        persistSession()
        startActivity(Intent(this, LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
    @Deprecated("Framework back callback")
    override fun onBackPressed() {
        if (cancellation != null) { leaveAfterStopping = true; cancelOperation() } else if (!busy) returnToLibrary()
    }
    companion object {
        /** One attempt per process: a network that cannot reach the detector must not be retried at every note. */
        private val detectorAttempted = java.util.concurrent.atomic.AtomicBoolean()

        /** The detector is optional (without it recordings are cut at quiet points), so a failed fetch is only logged. */
        private fun installSpeechDetector(filesDir: File) {
            if (ModelStore.vadReady(filesDir)) return
            try { ModelStore.installVad(filesDir) { } } catch (error: Exception) { Log.w(TAG, "Speech detector unavailable", error) }
        }

        const val OPEN_FILE = 1; const val SAVE_DOCUMENT = 2; private const val TAG = "Cassini"
        const val NOTE_ID = "noteId"; const val SEARCH_QUERY = "searchQuery"
        const val REQUEST_IMPORT = "requestImport"; const val AUTO_TRANSCRIBE = "autoTranscribe"
    }
}
