package org.cassini.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
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

class MainActivity : Activity(), ProcessingJobs.Listener {
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
    private var busy = false
    /** Settled words of this note's transcription while the service runs it. Shown, never saved from here. */
    private var live: SettledWords? = null
    /** Words after [live]'s that a later decode may still change, drawn muted at the end. */
    private var livePending = emptyList<Word>()
    /** Where the muted provisional tail begins in the shown text. */
    private var pendingStart = -1
    /** The job of this note last seen running here, so its end is reported once. */
    private var watched: ProcessingJob? = null
    /** The finished job whose note this screen has already reloaded. */
    private var synced: ProcessingJob? = null
    private var highlightedWord = -1
    /** The shown transcript's text and word positions, extended in place while a job adds words. */
    private var shown = TranscriptText()
    private val searchDebounce = Runnable { renderTranscript() }
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
        val noteId = intent.getStringExtra(NOTE_ID)
        screen = savedInstanceState?.getString("screen") ?: UUID.randomUUID().toString()
        val importing = intent.getBooleanExtra(REQUEST_IMPORT, false)
        val restoredId = savedInstanceState?.getString(NOTE_ID)
        val restoredUri = savedInstanceState?.getString("uri")
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
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // A full rebuild per keystroke stalls a long transcript: rebuild it once typing pauses.
                ui.transcript.removeCallbacks(searchDebounce)
                if (shown.size < DEBOUNCED_WORDS) renderTranscript() else ui.transcript.postDelayed(searchDebounce, SEARCH_DEBOUNCE_MS)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        ui.clearSearch.setOnClickListener { ui.search.text.clear() }
        ui.transcript.movementMethod = LinkMovementMethod.getInstance()
        ui.search.setText(savedInstanceState?.getString("query") ?: intent.getStringExtra(SEARCH_QUERY).orEmpty())
        renderScreen()
        // A fresh import shows an empty note at once; anything else reads session.json and the catalogue, which holds
        // every transcript, behind this process's queued writes and off the main thread.
        if (savedInstanceState == null && (importing || (intent.action == Intent.ACTION_VIEW && intent.data != null))) {
            opened(Session(), viewing = !importing, importing = importing, fresh = true)
        } else {
            setStatus(R.string.library_loading)
            val fresh = savedInstanceState == null
            SerialWriter.library.submit {
                val preferences = sessions.load()
                val notes by lazy { try { library.load() } catch (error: Exception) { Log.e(TAG, "Could not load notes", error); emptyList() } }
                val restored = if (fresh) null else LibraryNote.shown(screen, restoredId, restoredUri, preferences, notes)
                // A recreated activity keeps its launch intent. It is imported again only while this screen has no note yet.
                val viewing = restored == null && intent.action == Intent.ACTION_VIEW && intent.data != null
                val selected = when {
                    restored != null -> restored
                    viewing -> Session()
                    noteId == null -> preferences
                    else -> notes.firstOrNull { it.id == noteId }?.session ?: Session()
                }
                onUi { opened(selected, viewing, importing = false, fresh = fresh) }
            }
        }
        savedInstanceState?.let { state -> ui.scroll.post { ui.scroll.scrollTo(0, state.getInt("scroll")) } }
    }

    /** Shows the note this screen opened with, once it is resolved. */
    private fun opened(selected: Session, viewing: Boolean, importing: Boolean, fresh: Boolean) {
        session = selected
        renderScreen()
        defaultStatus()
        session.uri?.let { preparePlayer(Uri.parse(it)) }
        session.document?.takeIf { !viewing }?.let { path -> runWork(R.string.opening_document) {
            val loaded = CassiniDocument.read(File(path))
            onUi { adoptDocument(loaded); renderScreen(); defaultStatus() }
        } }
        if (viewing) intent.data?.let { importFile(it) }
        if (importing && fresh) chooseFile()
        onJobChanged(ProcessingJobs.current)
        maybeAutoTranscribe()
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

    /** Hands the note to [ProcessingService]: the work outlives this screen, which only shows its progress. */
    private fun transcribe() {
        if (session.uri == null) return
        if (!models.ready()) { refreshControls(); return }
        startProcessing(speakers = false)
    }

    private fun startProcessing(speakers: Boolean) {
        // A new note gets its id from its first save, which runs on the writer: continue once it has one.
        persistSession { startSaved(speakers) }
    }

    private var notificationsAsked = false

    private fun startSaved(speakers: Boolean) {
        val id = session.libraryId ?: run { setStatus(R.string.library_error, error = true); return }
        val running = ProcessingJobs.current?.takeIf { ProcessingJobs.running && it.phase.active }
        if (running != null && running.noteId != id) { setStatus(R.string.processing_other_note, error = true); return }
        if (running == null) ProcessingService.start(this, id, session.name, speakers)
        // Progress, speed and the outcome are shown in a notification while the app is away; ask once per screen.
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAsked &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationsAsked = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS)
        }
        watched = ProcessingJob(id, session.name, speakers)
        setStatus(if (speakers) R.string.identifying_speakers else R.string.decoding)
        ui.progress.visibility = View.VISIBLE
        ui.progress.isIndeterminate = true
        refreshControls()
    }

    /** True while the service works on the note on screen. */
    private fun processingHere(): Boolean = ProcessingJobs.running &&
        ProcessingJobs.current?.let { it.phase.active && it.noteId == session.libraryId } == true

    override fun onJobChanged(job: ProcessingJob?) {
        if (isDestroyed || job == null || job.noteId != session.libraryId) { refreshControls(); return }
        if (job.phase.active && ProcessingJobs.running) {
            watched = job
            showProgress(job)
            return
        }
        if (job.phase.active || job === synced) return
        // Finished, failed or stopped: the service saved the note; show what it saved.
        val seen = watched != null
        watched = null
        synced = job
        ui.progress.visibility = View.GONE
        reloadNote {
            when {
                seen && job.phase == ProcessingJob.Phase.DONE && job.speakers -> setStatus(R.string.speakers_complete,
                    session.transcript?.words?.map { it.speaker }?.distinct()?.size ?: 0, job.stageMs / 1000.0)
                !seen || job.phase == ProcessingJob.Phase.DONE -> defaultStatus()
                job.phase == ProcessingJob.Phase.CANCELLED -> setStatus(R.string.error_cancelled)
                else -> setStatus((job.failure ?: Failure.UNKNOWN).stringRes, error = true)
            }
        }
    }

    /** Re-reads this note from the library, which the service wrote, and its document. */
    private fun reloadNote(then: () -> Unit) {
        val id = session.libraryId ?: return then()
        // The catalogue holds every transcript: read it behind this screen's own queued writes, off the main thread.
        SerialWriter.library.submit {
            val fresh = try { library.load().firstOrNull { it.id == id }?.session } catch (error: Exception) {
                Log.e(TAG, "Could not load note", error); null
            }
            onUi { if (fresh == null || session.libraryId != id) then() else adoptNote(fresh, then) }
        }
    }

    private fun adoptNote(fresh: Session, then: () -> Unit) {
        val position = if (playerReady) player?.currentPosition ?: session.positionMs else session.positionMs
        val moved = fresh.uri != session.uri
        session = fresh.copy(positionMs = position, screen = screen)
        live = null
        livePending = emptyList()
        val saved = session
        SerialWriter.library.submit { sessions.save(saved) }
        if (moved) session.uri?.let { preparePlayer(Uri.parse(it)) }
        val path = fresh.document
        if (path == null) { document = null; renderScreen(); then(); return }
        worker.execute {
            val loaded = try { CassiniDocument.read(File(path)) } catch (error: Exception) { Log.e(TAG, "Could not open document", error); null }
            onUi { adoptDocument(loaded); renderScreen(); then() }
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

    /** Cancels the job of this note; its settled words stay in the note. */
    private fun cancelSpeakerIdentification() {
        if (processingHere()) ProcessingService.cancel(this)
    }

    private fun identifySpeakers() {
        if (busy || processingHere() || (document != null && document?.state != "ok")) return
        if (session.transcript?.words?.isNotEmpty() != true || session.uri == null) return
        val speakerModels = DiarizationModels.inFiles(filesDir)
        if (speakerModels.ready()) { startProcessing(speakers = true); return }
        runWork(R.string.preparing_download) {
            speakerModels.install { progress -> onUi {
                ui.progress.isIndeterminate = false
                ui.progress.progress = progress.percent
                setStatus(if (progress.verifying) R.string.verifying else R.string.downloading, progress.percent)
            } }
            onUi { handler.post { if (!isDestroyed && !busy) startProcessing(speakers = true) } }
        }
    }

    private fun processing(precision: String, revision: String? = null): JSONObject = ProcessingPipeline.processing(precision, revision)

    private fun saveCassiniDocument() {
        // Settled words of a stopped job are not a complete transcript: never package them as one.
        if (session.partial) { setStatus(R.string.partial_transcript, clock(session.transcript?.words?.lastOrNull()?.endMs ?: 0)); return }
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
            val location = Uri.fromFile(file).toString()
            // A file opened before returns to its note, with its position and chosen transcript.
            val known = try { library.load().firstOrNull { it.session.uri == location }?.session }
                catch (error: Exception) { Log.e(TAG, "Could not load note", error); null }
            onUi {
                // Imports are named by content. Opening a file that is already on screen changes nothing.
                if (session.uri != location) {
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
            // A stopped job's partial words are newer than the document they will replace.
            session = session.copy(transcript = if (session.partial) session.transcript else selected?.transcript,
                selectedVariant = selected?.id, inferenceMs = inference)
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
            } catch (error: InterruptedException) {
                Log.i(TAG, "Operation interrupted", error)
            } catch (error: Exception) {
                Log.e(TAG, "Operation failed", error)
                onUi { setStatus(R.string.error_unknown, error = true) }
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "Insufficient memory", error)
                onUi { setStatus(R.string.error_memory, error = true) }
            } finally {
                onUi {
                    busy = false
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

    /** Progress, speed and time left of the service's job; its settled and pending words are shown as they arrive. */
    private fun showProgress(job: ProcessingJob) {
        ui.progress.visibility = View.VISIBLE
        ui.cancelOperation.visibility = View.VISIBLE
        // Nothing is measured until the first piece is decoded.
        ui.progress.isIndeterminate = job.doneMs <= 0 || job.phase == ProcessingJob.Phase.PACKAGE
        ui.progress.progress = job.percent
        val speed = job.speed
        val remaining = job.remainingMs
        when (job.phase) {
            ProcessingJob.Phase.QUEUED, ProcessingJob.Phase.DECODE -> setStatus(R.string.decoding)
            ProcessingJob.Phase.PACKAGE -> setStatus(R.string.packaging_document)
            ProcessingJob.Phase.DIARIZE -> if (speed == null || job.doneMs <= 0) setStatus(R.string.identifying_speakers)
                else setStatus(R.string.identifying_speakers_speed, job.percent, speed)
            else -> if (speed == null || remaining == null) setStatus(R.string.transcribing)
                else setStatus(R.string.transcribing_progress, job.percent, speed)
        }
        val measured = speed != null && remaining != null && job.doneMs > 0 && job.phase != ProcessingJob.Phase.PACKAGE
        ui.eta.text = if (measured) clock(remaining!! + 999) else ""
        if (job.phase != ProcessingJob.Phase.ASR) { refreshControls(); return }
        val words = ProcessingJobs.settled
        val follow = ui.scroll.getChildAt(0).height - ui.scroll.height - ui.scroll.scrollY <= ui.scroll.height / 4
        val first = live !== words
        if (first) {
            // A new run (or this screen just attached): one full build from the words settled so far.
            live = words
            livePending = job.pending
            highlightedWord = -1
            renderScreen()
        } else {
            appendLive(words.since(shown.size), job.pending)
            refreshControls()
        }
        if (first || follow) ui.transcript.post {
            if (live != null && !isDestroyed) {
                val bounds = android.graphics.Rect()
                ui.transcript.getDrawingRect(bounds)
                ui.scroll.offsetDescendantRectToMyCoords(ui.transcript, bounds)
                ui.scroll.smoothScrollTo(0, if (first) bounds.top else (bounds.bottom - ui.scroll.height).coerceAtLeast(0))
            }
        }
    }

    /** Adds newly settled words and replaces the provisional tail, touching only the end of the text. */
    private fun appendLive(fresh: List<Word>, pending: List<Word>) {
        val text = ui.transcript.text as? android.text.Editable
        if (text == null || pendingStart < 0 || pendingStart > text.length) { livePending = pending; renderScreen(); return }
        if (fresh.isEmpty() && pending == livePending) return
        val hadWords = shown.size + livePending.size > 0
        livePending = pending
        text.delete(pendingStart, text.length)
        val before = shown.size
        val from = shown.append(fresh)
        text.append(shown.text, from, shown.text.length)
        for (index in before until shown.size) wordSpan(text, index)
        highlightMatches(text, from)
        pendingStart = text.length
        appendPending(text)
        if (!hadWords && shown.size + livePending.size > 0) renderScreen()
        else ui.details.text = details(shown.size + livePending.size)
    }

    private fun appendPending(text: android.text.Editable) {
        if (livePending.isEmpty()) return
        val start = text.length
        text.append(shown.tail(livePending))
        text.setSpan(ForegroundColorSpan(DeckViews.muted), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun onUi(action: () -> Unit) = runOnUiThread { if (!isDestroyed) action() }
    private fun setStatus(resource: Int, vararg args: Any, error: Boolean = false) {
        ui.eta.text = ""
        ui.status.text = getString(resource, *args)
        ui.status.setTextColor(if (error) DeckViews.warning else DeckViews.muted)
    }
    private fun defaultStatus() {
        when {
            session.partial -> setStatus(R.string.partial_transcript, clock(session.transcript?.words?.lastOrNull()?.endMs ?: 0))
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
        // Processing runs in the service: only this note's own processing controls wait for it.
        val processing = processingHere()
        ui.menu.isEnabled = !busy
        ui.library.isEnabled = !busy
        ui.open.isEnabled = !busy && !processing
        ui.transcribe.isEnabled = !busy && !processing && session.uri != null && ready && (document == null || document?.state == "ok")
        ui.speakers.visibility = if (session.transcript?.words?.isNotEmpty() == true && live == null && !session.partial) View.VISIBLE else View.GONE
        ui.speakers.isEnabled = !busy && !processing && session.uri != null && (document == null || document?.state == "ok")
        ui.cancelOperation.visibility = if (processing) View.VISIBLE else View.GONE
        if (!processing && !busy) ui.progress.visibility = View.GONE
        ui.download.isEnabled = !busy
        ui.export.isEnabled = !busy && !processing && !session.partial && (session.document != null || session.transcript != null)
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
        ui.draft.visibility = if (live != null || session.partial) View.VISIBLE else View.GONE
        ui.filename.text = document?.title?.takeIf { it.isNotBlank() } ?: session.name.ifBlank { getString(R.string.no_file) }
        ui.caption.text = if (document == null) getString(R.string.voice_caption, getString(R.string.italian)) else "${getString(R.string.cassini_document)}\n${session.name}"
        // Words of a running transcription belong to no document yet: not to its trust state, variants or speakers.
        ui.trust.visibility = if (document == null || live != null || session.partial) View.GONE else View.VISIBLE
        document?.let {
            ui.trust.setText(trustResource(it.state))
            ui.trust.setTextColor(if (it.state == "ok") DeckViews.amber else DeckViews.warning)
        }
        ui.documentInfo.visibility = if (document == null) View.GONE else View.VISIBLE
        ui.variant.visibility = if ((document?.variants?.size ?: 0) > 1 && live == null) View.VISIBLE else View.GONE
        ui.variant.text = getString(R.string.selected_transcript, session.selectedVariant.orEmpty())
        ui.open.setText(if (session.uri == null) R.string.open_audio else R.string.change_audio)
        ui.transcribe.setText(if (session.transcript == null) R.string.transcribe else R.string.transcribe_again)
        ui.seek.max = session.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        ui.position.text = getString(R.string.position, clock(session.positionMs.toLong()), clock(session.durationMs))
        val count = live?.let { it.size + livePending.size } ?: session.transcript?.words?.size
        val transcript = if (live != null) null else session.transcript
        ui.details.visibility = if (count == null) View.GONE else View.VISIBLE
        count?.let { ui.details.text = details(it) }
        val hasWords = (count ?: 0) > 0
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

    private fun details(count: Int) = getString(R.string.transcript_details,
        resources.getQuantityString(R.plurals.word_count, count, count), clock(session.durationMs), when {
            live != null -> models.precision
            document == null -> session.resultPrecision
            else -> session.selectedVariant.orEmpty()
        })

    private fun updatePlayback(media: MediaPlayer, followSeek: Boolean = false) {
        val time = media.currentPosition
        if (!ui.seek.isPressed) ui.seek.progress = time
        val clock = getString(R.string.position, clock(time.toLong()), clock(session.durationMs))
        if (ui.position.text.toString() != clock) ui.position.text = clock
        val playText = getString(if (media.isPlaying) R.string.pause else R.string.play)
        if (ui.play.text.toString() != playText) ui.play.text = playText
        val active = shown.wordAt(time.toLong())
        if (active != highlightedWord || followSeek) {
            highlightedWord = active
            highlightWord(followPausedSeek = followSeek)
        }
    }

    /** A full build of the shown transcript: on a new note, a search, or a job attaching. Running jobs append instead. */
    private fun renderTranscript() {
        ui.transcript.removeCallbacks(searchDebounce)
        val running = live
        val words = running?.since(0) ?: session.transcript?.words ?: emptyList()
        val labelled = document != null && running == null && !session.partial
        shown = TranscriptText(if (!labelled) null else { word ->
            document?.speakerLabel(word.speaker)?.takeUnless { it == word.speaker } ?: getString(R.string.unknown_speaker, word.speaker)
        })
        shown.append(words)
        val text = android.text.SpannableStringBuilder(shown.text)
        val matchCount = highlightMatches(text, 0)
        for (index in 0 until shown.size) wordSpan(text, index)
        pendingStart = text.length
        if (running != null) appendPending(text)
        ui.transcript.setText(text, TextView.BufferType.EDITABLE)
        val query = searchQuery()
        ui.clearSearch.isEnabled = query.isNotEmpty()
        ui.clearSearch.alpha = if (query.isEmpty()) .4f else 1f
        ui.matches.visibility = if (query.isEmpty() || words.isEmpty()) View.GONE else View.VISIBLE
        ui.matches.text = if (matchCount == 0) getString(R.string.no_matches)
            else resources.getQuantityString(R.plurals.search_matches, matchCount, matchCount)
        highlightWord()
    }

    private fun searchQuery() = ui.search.text.toString().trim()

    /** Marks the search matches in [text] from [from] on; returns how many. */
    private fun highlightMatches(text: Spannable, from: Int): Int {
        val query = searchQuery()
        if (query.isEmpty()) return 0
        var count = 0
        val plain = text.toString()
        var start = plain.indexOf(query, from, ignoreCase = true)
        while (start >= 0) {
            text.setSpan(BackgroundColorSpan(android.graphics.Color.rgb(76, 86, 45)), start, start + query.length, 0)
            count++
            start = plain.indexOf(query, start + query.length, ignoreCase = true)
        }
        return count
    }

    /** Tapping word [index] plays from its start. */
    private fun wordSpan(text: Spannable, index: Int) {
        val startMs = shown.startMs(index)
        text.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                if (playerReady) player?.let {
                    seekTo(startMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    it.start()
                }
            }
            override fun updateDrawState(ds: android.text.TextPaint) { ds.isUnderlineText = false }
        }, shown.start(index), shown.end(index), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Playback moves two spans. Rebuilding every word span per spoken word stalls long documents. */
    private fun highlightWord(followPausedSeek: Boolean = false) {
        val text = ui.transcript.text as? Spannable ?: return
        // A word activated from the keyboard stays selected, drawn in the same amber as the active word.
        Selection.removeSelection(text)
        text.removeSpan(activeBackground); text.removeSpan(activeForeground)
        if (highlightedWord !in 0 until shown.size) return
        val range = shown.start(highlightedWord) until shown.end(highlightedWord)
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

    /**
     * Saves the note. A note already in the catalogue is written on [SerialWriter.library], in order, so pausing,
     * rotating or leaving never rewrites the whole catalogue on the main thread. A note's first save gives it its
     * id, which reaches this screen, and then [saved] runs, on the main thread once the write is done.
     */
    private fun persistSession(saved: (() -> Unit)? = null) {
        if (playerReady) player?.let { session = session.copy(positionMs = it.currentPosition) }
        session = session.copy(screen = screen)
        // An empty viewer has nothing to remember, and session.json may hold a session that exists nowhere else.
        if (session.uri == null) { saved?.invoke(); return }
        val snapshot = session
        val id = snapshot.libraryId
        // The service owns this note's transcript and document until this screen has reloaded them.
        val serviceOwns = id != null && ProcessingJobs.current?.noteId == id && ProcessingJobs.current !== synced
        val failed = { error: Exception ->
            Log.e(TAG, "Could not persist session", error)
            onUi { setStatus(R.string.library_error, error = true) }
        }
        SerialWriter.library.submit(failed) {
            val written = writeSession(snapshot, serviceOwns)
            onUi {
                // Only the id comes back: the screen may have moved on (position, edits) while the write ran.
                if (session.libraryId == null && session.uri == snapshot.uri) session = session.copy(libraryId = written.libraryId)
                saved?.invoke()
            }
        }
    }

    private fun writeSession(snapshot: Session, serviceOwns: Boolean): Session {
        val preferences = sessions.load()
        // The session another screen left there, or one from before the notes library, reaches the catalogue before it is replaced.
        if (preferences.screen != snapshot.screen) library.adopt(preferences)
        val id = snapshot.libraryId
        if (serviceOwns && id != null) {
            library.update(id) { it.copy(positionMs = snapshot.positionMs) }
            sessions.save(snapshot)
            return snapshot
        }
        sessions.save(snapshot)
        val saved = library.save(snapshot)
        sessions.save(saved)
        return saved
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
    override fun onStart() {
        super.onStart()
        ProcessingJobs.addListener(this)
        onJobChanged(ProcessingJobs.load(filesDir))
    }
    override fun onStop() {
        ProcessingJobs.removeListener(this)
        super.onStop()
    }
    override fun onResume() {
        super.onResume()
        if (!busy) refreshControls()
        fetchSpeechDetector()
        resumeInterrupted()
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
        // Downloads stop with the screen; transcription runs in ProcessingService and does not.
        worker.shutdownNow()
        player?.release()
        super.onDestroy()
    }
    private fun maybeAutoTranscribe() {
        if (autoTranscribe && !busy && models.ready() && session.uri != null) {
            autoTranscribe = false
            if (!processingHere()) transcribe()
        }
    }

    /** A job the process lost (killed, or stopped by the system) continues from its checkpoints now that Cassini is in front. */
    private fun resumeInterrupted() {
        val job = ProcessingJobs.interrupted(filesDir) ?: return
        if (job.attempts >= MAX_ATTEMPTS || !models.ready()) {
            ProcessingJobs.update(filesDir, job.copy(phase = ProcessingJob.Phase.FAILED, failure = job.failure ?: Failure.UNKNOWN), persist = true)
            return
        }
        ProcessingService.start(this, job.noteId, job.name, job.speakers)
        if (job.noteId == session.libraryId) { watched = job; setStatus(R.string.processing_resuming); refreshControls() }
    }
    private fun returnToLibrary() {
        persistSession()
        startActivity(Intent(this, LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
    @Deprecated("Framework back callback")
    override fun onBackPressed() {
        if (!busy) returnToLibrary()
    }
    companion object {
        private const val NOTIFICATIONS = 41
        /** One attempt per process: a network that cannot reach the detector must not be retried at every note. */
        private val detectorAttempted = java.util.concurrent.atomic.AtomicBoolean()

        /** The detector is optional (without it recordings are cut at quiet points), so a failed fetch is only logged. */
        private fun installSpeechDetector(filesDir: File) {
            if (ModelStore.vadReady(filesDir)) return
            try { ModelStore.installVad(filesDir) { } } catch (error: Exception) { Log.w(TAG, "Speech detector unavailable", error) }
        }

        /** Runs of one job before a job that keeps ending the process is given up. */
        private const val MAX_ATTEMPTS = 4
        const val OPEN_FILE = 1; const val SAVE_DOCUMENT = 2; private const val TAG = "Cassini"
        private const val SEARCH_DEBOUNCE_MS = 250L
        private const val DEBOUNCED_WORDS = 2_000
        const val NOTE_ID = "noteId"; const val SEARCH_QUERY = "searchQuery"
        const val REQUEST_IMPORT = "requestImport"; const val AUTO_TRANSCRIBE = "autoTranscribe"
    }
}
