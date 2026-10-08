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
    private var speakerCancellation: java.util.concurrent.atomic.AtomicBoolean? = null
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
        ui.menu.setOnClickListener { showSettings() }
        ui.open.setOnClickListener { chooseFile() }
        ui.transcribe.setOnClickListener { transcribe() }
        ui.speakers.setOnClickListener { confirmSpeakerIdentification() }
        ui.cancelOperation.setOnClickListener { cancelSpeakerIdentification() }
        ui.download.setOnClickListener { downloadModel() }
        ui.export.setOnClickListener { saveCassiniDocument() }
        ui.documentInfo.setOnClickListener { showDocumentInfo() }
        ui.variant.setOnClickListener { chooseVariant() }
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
            val loaded = CassiniDocument.read(File(path).readBytes())
            onUi { adoptDocument(loaded); renderScreen(); defaultStatus() }
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

    private fun downloadModel() = runWork(R.string.preparing_download) {
        models.install { progress -> onUi {
            ui.progress.isIndeterminate = false
            ui.progress.progress = progress.percent
            setStatus(if (progress.verifying) R.string.verifying else R.string.downloading, progress.percent)
        } }
        onUi { setStatus(R.string.download_complete); fetchSpeechDetector() }
    }

    private fun transcribe() {
        val uri = session.uri?.let(Uri::parse) ?: return
        val chosenModel = models
        if (!chosenModel.ready()) { refreshControls(); return }
        runWork(R.string.decoding) {
            val totalBegan = System.nanoTime()
            val audio = AudioDecoder.decode(this, uri)
            requireUser(audio.durationMs >= 200, Failure.SHORT)
            onUi { setStatus(R.string.transcribing) }
            val detector = ModelStore.vadPath(filesDir).takeIf { ModelStore.vadReady(filesDir) }
            val began = System.nanoTime()
            val transcript = Parakeet.transcribe(audio, chosenModel, detector) { progress -> onUi { showProgress(progress) } }
            val elapsed = (System.nanoTime() - began) / 1_000_000
            // Processing records are machine-readable provenance, independent of UI language.
            try {
                File(filesDir, "latest.words.json").writeText(transcript.json())
                File(filesDir, "latest.processing.json").writeText(JSONObject()
                    .put("model", "nvidia/parakeet-tdt-0.6b-v3").put("modelRevision", chosenModel.revision)
                    .put("runtime", "sherpa-onnx 1.13.7, stock frontend, CPU ${chosenModel.precision}, greedy, 2 threads")
                    .put("language", "it (user-selected; automatic multilingual recognition)")
                    .put("durationMs", audio.durationMs).put("inferenceMs", elapsed)
                    .put("speakerAttribution", "single recording; no diarization")
                    .put("segmentation", Parakeet.cutting(detector).provenance)
                    .put("wordTimings", "TDT token-derived; words over silence dropped; ends follow continuing audio up to the punctuation-inclusive end")
                    .toString(2))
            } catch (error: IOException) { Log.e(TAG, "Could not cache processing artifacts", error) }
            onUi { ui.progress.isIndeterminate = true; setStatus(R.string.packaging_document) }
            val (file, portable) = documents.create(uri, audio, transcript, session.name, processing(chosenModel.precision, chosenModel.revision)
                .put("x-inferenceMs", elapsed).put("x-segmentation", Parakeet.cutting(detector).provenance), document)
            val processingMs = (System.nanoTime() - totalBegan) / 1_000_000
            onUi {
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
                defaultStatus()
            }
        }
    }

    private fun confirmSpeakerIdentification() {
        if (busy || session.transcript?.words?.isNotEmpty() != true) return
        if (DiarizationModels.inFiles(filesDir).ready()) identifySpeakers()
        else AlertDialog.Builder(this).setTitle(R.string.speaker_models_download)
            .setMessage(R.string.speaker_models_summary)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.download_speaker_models) { _, _ -> identifySpeakers() }.show()
    }

    private fun cancelSpeakerIdentification() {
        speakerCancellation?.set(true)
        returnToLibrary()
    }

    private fun identifySpeakers() {
        if (busy || (document != null && document?.state != "ok")) return
        val source = session.transcript?.takeIf { it.words.isNotEmpty() } ?: return
        val uri = session.uri?.let(Uri::parse) ?: return
        val original = session
        val originalDocument = document
        val sourceId = original.selectedVariant
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        speakerCancellation = cancelled
        ui.cancelOperation.visibility = View.VISIBLE
        val speakerModels = DiarizationModels.inFiles(filesDir)
        fun checkActive() = requireUser(!cancelled.get() && !Thread.currentThread().isInterrupted, Failure.CANCELLED)
        runWork(if (speakerModels.ready()) R.string.identifying_speakers else R.string.preparing_download) {
            if (!speakerModels.ready()) speakerModels.install { progress -> onUi {
                if (!cancelled.get()) {
                    ui.progress.isIndeterminate = false
                    ui.progress.progress = progress.percent
                    setStatus(if (progress.verifying) R.string.verifying else R.string.downloading, progress.percent)
                }
            } }
            checkActive()
            onUi { ui.progress.isIndeterminate = true; setStatus(R.string.identifying_speakers) }
            val began = System.nanoTime()
            val audio = AudioDecoder.decode(this, uri)
            checkActive()
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
            val elapsed = (System.nanoTime() - began) / 1_000_000
            val previousProcessing = originalDocument?.manifest?.optJSONObject("provenance")?.optJSONObject("speechToText")?.optJSONObject(sourceId ?: "")
            val result = SpeakerAttribution.derive(source, turns, previousProcessing, sourceId, elapsed, originalDocument?.manifest?.optJSONObject("provenance")?.optJSONObject("attribution"))
            val labels = result.transcript.words.map { it.speaker }.distinct().mapIndexed { index, id ->
                id to getString(R.string.speaker_label, index + 1)
            }.toMap()
            onUi { ui.progress.isIndeterminate = true; setStatus(R.string.packaging_document) }
            checkActive()
            val (file, portable) = documents.create(uri, audio, result.transcript, original.name,
                result.processing, originalDocument, labels)
            // A completed native call may outlive its screen. Never publish a cancelled result,
            // and remove only this job's new immutable document when it cannot be adopted.
            handler.post {
                if (isDestroyed || isFinishing || cancelled.get()) file.delete()
                else {
                    val position = if (playerReady) player?.currentPosition ?: session.positionMs else session.positionMs
                    session = original.copy(uri = Uri.fromFile(file).toString(), document = file.absolutePath,
                        name = "${original.name.substringBeforeLast('.')}.opus", selectedVariant = portable.defaultId,
                        durationMs = audio.durationMs, positionMs = position,
                        processingMs = maxOf(original.processingMs, original.inferenceMs) + elapsed)
                    adoptDocument(portable); preparePlayer(Uri.fromFile(file)); persistSession(); renderScreen()
                    setStatus(R.string.speakers_complete, labels.size, elapsed / 1000.0)
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
                val audio = AudioDecoder.decode(this, uri)
                val (file, portable) = documents.create(uri, audio, transcript, session.name, processing(session.resultPrecision).put("x-inferenceMs", session.inferenceMs), null)
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
                    speakerCancellation = null
                    ui.cancelOperation.visibility = View.GONE
                    // A run that failed or was cancelled leaves no partial transcript on screen.
                    if (interim != null) { interim = null; renderScreen() }
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
        val ready = models.ready()
        ui.model.text = getString(if (ready) R.string.model_ready else R.string.model_missing,
            models.precision)
        ui.download.text = getString(R.string.download_model, getString(R.string.model_size_int8))
        ui.download.visibility = if (ready) View.GONE else View.VISIBLE
        ui.menu.isEnabled = !busy
        ui.library.isEnabled = !busy
        ui.open.isEnabled = !busy
        ui.transcribe.isEnabled = !busy && session.uri != null && ready && (document == null || document?.state == "ok")
        ui.speakers.visibility = if (session.transcript?.words?.isNotEmpty() == true && interim == null) View.VISIBLE else View.GONE
        ui.speakers.isEnabled = !busy && session.uri != null && (document == null || document?.state == "ok")
        ui.download.isEnabled = !busy
        ui.export.isEnabled = !busy && (session.document != null || session.transcript != null)
        ui.documentInfo.isEnabled = !busy
        ui.variant.isEnabled = !busy
        ui.play.isEnabled = playerReady
        ui.seek.isEnabled = playerReady
        ui.back.isEnabled = playerReady
        ui.forward.isEnabled = playerReady
        listOf(ui.menu, ui.open, ui.transcribe, ui.speakers, ui.download, ui.export, ui.play, ui.back, ui.forward)
            .forEach { it.alpha = if (it.isEnabled) 1f else .4f }
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
        ui.documentInfo.visibility = if (document == null) View.GONE else View.VISIBLE
        ui.variant.visibility = if ((document?.variants?.size ?: 0) > 1 && interim == null) View.VISIBLE else View.GONE
        ui.variant.text = getString(R.string.selected_transcript, session.selectedVariant.orEmpty())
        ui.open.setText(if (session.uri == null) R.string.open_audio else R.string.change_audio)
        ui.transcribe.setText(if (session.transcript == null) R.string.transcribe else R.string.transcribe_again)
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
        words.forEachIndexed { index, word ->
            if (index > 0) {
                val previous = words[index - 1]
                builder.append(if (previous.speaker != word.speaker || previous.text.lastOrNull() in listOf('.', '?', '!')) "\n\n" else " ")
            }
            if (document != null && interim == null && (index == 0 || words[index - 1].speaker != word.speaker)) {
                builder.append(document?.speakerLabel(word.speaker)?.takeUnless { it == word.speaker } ?: getString(R.string.unknown_speaker, word.speaker)).append("\n")
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

    private fun persistSession() {
        if (playerReady) player?.let { session = session.copy(positionMs = it.currentPosition) }
        val preferences = sessions.load()
        session = session.copy(screen = screen)
        // An empty viewer has nothing to remember, and session.json may hold a session that exists nowhere else.
        if (session.uri == null) return
        try {
            // The session another screen left there, or one from before the notes library, reaches the catalogue before it is replaced.
            if (preferences.screen != screen) library.adopt(preferences)
            sessions.save(session); session = library.save(session); sessions.save(session)
        } catch (error: Exception) { Log.e(TAG, "Could not persist session", error); setStatus(R.string.library_error, error = true) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        persistSession()
        outState.putString("query", ui.search.text.toString())
        outState.putInt("scroll", ui.scroll.scrollY)
        outState.putString(NOTE_ID, session.libraryId)
        outState.putString("uri", session.uri)
        outState.putString("screen", screen)
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
        speakerCancellation?.set(true)
        worker.shutdownNow()
        player?.release()
        super.onDestroy()
    }
    private fun clock(ms: Long) = String.format(Locale.ROOT, "%02d:%02d", ms / 60000, ms / 1000 % 60)
    private fun maybeAutoTranscribe() {
        if (autoTranscribe && !busy && models.ready() && session.uri != null) {
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
        if (speakerCancellation != null) cancelSpeakerIdentification() else if (!busy) returnToLibrary()
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
