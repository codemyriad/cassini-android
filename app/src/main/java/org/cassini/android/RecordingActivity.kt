package org.cassini.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

class RecordingActivity : Activity() {
    private lateinit var views: NoteViews
    private lateinit var title: EditText
    private lateinit var timer: TextView
    private lateinit var status: TextView
    private lateinit var pause: Button
    private lateinit var done: Button
    private lateinit var start: Button
    private lateinit var waveform: WaveformView
    private var capture: RecordingCapture? = null
    private lateinit var hint: TextView
    private lateinit var format: TextView
    private lateinit var liveStatus: TextView
    private lateinit var liveWords: TextView
    private lateinit var scroll: ScrollView
    private var liveJob: LiveJob? = null
    private var capturedSession: Session? = null
    private var finishingLive = false
    private var stopping = false
    private var foreground = false
    private var interrupted = false

    private class LiveJob(val audio: LiveAudioBuffer, val models: ModelStore) {
        @Volatile var cancelled = false
        var failed = false
        var result: Transcript? = null
        var inferenceMs = 0L
        var thread: Thread? = null
    }
    private val handler = Handler(Looper.getMainLooper())
    private var savedNote: String? = null
    private var permissionDenied = false
    private val tick = object : Runnable {
        override fun run() {
            val recording = capture ?: return
            timer.text = String.format(Locale.ROOT, "%02d:%02d", recording.elapsedMs / 60000, recording.elapsedMs / 1000 % 60)
            waveform.push(recording.amplitude / 32768f)
            if (recording.elapsedMs >= MicrophoneRecording.MAX_DURATION_MS) finishRecording(true)
            else handler.postDelayed(this, 100)
        }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = NoteViews(this)
        val root = views.column().apply {
            setBackgroundColor(DeckViews.ink); setPadding(views.dp(20), views.dp(20), views.dp(20), views.dp(24))
        }
        val content = views.column()
        status = views.text(16f, views.record).apply {
            id = R.id.capture_status; gravity = Gravity.CENTER; setText(R.string.recording_preparing)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        views.add(content, status, top = 12)
        title = views.input(R.string.recording_title).apply {
            id = R.id.capture_title
            setText(getString(R.string.voice_note_name, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())))
            maxLines = 2
        }
        views.add(content, title, top = 32)
        format = views.text(12f, DeckViews.muted).apply { gravity = Gravity.CENTER; setText(R.string.microphone_format) }
        views.add(content, format, top = 12)
        timer = views.text(64f, DeckViews.paper, true).apply { id = R.id.capture_timer; text = getString(R.string.note_clock, 0, 0); gravity = Gravity.CENTER }
        views.add(content, timer, top = 30)
        waveform = WaveformView(this, views).apply { contentDescription = getString(R.string.microphone_level) }
        views.add(content, waveform, height = views.dp(170), top = 24)
        hint = views.text(15f, DeckViews.muted).apply {
            setText(R.string.recording_hint); setLineSpacing(views.dp(4).toFloat(), 1f)
        }
        views.add(content, hint, top = 16)
        liveStatus = views.text(13f, DeckViews.amber).apply { id = R.id.capture_live_status; visibility = View.GONE }
        liveWords = views.text(18f, DeckViews.paper).apply {
            id = R.id.capture_transcript; visibility = View.GONE
            setLineSpacing(views.dp(5).toFloat(), 1f)
        }
        views.add(content, liveStatus, top = 20)
        views.add(content, liveWords, top = 8)
        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(content) }
        views.add(root, scroll, height = 0, weight = 1f)
        start = views.button(R.string.enable_microphone, views.record, DeckViews.ink).apply {
            id = R.id.capture_start
            setOnClickListener {
                if (permissionDenied && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } else requestCapture()
            }
        }
        views.add(root, start, top = 20)
        val controls = views.row()
        pause = views.button(R.string.pause_recording).apply {
            id = R.id.capture_pause; isEnabled = false
            setOnClickListener {
                try {
                    capture?.togglePause()
                    val paused = capture?.paused == true
                    setText(if (paused) R.string.resume_recording else R.string.pause_recording)
                    status.setText(if (paused) R.string.recording_paused else R.string.recording_active)
                } catch (error: Exception) { recordingFailed(error) }
            }
        }
        done = views.button(R.string.finish_recording, DeckViews.paper, DeckViews.ink).apply {
            id = R.id.capture_done; isEnabled = false; setOnClickListener { finishRecording(true) }
        }
        views.add(controls, pause, 0, weight = 1f)
        views.add(controls, Space(this), views.dp(12), 1)
        views.add(controls, done, 0, weight = 1f)
        views.add(root, controls, top = 20)
        setContentView(root)
        savedNote = savedInstanceState?.getString("savedNote")
        if (savedNote == null && savedInstanceState == null) requestCapture()
    }

    private fun requestCapture() {
        if (capture != null || stopping) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE)
            return
        }
        permissionDenied = false
        capturedSession = null
        interrupted = false
        try {
            val directory = File(filesDir, "documents").also { check(it.mkdirs() || it.isDirectory) }
            val preferences = SessionStore(filesDir).load()
            val fp32 = ModelPolicy.resolve(this, preferences.modelChoice)
            val model = ModelStore(File(filesDir, if (fp32) "parakeet-v3-fp32" else "parakeet-v3"), fp32)
            val live = RecordingPreferences.live(this)
            if (live && model.ready()) {
                val job = LiveJob(LiveAudioBuffer(), model)
                liveJob = job
                capture = PcmMicrophoneRecording(File(directory, "${UUID.randomUUID()}.wav"), job.audio,
                    onLimit = { finishRecording(true) }, onError = { recordingFailed(it) })
                format.setText(R.string.microphone_pcm_format)
                hint.setText(R.string.live_recording_hint)
                liveStatus.visibility = View.VISIBLE; liveStatus.setText(R.string.live_recording_loading)
                liveWords.visibility = View.VISIBLE
                startLive(job)
            } else {
                capture = MicrophoneRecording(this, File(directory, "${UUID.randomUUID()}.m4a"),
                    onLimit = { finishRecording(true) }, onError = { recordingFailed(IllegalStateException("Microphone error")) })
                if (live) { liveStatus.visibility = View.VISIBLE; liveStatus.setText(R.string.live_recording_unavailable) }
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            start.visibility = View.GONE; pause.isEnabled = true; done.isEnabled = true
            status.setText(R.string.recording_active)
            handler.post(tick)
        } catch (error: Exception) { recordingFailed(error) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MICROPHONE) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestCapture()
        else { permissionDenied = true; status.setText(R.string.microphone_permission_denied) }
    }

    private fun finishRecording(autoTranscribe: Boolean) {
        val recording = capture ?: return
        capture = null
        stopping = true
        handler.removeCallbacks(tick)
        pause.isEnabled = false; done.isEnabled = false
        status.setText(R.string.recording_saving)
        val name = title.text.toString().trim().ifEmpty { getString(R.string.recording) } + ".${recording.file.extension}"
        val previous = SessionStore(filesDir).load()
        val stopped: () -> Unit = {
            try {
                val file = recording.stop()
                val elapsed = recording.elapsedMs
                if (Looper.myLooper() == Looper.getMainLooper()) saveRecording(file, elapsed, name, previous, autoTranscribe)
                else handler.post { saveRecording(file, elapsed, name, previous, autoTranscribe) }
            } catch (error: Exception) {
                recording.release()
                handler.post { stopping = false; recordingFailed(error) }
            }
        }
        // PCM stop joins the disk writer, including its final fsync. Audio capture is already stopping;
        // keep the screen responsive while slow storage finishes. The worker retains the capture handle.
        if (recording is PcmMicrophoneRecording) Thread(stopped, "Cassini-save-recording").start()
        else stopped()
    }

    private fun saveRecording(file: File, elapsed: Long, name: String, previous: Session, autoTranscribe: Boolean) {
        stopping = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            val sessions = SessionStore(filesDir)
            val preferences = sessions.load()
            val captured = Session(uri = Uri.fromFile(file).toString(), libraryId = UUID.randomUUID().toString(),
                name = name, durationMs = elapsed, modelChoice = preferences.modelChoice, fp32 = preferences.fp32)
            // If another screen selected a note while the file was closing, retain that current selection.
            // Otherwise keep a durable session reference even if updating the catalogue fails.
            val stillCurrent = preferences.uri == previous.uri && preferences.libraryId == previous.libraryId && preferences.screen == previous.screen
            if (stillCurrent) sessions.save(captured)
            val session = LibraryStore(filesDir).save(captured)
            if (stillCurrent) sessions.save(session)
            savedNote = session.libraryId
            capturedSession = session
            val job = liveJob
            if (autoTranscribe && !interrupted && foreground && !isDestroyed && job != null && !job.failed && intent.getBooleanExtra(AUTO_TRANSCRIBE, true)) {
                finishingLive = true
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                status.setText(R.string.live_recording_finishing)
                completeLive(job)
            } else {
                cancelLive()
                if (foreground && !isDestroyed) openSavedNote(autoTranscribe && !interrupted && job == null && intent.getBooleanExtra(AUTO_TRANSCRIBE, true))
                else if (!isDestroyed) status.setText(R.string.recording_saved_interrupted)
            }
        } catch (error: Exception) {
            cancelLive()
            android.util.Log.e("Cassini", "Could not catalogue retained recording", error)
            if (!isDestroyed) status.setText(R.string.library_error)
        }
    }

    private fun startLive(job: LiveJob) {
        job.thread = Thread({
            try {
                val began = System.nanoTime()
                val result = Parakeet.Decoder(job.models).use { decoder ->
                    handler.post { if (liveJob === job && !job.cancelled) liveStatus.setText(R.string.live_recording_waiting) }
                    job.inferenceMs = (System.nanoTime() - began) / 1_000_000
                    LiveTranscription.transcribe(job.audio, { audio, padding ->
                        val decodeBegan = System.nanoTime()
                        decoder.decode(audio, tailPad = padding).also { job.inferenceMs += (System.nanoTime() - decodeBegan) / 1_000_000 }
                    }) { progress ->
                        handler.post {
                            if (liveJob === job && !job.cancelled && !isDestroyed) {
                                val first = liveWords.text.isEmpty()
                                val follow = scroll.getChildAt(0).height - scroll.height - scroll.scrollY <= views.dp(80)
                                liveWords.text = progress.words.joinToString(" ") { it.text }
                                liveStatus.text = getString(R.string.live_recording_progress, progress.doneMs / 60000, progress.doneMs / 1000 % 60)
                                if (progress.words.isNotEmpty() && (first || follow)) scroll.post {
                                    if (!job.cancelled) scroll.smoothScrollTo(0, (liveWords.bottom - scroll.height).coerceAtLeast(0))
                                }
                            }
                        }
                    }
                }
                handler.post {
                    if (liveJob === job && !job.cancelled && !isDestroyed) {
                        job.result = result
                        completeLive(job)
                    }
                }
            } catch (error: Exception) { liveFailed(job, error) }
            catch (error: OutOfMemoryError) { liveFailed(job, error) }
        }, "Cassini-live-transcription").also { it.start() }
    }

    /** Called only after both the microphone file and the last decode have finished. */
    private fun completeLive(job: LiveJob) {
        val session = capturedSession ?: return
        val transcript = job.result ?: return
        if (!finishingLive || job.cancelled || liveJob !== job) return
        job.result = null // Only one packaging job, even if completion callbacks meet.
        job.thread = Thread({
            try {
                if (job.cancelled) return@Thread
                val audio = job.audio.snapshot()
                val processing = JSONObject().put("backend", "sherpa-onnx").put("engine", "Parakeet TDT")
                    .put("model", "nvidia/parakeet-tdt-0.6b-v3 ${job.models.precision}")
                    .put("device", "Android CPU").put("language", "it").put("source", "recording")
                    .put("version", "sherpa-onnx 1.13.7; model revision ${job.models.revision}")
                    .put("x-segmentation", LiveTranscription.PROVENANCE).put("x-inferenceMs", job.inferenceMs)
                val (file, portable) = DocumentStore(applicationContext).create(Uri.parse(session.uri), audio, transcript, session.name, processing, null)
                handler.post {
                    if (job.cancelled || liveJob !== job || isDestroyed) { file.delete(); return@post }
                    try {
                        val complete = session.copy(uri = Uri.fromFile(file).toString(), document = file.absolutePath, transcript = transcript,
                            name = session.name.substringBeforeLast('.') + ".opus", selectedVariant = portable.defaultId,
                            durationMs = audio.durationMs, inferenceMs = job.inferenceMs, resultPrecision = job.models.precision,
                            fp32 = job.models.fp32)
                        val saved = LibraryStore(filesDir).save(complete)
                        SessionStore(filesDir).save(saved)
                        liveJob = null; finishingLive = false
                        openSavedNote(false)
                    } catch (error: Exception) { liveFailed(job, error) }
                }
            } catch (error: Exception) { liveFailed(job, error) }
            catch (error: OutOfMemoryError) { liveFailed(job, error) }
        }, "Cassini-live-document").also { it.start() }
    }

    private fun liveFailed(job: LiveJob, error: Throwable) {
        if (job.cancelled) return
        android.util.Log.e("Cassini", "Live transcription failed; audio retained", error)
        handler.post {
            if (liveJob !== job || job.cancelled || isDestroyed) return@post
            job.failed = true
            liveStatus.setText(R.string.live_recording_failed)
            if (finishingLive) { cancelLive(); openSavedNote(false) }
        }
    }

    private fun cancelLive() {
        liveJob?.let { it.cancelled = true; it.audio.cancel(); it.thread?.interrupt() }
        liveJob = null; finishingLive = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun openSavedNote(autoTranscribe: Boolean) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.NOTE_ID, savedNote)
            .putExtra(MainActivity.AUTO_TRANSCRIBE, autoTranscribe))
        savedNote = null
        finish()
    }

    private fun recordingFailed(error: Exception) {
        android.util.Log.e("Cassini", "Microphone recording failed", error)
        cancelLive()
        val recording = capture
        capture = null
        recording?.release(); recording?.file?.delete()
        handler.removeCallbacks(tick); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.setText(R.string.error_microphone)
        pause.isEnabled = false; done.isEnabled = false; start.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (savedNote != null && !finishingLive) openSavedNote(false)
        else if (permissionDenied && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestCapture()
    }
    override fun onPause() {
        foreground = false
        if (capture != null || stopping || finishingLive) interrupted = true
        // No background service yet: finalize before leaving the foreground, retaining the note.
        finishRecording(false)
        cancelLive()
        super.onPause()
    }
    @Deprecated("Framework back callback")
    override fun onBackPressed() { if (capture != null) finishRecording(true) else super.onBackPressed() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("savedNote", savedNote); super.onSaveInstanceState(outState) }
    override fun onDestroy() { handler.removeCallbacks(tick); cancelLive(); capture?.release(); super.onDestroy() }

    private class WaveformView(context: Context, private val views: NoteViews) : View(context) {
        private val levels = ArrayDeque<Float>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DeckViews.paper; strokeCap = Paint.Cap.ROUND }
        fun push(level: Float) { levels.addLast(level.coerceIn(0f, 1f)); while (levels.size > 70) levels.removeFirst(); invalidate() }
        override fun onDraw(canvas: Canvas) {
            paint.strokeWidth = views.dp(3).toFloat()
            val spacing = width / 70f
            levels.forEachIndexed { i, amplitude ->
                val half = (height * .4f * kotlin.math.sqrt(amplitude)).coerceAtLeast(views.dp(2).toFloat())
                val x = (70 - levels.size + i) * spacing
                canvas.drawLine(x, height / 2f - half, x, height / 2f + half, paint)
            }
            paint.color = views.record
            canvas.drawLine(width - spacing / 2, height * .08f, width - spacing / 2, height * .92f, paint)
            paint.color = DeckViews.paper
        }
    }
    companion object { private const val MICROPHONE = 10; const val AUTO_TRANSCRIBE = "captureAutoTranscribe" }
}
