package org.cassini.android

import android.app.Activity
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
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ui: DeckViews
    private lateinit var models: ModelStore
    private lateinit var sessions: SessionStore
    private var session = Session()
    private var player: MediaPlayer? = null
    private var playerReady = false
    private var busy = false
    private var highlightedWord = -1
    private var touchingTranscript = false
    private var followCompletedSeeks = false

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
        session = sessions.load()
        models = modelStore(session.fp32)
        ui = DeckViews(this)
        setContentView(ui.root)
        ui.menu.setOnClickListener { showSettings() }
        ui.open.setOnClickListener { chooseAudio() }
        ui.transcribe.setOnClickListener { transcribe() }
        ui.download.setOnClickListener { downloadModel() }
        ui.export.setOnClickListener { exportTranscript() }
        ui.play.setOnClickListener {
            if (playerReady) player?.let {
                if (it.isPlaying) it.pause() else { it.start(); renderTranscript() }
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
        ui.search.setText(savedInstanceState?.getString("query").orEmpty())
        renderScreen()
        defaultStatus()
        session.uri?.let { preparePlayer(Uri.parse(it)) }
        savedInstanceState?.let { state -> ui.scroll.post { ui.scroll.scrollTo(0, state.getInt("scroll")) } }
    }

    private fun modelStore(fp32: Boolean) = ModelStore(File(filesDir, if (fp32) "parakeet-v3-fp32" else "parakeet-v3"), fp32)

    private fun chooseAudio() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "audio/*"; addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, OPEN_AUDIO)
    }

    private fun downloadModel() = runWork(R.string.preparing_download) {
        models.install { progress -> onUi {
            ui.progress.isIndeterminate = false
            ui.progress.progress = progress.percent
            setStatus(if (progress.verifying) R.string.verifying else R.string.downloading, progress.percent)
        } }
        onUi { setStatus(R.string.download_complete) }
    }

    private fun transcribe() {
        val uri = session.uri?.let(Uri::parse) ?: return
        val chosenModel = models
        runWork(R.string.decoding) {
            val audio = AudioDecoder.decode(this, uri)
            requireUser(audio.durationMs >= 200, Failure.SHORT)
            onUi { setStatus(R.string.transcribing) }
            val began = System.nanoTime()
            val transcript = Parakeet.transcribe(audio, chosenModel)
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
                    .put("wordTimings", "TDT token-derived; punctuation excluded from word ends; no acoustic bounding")
                    .toString(2))
            } catch (error: IOException) { Log.e(TAG, "Could not cache processing artifacts", error) }
            onUi {
                session = session.copy(transcript = transcript, durationMs = audio.durationMs,
                    inferenceMs = elapsed, resultPrecision = chosenModel.precision)
                highlightedWord = -1
                persistSession()
                renderScreen()
                defaultStatus()
            }
        }
    }

    private fun exportTranscript() {
        if (session.transcript == null) return
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE)
            val name = session.name.ifBlank { getString(R.string.recording) }
            putExtra(Intent.EXTRA_TITLE, "${name.substringBeforeLast('.')}.words.json")
        }, SAVE_WORDS)
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
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    ui.progress.visibility = View.GONE
                    refreshControls()
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
            session.transcript != null -> setStatus(R.string.inference_time, session.inferenceMs / 1000.0)
            session.uri != null -> setStatus(R.string.file_ready)
            else -> ui.status.text = ""
        }
    }

    private fun refreshControls() {
        val ready = models.ready()
        ui.model.text = getString(if (ready) R.string.model_ready else R.string.model_missing, models.precision)
        ui.download.text = getString(R.string.download_model, getString(if (models.fp32) R.string.model_size_fp32 else R.string.model_size_int8))
        ui.download.visibility = if (ready) View.GONE else View.VISIBLE
        ui.menu.isEnabled = !busy
        ui.open.isEnabled = !busy
        ui.transcribe.isEnabled = !busy && session.uri != null && ready
        ui.download.isEnabled = !busy
        ui.export.isEnabled = !busy && session.transcript != null
        ui.play.isEnabled = playerReady
        ui.seek.isEnabled = playerReady
        ui.back.isEnabled = playerReady
        ui.forward.isEnabled = playerReady
        listOf(ui.menu, ui.open, ui.transcribe, ui.download, ui.export, ui.play, ui.back, ui.forward)
            .forEach { it.alpha = if (it.isEnabled) 1f else .4f }
    }

    private fun renderScreen() {
        ui.filename.text = session.name.ifBlank { getString(R.string.no_file) }
        ui.caption.text = getString(R.string.voice_caption, getString(R.string.italian))
        ui.open.setText(if (session.uri == null) R.string.open_audio else R.string.change_audio)
        ui.transcribe.setText(if (session.transcript == null) R.string.transcribe else R.string.transcribe_again)
        ui.seek.max = session.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        ui.position.text = getString(R.string.position, clock(session.positionMs.toLong()), clock(session.durationMs))
        val transcript = session.transcript
        ui.details.visibility = if (transcript == null) View.GONE else View.VISIBLE
        transcript?.let {
            ui.details.text = getString(R.string.transcript_details,
                resources.getQuantityString(R.plurals.word_count, it.words.size, it.words.size),
                clock(session.durationMs), session.resultPrecision)
        }
        val hasWords = transcript?.words?.isNotEmpty() == true
        ui.searchRow.visibility = if (hasWords) View.VISIBLE else View.GONE
        ui.voice.visibility = if (hasWords) View.VISIBLE else View.GONE
        ui.transcript.visibility = if (hasWords) View.VISIBLE else View.GONE
        ui.empty.visibility = if (hasWords) View.GONE else View.VISIBLE
        ui.emptyTitle.setText(when {
            transcript != null -> R.string.no_speech
            session.uri != null -> R.string.loaded_title
            else -> R.string.empty_title
        })
        ui.emptyHint.setText(when {
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
        val active = session.transcript?.words?.indexOfFirst { time >= it.startMs && time < it.endMs } ?: -1
        if (active != highlightedWord || followSeek) {
            highlightedWord = active
            renderTranscript(followPausedSeek = followSeek)
        }
    }

    private fun renderTranscript(followPausedSeek: Boolean = false) {
        val words = session.transcript?.words ?: emptyList()
        val builder = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        words.forEachIndexed { index, word ->
            if (index > 0) {
                val previous = words[index - 1]
                builder.append(if (previous.speaker != word.speaker || previous.text.lastOrNull() in listOf('.', '?', '!')) "\n\n" else " ")
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
            if (index == highlightedWord) {
                spannable.setSpan(BackgroundColorSpan(DeckViews.amber), range.first, range.last + 1, 0)
                spannable.setSpan(ForegroundColorSpan(DeckViews.ink), range.first, range.last + 1, 0)
            }
        }
        ui.transcript.text = spannable
        ui.clearSearch.isEnabled = query.isNotEmpty()
        ui.clearSearch.alpha = if (query.isEmpty()) .4f else 1f
        ui.matches.visibility = if (query.isEmpty() || words.isEmpty()) View.GONE else View.VISIBLE
        ui.matches.text = if (matchCount == 0) getString(R.string.no_matches)
            else resources.getQuantityString(R.plurals.search_matches, matchCount, matchCount)
        ranges.getOrNull(highlightedWord)?.let { range ->
            // Spans may have requested a new text layout. Measure after that layout settles.
            ui.transcript.post {
                if (!isDestroyed && playerReady && (player?.isPlaying == true || followPausedSeek) && !touchingTranscript) {
                    followWord(range)
                }
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
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            OPEN_AUDIO -> {
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: SecurityException) { /* A provider may offer only a temporary grant. */ }
                val name = try { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } } catch (_: Exception) { null }
                session = Session(uri = uri.toString(), name = name.orEmpty(), fp32 = session.fp32)
                highlightedWord = -1
                ui.search.text.clear()
                renderScreen()
                defaultStatus()
                persistSession()
                preparePlayer(uri)
            }
            SAVE_WORDS -> {
                val json = session.transcript?.json() ?: return
                runWork(R.string.saving) {
                    val output = contentResolver.openOutputStream(uri, "wt") ?: throw UserFacingException(Failure.SAVE)
                    output.bufferedWriter().use { it.write(json) }
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
            session = session.copy(durationMs = it.duration.toLong())
            ui.seek.max = it.duration
            it.seekTo(session.positionMs.coerceIn(0, it.duration))
            refreshControls()
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

    private fun showSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun persistSession() {
        if (playerReady) player?.let { session = session.copy(positionMs = it.currentPosition) }
        // Settings can change the model while this activity is stopped or being recreated.
        session = session.copy(fp32 = sessions.load().fp32)
        try { sessions.save(session) } catch (error: Exception) { Log.e(TAG, "Could not persist session", error) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        persistSession()
        outState.putString("query", ui.search.text.toString())
        outState.putInt("scroll", ui.scroll.scrollY)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        val fp32 = sessions.load().fp32
        if (fp32 != session.fp32) {
            session = session.copy(fp32 = fp32)
            models = modelStore(fp32)
            refreshControls()
        }
        handler.post(ticker)
    }
    override fun onPause() {
        handler.removeCallbacks(ticker)
        if (playerReady) player?.pause()
        persistSession()
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        worker.shutdownNow()
        player?.release()
        super.onDestroy()
    }
    private fun clock(ms: Long) = String.format(Locale.ROOT, "%02d:%02d", ms / 60000, ms / 1000 % 60)
    companion object { const val OPEN_AUDIO = 1; const val SAVE_WORDS = 2; private const val TAG = "Cassini" }
}
