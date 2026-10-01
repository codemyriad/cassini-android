package org.cassini.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
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
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var models: ModelStore
    private lateinit var status: TextView
    private lateinit var transcriptView: TextView
    private lateinit var position: TextView
    private lateinit var seek: SeekBar
    private lateinit var search: EditText
    private lateinit var download: Button
    private lateinit var open: Button
    private lateinit var transcribe: Button
    private lateinit var export: Button
    private lateinit var play: Button
    private lateinit var precision: Spinner
    private var selected: Uri? = null
    private var selectedName = "Recording"
    private var result: Transcript? = null
    private var player: MediaPlayer? = null
    private var busy = false
    private var preparingPlayback = false
    private var durationMs = 0L
    private var highlightedWord = -1

    private val ticker = object : Runnable {
        override fun run() {
            val media = player
            if (media != null && !preparingPlayback) {
                val time = media.currentPosition
                seek.progress = time
                position.text = "${clock(time.toLong())} / ${clock(durationMs)}"
                play.text = if (media.isPlaying) "Pause" else "Play"
                val active = result?.words?.indexOfFirst { time >= it.startMs && time < it.endMs } ?: -1
                if (active != highlightedWord) {
                    highlightedWord = active
                    renderTranscript()
                }
            }
            handler.postDelayed(this, 150)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        models = ModelStore(File(filesDir, "parakeet-v3"))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        setContentView(ScrollView(this).apply { addView(column) })
        fun label(text: String, size: Float = 16f): TextView = TextView(this).apply {
            this.text = text; textSize = size; setPadding(0, dp(8), 0, dp(8)); column.addView(this)
        }
        fun button(text: String, action: () -> Unit): Button = Button(this).apply {
            this.text = text; setOnClickListener { action() }; column.addView(this)
        }
        label("Cassini · Italian prototype", 24f)
        label("Import a clip up to 30 seconds. Transcription runs on this device. One speaker; tap words to listen.")
        precision = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Parakeet v3 · INT8 · 640 MiB", "Parakeet v3 · FP32 · 2.37 GiB"))
            column.addView(this)
        }
        download = button("Download Parakeet v3 · 640 MiB") {
            runWork("Preparing model download…") {
                models.install { updateStatus(it) }
                updateStatus("Parakeet ready. Audio stays on this device.")
            }
        }
        open = button("Choose audio file") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "audio/*"; addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, OPEN_AUDIO)
        }
        transcribe = button("Transcribe Italian") {
            val uri = selected ?: return@button
            runWork("Decoding $selectedName…") {
                val audio = AudioDecoder.decode(this, uri)
                require(audio.durationMs >= 200) { "Choose at least 200 milliseconds of audio." }
                updateStatus("Transcribing ${clock(audio.durationMs)} with Parakeet…")
                val began = System.nanoTime()
                val transcript = Parakeet.transcribe(audio, models)
                val elapsedMs = (System.nanoTime() - began) / 1_000_000
                File(filesDir, "latest.words.json").writeText(transcript.json())
                File(filesDir, "latest.processing.json").writeText(JSONObject()
                    .put("model", "nvidia/parakeet-tdt-0.6b-v3")
                    .put("modelRevision", models.revision)
                    .put("runtime", "sherpa-onnx 1.13.7, stock frontend, CPU ${models.precision}, greedy, 2 threads")
                    .put("language", "it (user-selected; automatic multilingual recognition)")
                    .put("durationMs", audio.durationMs).put("inferenceMs", elapsedMs)
                    .put("speakerAttribution", "single recording; no diarization")
                    .put("wordTimings", "TDT token-derived; punctuation excluded from word ends; no acoustic bounding")
                    .toString(2))
                runOnUiThread {
                    if (!isDestroyed) {
                        result = transcript
                        highlightedWord = -1
                        renderTranscript()
                        status.text = "${transcript.words.size} words · audio ${clock(audio.durationMs)} · inference ${elapsedMs / 1000.0}s" +
                            if (transcript.words.isEmpty()) "\nNo speech recognized." else "\nTap any word to seek."
                    }
                }
            }
        }
        export = button("Save Cassini words JSON") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, "${selectedName.substringBeforeLast('.')}.words.json")
            }, SAVE_WORDS)
        }
        status = label(if (models.ready()) "Parakeet ready. Choose an audio file." else "Download the model, then choose an audio file.")
        play = button("Play") {
            player?.let { if (it.isPlaying) it.pause() else it.start() }
        }
        position = label("00:00 / 00:00")
        seek = SeekBar(this).apply {
            column.addView(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser && !preparingPlayback) player?.seekTo(value)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        search = EditText(this).apply {
            hint = "Search transcript"; setSingleLine(true); column.addView(this)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderTranscript() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        transcriptView = label("Your transcription will appear here.", 20f).apply {
            movementMethod = LinkMovementMethod.getInstance()
            setLineSpacing(dp(6).toFloat(), 1f)
        }
        precision.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                models = ModelStore(File(filesDir, if (position == 0) "parakeet-v3" else "parakeet-v3-fp32"), position == 1)
                status.text = if (models.ready()) "${models.precision} ready." else "Download ${models.precision} to continue."
                refreshControls()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        refreshControls()
    }

    private fun runWork(message: String, action: () -> Unit) {
        if (busy) return
        busy = true
        status.text = message
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshControls()
        worker.execute {
            try { action() } catch (error: Exception) {
                updateStatus("Failed: ${error.message ?: error.javaClass.simpleName}")
            } catch (_: OutOfMemoryError) {
                updateStatus("Not enough memory. Try a shorter clip and close other apps.")
            } finally {
                runOnUiThread {
                    if (!isDestroyed) {
                        busy = false
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        refreshControls()
                    }
                }
            }
        }
    }

    private fun updateStatus(message: String) = runOnUiThread { if (!isDestroyed) status.text = message }
    private fun refreshControls() {
        download.isEnabled = !busy && !models.ready()
        download.text = if (models.ready()) "${models.precision} model ready" else
            "Download Parakeet v3 · ${if (models.fp32) "2.37 GiB" else "640 MiB"}"
        precision.isEnabled = !busy
        open.isEnabled = !busy
        transcribe.isEnabled = !busy && selected != null && models.ready()
        export.isEnabled = !busy && result != null
        play.isEnabled = player != null && !preparingPlayback
        seek.isEnabled = play.isEnabled
    }

    @Deprecated("Framework activity result API is sufficient for this dependency-light prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            OPEN_AUDIO -> {
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: SecurityException) { /* Some document providers offer only temporary grants. */ }
                selected = uri
                selectedName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "Recording"
                result = null
                highlightedWord = -1
                transcriptView.text = "Your transcription will appear here."
                status.text = "Selected: $selectedName"
                preparePlayer(uri)
            }
            SAVE_WORDS -> {
                val json = result?.json() ?: return
                runWork("Saving transcript…") {
                    requireNotNull(contentResolver.openOutputStream(uri, "wt")) { "Cannot save transcript." }
                        .bufferedWriter().use { it.write(json) }
                    updateStatus("Saved cassini.words.v1 JSON. Speaker ID: spk_1 (unidentified).")
                }
            }
        }
        refreshControls()
    }

    private fun preparePlayer(uri: Uri) {
        player?.release()
        preparingPlayback = true
        val media = MediaPlayer()
        player = media
        media.setOnPreparedListener {
            preparingPlayback = false
            durationMs = it.duration.toLong()
            seek.max = it.duration
            refreshControls()
        }
        media.setOnErrorListener { _, _, _ ->
            preparingPlayback = false
            media.release()
            if (player === media) player = null
            status.append("\nPlayback is unavailable for this file.")
            refreshControls()
            true
        }
        try {
            media.setDataSource(this, uri)
            media.prepareAsync()
        } catch (error: Exception) {
            media.release(); player = null; preparingPlayback = false
            status.append("\nPlayback unavailable: ${error.message}")
        }
    }

    private fun renderTranscript() {
        val transcript = result ?: return
        val text = transcript.words.joinToString(" ") { it.text }
        val spannable = SpannableString(text)
        var offset = 0
        transcript.words.forEachIndexed { index, word ->
            val end = offset + word.text.length
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    if (!preparingPlayback) player?.let {
                        it.seekTo(word.startMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        it.start()
                    }
                }
                override fun updateDrawState(ds: android.text.TextPaint) { ds.isUnderlineText = false }
            }, offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (index == highlightedWord) spannable.setSpan(BackgroundColorSpan(Color.rgb(170, 215, 255)), offset, end, 0)
            offset = end + 1
        }
        val query = search.text.toString().trim()
        if (query.isNotEmpty()) {
            var start = text.indexOf(query, ignoreCase = true)
            while (start >= 0) {
                spannable.setSpan(BackgroundColorSpan(Color.YELLOW), start, start + query.length, 0)
                start = text.indexOf(query, start + query.length, ignoreCase = true)
            }
        }
        transcriptView.text = if (text.isEmpty()) "No speech recognized." else spannable
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() {
        handler.removeCallbacks(ticker)
        if (!preparingPlayback) player?.pause()
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        worker.shutdownNow()
        player?.release()
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun clock(ms: Long) = "%02d:%02d".format(ms / 60000, ms / 1000 % 60)
    companion object { const val OPEN_AUDIO = 1; const val SAVE_WORDS = 2 }
}
