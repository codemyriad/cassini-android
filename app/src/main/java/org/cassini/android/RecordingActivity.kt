package org.cassini.android

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.DateFormat
import java.util.Date

class RecordingActivity : Activity(), RecordingService.Listener {
    private lateinit var views: NoteViews
    private lateinit var title: EditText
    private lateinit var timer: TextView
    private lateinit var status: TextView
    private lateinit var pause: Button
    private lateinit var done: Button
    private lateinit var start: Button
    private lateinit var format: TextView
    private lateinit var waveform: WaveformView
    private var service: RecordingService? = null
    private val capture get() = service?.capture
    internal val savingAudio: Boolean get() = service?.stopping == true
    private var foreground = false
    private var wantCapture = false

    private val handler = Handler(Looper.getMainLooper())
    private var savedNote: String? = null
    private var permissionDenied = false
    private val tick = object : Runnable {
        override fun run() {
            val recording = capture ?: return
            timer.text = clock(recording.elapsedMs)
            waveform.push(recording.amplitude / 32768f)
            handler.postDelayed(this, 100)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = (binder as RecordingService.Local).service
            service = bound; bound.listener = this@RecordingActivity
            if (bound.capture != null) { title.setText(bound.title); showRecording() }
            else if (wantCapture) requestCapture()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
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
        format = views.text(12f, DeckViews.muted).apply {
            gravity = Gravity.CENTER
            setText(if (MicrophoneRecording.extension() == "opus") R.string.microphone_format else R.string.microphone_format_aac)
        }
        views.add(content, format, top = 12)
        timer = views.text(64f, DeckViews.paper, true).apply { id = R.id.capture_timer; text = clock(0); gravity = Gravity.CENTER }
        views.add(content, timer, top = 30)
        waveform = WaveformView(this, views).apply { contentDescription = getString(R.string.microphone_level) }
        views.add(content, waveform, height = views.dp(170), top = 24)
        val hint = views.text(15f, DeckViews.muted).apply {
            setText(R.string.recording_hint); setLineSpacing(views.dp(4).toFloat(), 1f)
        }
        views.add(content, hint, top = 16)
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(content) }
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
            setOnClickListener { try { service?.togglePause() } catch (error: Exception) { recordingFailed(error) } }
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
        wantCapture = savedNote == null && savedInstanceState == null
        bindService(Intent(this, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun requestCapture() {
        val bound = service ?: run { wantCapture = true; return }
        wantCapture = false
        if (bound.capture != null || bound.stopping) return
        val missing = listOfNotNull(Manifest.permission.RECORD_AUDIO.takeIf { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED },
            Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= 33 && checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED && !notificationsAsked })
        if (Manifest.permission.RECORD_AUDIO in missing) {
            notificationsAsked = true
            requestPermissions(missing.toTypedArray(), MICROPHONE)
            return
        }
        permissionDenied = false
        try {
            bound.begin(title.text.toString())
            showRecording()
        } catch (error: Exception) { recordingFailed(error) }
    }
    private var notificationsAsked = false

    private fun showRecording() {
        start.visibility = View.GONE; pause.isEnabled = true; done.isEnabled = true
        onRecordingChanged()
        handler.removeCallbacks(tick); handler.post(tick)
    }

    override fun onRecordingChanged() {
        val bound = service ?: return
        val paused = bound.capture?.paused == true
        pause.setText(if (paused) R.string.resume_recording else R.string.pause_recording)
        status.setText(when {
            bound.notice == RecordingService.Notice.NEAR_LIMIT -> R.string.recording_near_limit
            bound.notice == RecordingService.Notice.FOCUS && paused -> R.string.recording_focus_paused
            paused -> R.string.recording_paused
            else -> R.string.recording_active
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MICROPHONE) return
        val microphone = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        if (microphone >= 0 && grantResults.getOrNull(microphone) == PackageManager.PERMISSION_GRANTED) requestCapture()
        else { permissionDenied = true; status.setText(R.string.microphone_permission_denied) }
    }

    private fun finishRecording(autoTranscribe: Boolean) {
        val bound = service ?: return
        if (bound.capture == null) return
        bound.title = title.text.toString()
        handler.removeCallbacks(tick)
        pause.isEnabled = false; done.isEnabled = false
        status.setText(R.string.recording_saving)
        bound.finish(autoTranscribe)
    }

    override fun onRecordingFinished(noteId: String?, autoTranscribe: Boolean, failed: Boolean) {
        handler.removeCallbacks(tick)
        pause.isEnabled = false; done.isEnabled = false
        if (failed) {
            status.setText(if (noteId != null) R.string.recording_error_saved else R.string.error_microphone)
            start.visibility = View.VISIBLE
            return
        }
        if (noteId == null) { status.setText(R.string.library_error); return }
        savedNote = noteId
        if (foreground && !isDestroyed) openSavedNote(autoTranscribe && intent.getBooleanExtra(AUTO_TRANSCRIBE, true))
        else if (!isDestroyed) status.setText(R.string.recording_saved_interrupted)
    }

    private fun openSavedNote(autoTranscribe: Boolean) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.NOTE_ID, savedNote)
            .putExtra(MainActivity.AUTO_TRANSCRIBE, autoTranscribe))
        savedNote = null
        finish()
    }

    private fun recordingFailed(error: Exception) {
        android.util.Log.e("Cassini", "Microphone recording failed", error)
        handler.removeCallbacks(tick)
        status.setText(R.string.error_microphone)
        pause.isEnabled = false; done.isEnabled = false; start.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (savedNote != null) openSavedNote(false)
        else if (permissionDenied && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestCapture()
    }
    // Capture continues in RecordingService while this screen is hidden or the display is off.
    override fun onPause() { foreground = false; if (capture != null) service?.title = title.text.toString(); super.onPause() }
    @Deprecated("Framework back callback")
    override fun onBackPressed() { if (capture != null) finishRecording(true) else super.onBackPressed() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("savedNote", savedNote); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        handler.removeCallbacks(tick)
        service?.let { if (it.listener === this) it.listener = null }
        unbindService(connection); service = null
        super.onDestroy()
    }

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
