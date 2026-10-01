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

class RecordingActivity : Activity() {
    private lateinit var views: NoteViews
    private lateinit var title: EditText
    private lateinit var timer: TextView
    private lateinit var status: TextView
    private lateinit var pause: Button
    private lateinit var done: Button
    private lateinit var start: Button
    private lateinit var waveform: WaveformView
    private var capture: MicrophoneRecording? = null
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
        views.add(content, views.text(12f, DeckViews.muted).apply { gravity = Gravity.CENTER; setText(R.string.microphone_format) }, top = 12)
        timer = views.text(64f, DeckViews.paper, true).apply { id = R.id.capture_timer; text = getString(R.string.note_clock, 0, 0); gravity = Gravity.CENTER }
        views.add(content, timer, top = 30)
        waveform = WaveformView(this, views).apply { contentDescription = getString(R.string.microphone_level) }
        views.add(content, waveform, height = views.dp(170), top = 24)
        views.add(content, views.text(15f, DeckViews.muted).apply {
            setText(R.string.recording_hint); setLineSpacing(views.dp(4).toFloat(), 1f)
        }, top = 16)
        views.add(root, ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(content) }, height = 0, weight = 1f)
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
        if (capture != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE)
            return
        }
        permissionDenied = false
        try {
            val directory = File(filesDir, "documents").also { check(it.mkdirs() || it.isDirectory) }
            capture = MicrophoneRecording(this, File(directory, "${UUID.randomUUID()}.m4a"),
                onLimit = { finishRecording(true) }, onError = { recordingFailed(IllegalStateException("Microphone error")) })
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
        handler.removeCallbacks(tick)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        var finalized = false
        try {
            val elapsed = recording.elapsedMs
            val file = recording.stop()
            finalized = true
            val sessions = SessionStore(filesDir)
            val preferences = sessions.load()
            val captured = Session(uri = Uri.fromFile(file).toString(), libraryId = UUID.randomUUID().toString(),
                name = title.text.toString().trim().ifEmpty { getString(R.string.recording) } + ".m4a",
                durationMs = elapsed, modelChoice = preferences.modelChoice, fp32 = preferences.fp32)
            // Keep a durable current-session reference even if updating the catalogue fails.
            sessions.save(captured)
            val session = LibraryStore(filesDir).save(captured)
            sessions.save(session)
            savedNote = session.libraryId
            if (autoTranscribe) openSavedNote(intent.getBooleanExtra(AUTO_TRANSCRIBE, true))
            else { status.setText(R.string.recording_saved_interrupted); pause.isEnabled = false; done.isEnabled = false }
        } catch (error: Exception) {
            recordingFailed(error)
            if (finalized) status.setText(R.string.library_error)
        }
    }

    private fun openSavedNote(autoTranscribe: Boolean) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.NOTE_ID, savedNote)
            .putExtra(MainActivity.AUTO_TRANSCRIBE, autoTranscribe))
        savedNote = null
        finish()
    }

    private fun recordingFailed(error: Exception) {
        android.util.Log.e("Cassini", "Microphone recording failed", error)
        val recording = capture
        capture = null
        recording?.release(); recording?.file?.delete()
        handler.removeCallbacks(tick); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.setText(R.string.error_microphone)
        pause.isEnabled = false; done.isEnabled = false; start.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        if (savedNote != null) openSavedNote(false)
        else if (permissionDenied && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestCapture()
    }
    override fun onPause() {
        // No background service yet: finalize before leaving the foreground, retaining the note.
        finishRecording(false)
        super.onPause()
    }
    @Deprecated("Framework back callback")
    override fun onBackPressed() { if (capture != null) finishRecording(true) else super.onBackPressed() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("savedNote", savedNote); super.onSaveInstanceState(outState) }
    override fun onDestroy() { handler.removeCallbacks(tick); capture?.release(); super.onDestroy() }

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
