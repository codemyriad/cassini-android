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
    /** Words of a transcription still running. Shown, never saved. */
    private var interim: Transcript? = null
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

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    private val ticker = object : Runnable {
        override fun run() {
            if (playerReady) player?.let { media ->
                updatePlayback(media)
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
                if (it == ModelBundle.totalBytes) getString(R.string.models_size)
                else getString(R.string.models_size_mib, ModelBundle.mebibytes(it)) }))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.download_and_transcribe) { _, _ -> transcribe() }.show()
    }

    private fun cancelOperation() {
        cancellation?.set(true)
        returnToLibrary()
    }

    private fun showDownload(progress: ModelStore.Progress, label: Int = R.string.downloading) {
        ui.progress.isIndeterminate = false
        ui.progress.progress = progress.percent
        setStatus(if (progress.verifying) R.string.verifying else label, progress.percent)
    }

    /** One action: fetch missing models, recognize words, then tell speakers apart. */
    private fun transcribe() {
        if (busy || (document != null && document?.state != "ok")) return
        val uri = session.uri?.let(Uri::parse) ?: return
        val chosenModel = models
        val speakerModels = DiarizationModels.inFiles(filesDir)
        val voiceprintModel = VoiceprintModel.inFiles(filesDir)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        cancellation = cancelled
        ui.cancelOperation.visibility = View.VISIBLE
        fun checkActive() = requireUser(!cancelled.get() && !Thread.currentThread().isInterrupted, Failure.CANCELLED)
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
            val totalBegan = System.nanoTime()
            val audio = AudioDecoder.decode(this, uri)
            requireUser(audio.durationMs >= 200, Failure.SHORT)
            onUi { setStatus(R.string.transcribing) }
            val detector = ModelStore.vadPath(filesDir).takeIf { ModelStore.vadReady(filesDir) }
            val began = System.nanoTime()
            val transcript = Parakeet.transcribe(audio, chosenModel, detector) { progress -> onUi { showProgress(progress) } }
            val elapsed = (System.nanoTime() - began) / 1_000_000
            checkActive()
            // Processing records are machine-readable provenance, independent of UI language.
            try {
                File(filesDir, "latest.words.json").writeText(transcript.json())
                File(filesDir, "latest.processing.json").writeText(JSONObject()
                    .put("model", "nvidia/parakeet-tdt-0.6b-v3").put("modelRevision", chosenModel.revision)
                    .put("runtime", "sherpa-onnx 1.13.7, stock frontend, CPU ${chosenModel.precision}, greedy, 2 threads")
                    .put("language", "it (user-selected; automatic multilingual recognition)")
                    .put("durationMs", audio.durationMs).put("inferenceMs", elapsed)
                    .put("segmentation", Parakeet.cutting(detector).provenance)
                    .put("wordTimings", "TDT token-derived; words over silence dropped; ends follow continuing audio up to the punctuation-inclusive end")
                    .toString(2))
            } catch (error: IOException) { Log.e(TAG, "Could not cache processing artifacts", error) }
            val recognition = processing(chosenModel.precision, chosenModel.revision)
                .put("x-inferenceMs", elapsed).put("x-segmentation", Parakeet.cutting(detector).provenance)
            // Speakers are best effort: without them the words are still kept, as one unidentified voice.
            val attributed = if (!speakersReady || transcript.words.isEmpty()) null else try {
                onUi { ui.progress.isIndeterminate = true; setStatus(R.string.identifying_speakers) }
                val speakersBegan = System.nanoTime()
                val turns = Diarization.turns(audio, speakerModels.modelPath) { done, total ->
                    onUi {
                        if (!cancelled.get() && total > 0) {
                            val percent = (done * 100L / total).toInt().coerceIn(0, 100)
                            ui.progress.isIndeterminate = false; ui.progress.progress = percent
                            setStatus(R.string.identifying_speakers_progress, percent)
                        }
                    }
                }
                checkActive()
                SpeakerAttribution.derive(transcript, turns, recognition, null, (System.nanoTime() - speakersBegan) / 1_000_000)
            } catch (error: UserFacingException) {
                if (error.failure == Failure.CANCELLED) throw error
                Log.w(TAG, "Speakers not identified: ${error.failure}", error); null
            }
            checkActive()
            val labels = attributed?.transcript?.words?.map { it.speaker }?.distinct()?.mapIndexed { index, id ->
                id to getString(R.string.speaker_label, index + 1)
            }?.toMap().orEmpty()
            onUi { ui.progress.isIndeterminate = true; setStatus(R.string.packaging_document) }
            val (file, portable) = documents.create(uri, { audio }, attributed?.transcript ?: transcript, session.name,
                attributed?.processing ?: recognition, document, labels)
            val processingMs = (System.nanoTime() - totalBegan) / 1_000_000
            // A completed native call may outlive its screen. Never publish a cancelled result,
            // and remove only this job's new immutable document when it cannot be adopted.
            handler.post {
                if (isDestroyed || isFinishing || cancelled.get()) file.delete()
                else {
                    val position = if (playerReady) player?.currentPosition ?: session.positionMs else session.positionMs
                    interim = null
                    session = session.copy(uri = Uri.fromFile(file).toString(), document = file.absolutePath,
                        name = "${session.name.substringBeforeLast('.')}.opus", selectedVariant = portable.defaultId,
                        durationMs = audio.durationMs, inferenceMs = elapsed, resultPrecision = chosenModel.precision, positionMs = position,
                        processingMs = processingMs)
                    adoptDocument(portable)
                    preparePlayer(Uri.fromFile(file))
                    persistSession()
                    renderScreen()
                    if (attributed == null && transcript.words.isNotEmpty()) setStatus(R.string.speakers_unavailable) else defaultStatus()
                }
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
                adoptDocument(portable.takeIf { it.state != "plain-audio" })
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
        highlightedWord = -1
    }

    private fun canRename() = document?.state == "ok" && interim == null && !busy

    /** "Speaker 2" in any interface language: a note sealed before the language changed still has no name. */
    private val defaultLabels by lazy {
        listOf("en", "it").map { tag -> createConfigurationContext(android.content.res.Configuration(resources.configuration)
            .apply { setLocale(Locale.forLanguageTag(tag)) }).getString(R.string.speaker_label, 0).substringBefore('0') }.toSet()
    }
    private fun isDefaultLabel(id: String, label: String) = label == id || label.isBlank() ||
        defaultLabels.any { label.startsWith(it) && label.removePrefix(it).toIntOrNull() != null }

    private fun showSpeakers() {
        val manifest = document?.manifest ?: return
        val speakers = manifest.getJSONArray("speakers").let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("id") } }
        AlertDialog.Builder(this).setTitle(R.string.speakers_menu)
            .setItems(speakers.map { document?.speakerLabel(it)?.ifBlank { it } ?: it }.toTypedArray()) { _, index -> showSpeakerDialog(speakers[index]) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showSpeakerDialog(speakerId: String, draft: String? = null) {
        if (!canRename()) return
        val current = document?.speakerLabel(speakerId) ?: return
        val input = SpeakerDialog.nameInput(this, draft ?: current.takeUnless { isDefaultLabel(speakerId, it) }.orEmpty())
        val dialog = AlertDialog.Builder(this).setTitle(R.string.rename_speaker_title).setView(SpeakerDialog.frame(this, input))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> applySpeakerName(speakerId, input.text.toString().trim()) }
            .create()
        // Dismissal is delivered later; only the dialog still on screen may clear the draft.
        dialog.setOnDismissListener { if (speakerDialog === dialog && !isChangingConfigurations) { speakerDialog = null; pendingSpeaker = null; pendingName = null } }
        speakerDialog?.dismiss()
        speakerDialog = dialog; pendingSpeaker = speakerId; pendingName = draft
        dialog.show()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        fun validate() { save.isEnabled = input.text.isNotBlank() && input.text.toString().trim() != current }
        validate()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { pendingName = s?.toString(); validate() }
            override fun afterTextChanged(s: Editable?) {}
        })
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    /** Seals a new file with the name and retires the old one only once the note points at the new one. */
    private fun applySpeakerName(speakerId: String, name: String) {
        val existing = document ?: return
        val path = session.document ?: return
        if (name.isEmpty() || !canRename()) return
        runWork(R.string.saving) {
            val (file, renamed) = documents.relabel(File(path), existing, mapOf(speakerId to name))
            handler.post {
                if (isDestroyed || isFinishing) { file.delete(); return@post }
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
        setStatus(message)
        ui.progress.visibility = View.VISIBLE
        ui.progress.isIndeterminate = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshControls()
        worker.execute {
            try { action() } catch (error: UserFacingException) {
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
                    cancellation = null
                    ui.cancelOperation.visibility = View.GONE
                    // A run that failed or was cancelled leaves no partial transcript on screen.
                    if (interim != null) { interim = null; renderScreen() }
                    // Speaker labels become tappable once nothing is running.
                    else if (document != null) renderTranscript()
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    ui.progress.visibility = View.GONE
                    refreshControls()
                    maybeAutoTranscribe()
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

    /** Progress, speed and time left come from the pieces decoded so far; their words are shown as they arrive. */
    private fun showProgress(progress: Parakeet.Progress) {
        if (!busy) return
        val percent = if (progress.totalMs > 0) (progress.doneMs * 100 / progress.totalMs).toInt().coerceIn(0, 100) else 0
        // Nothing is measured until the first piece is decoded.
        ui.progress.isIndeterminate = progress.doneMs <= 0
        ui.progress.progress = percent
        val speed = ProcessingSpeed.realtime(progress.doneMs, progress.elapsedMs)
        val remaining = ProcessingSpeed.remainingMs(progress.doneMs, progress.totalMs, progress.elapsedMs)
        if (speed == null || remaining == null) setStatus(R.string.transcribing)
        else setStatus(R.string.transcribing_progress, percent, speed, (remaining + 999) / 1000)
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
        ui.transcribe.visibility = if (session.uri != null && session.transcript == null && interim == null) View.VISIBLE else View.GONE
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
        if ((document?.manifest?.optJSONArray("speakers")?.length() ?: 0) > 0) add(R.string.speakers_menu, canRename()) { showSpeakers() }
        if (document != null) add(R.string.document_info) { showDocumentInfo() }
        add(R.string.settings) { showSettings() }
        menu.setOnMenuItemClickListener { item -> actions[item.itemId]?.invoke(); true }
        menu.show()
    }

    private fun renderScreen() {
        ui.draft.visibility = if (interim != null) View.VISIBLE else View.GONE
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
        val words = (interim ?: session.transcript)?.words ?: emptyList()
        val builder = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        val labels = mutableListOf<Pair<IntRange, String>>()
        words.forEachIndexed { index, word ->
            if (index > 0) {
                val previous = words[index - 1]
                builder.append(if (previous.speaker != word.speaker || previous.text.lastOrNull() in listOf('.', '?', '!')) "\n\n" else " ")
            }
            if (document != null && interim == null && (index == 0 || words[index - 1].speaker != word.speaker)) {
                val start = builder.length
                builder.append(document?.speakerLabel(word.speaker)?.takeUnless { it == word.speaker } ?: getString(R.string.unknown_speaker, word.speaker))
                labels += (start until builder.length) to word.speaker
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
        if (cancellation != null) cancelOperation() else if (!busy) returnToLibrary()
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
