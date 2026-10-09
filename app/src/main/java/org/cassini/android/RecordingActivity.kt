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
    private lateinit var space: TextView
    private lateinit var waveform: WaveformView
    private var service: RecordingService? = null
    private val capture get() = service?.capture
    internal val savingAudio: Boolean get() = service?.stopping == true
    private var foreground = false
    private var wantCapture = false

    private val handler = Handler(Looper.getMainLooper())
    private var savedNote: String? = null
    private var permissionDenied = false
    private var spaceCheckedMs = -1L
    private val tick = object : Runnable {
        override fun run() {
            val recording = capture ?: return
            timer.text = clock(recording.elapsedMs)
            waveform.push(recording.amplitude / 32768f)
            if (spaceCheckedMs < 0 || recording.elapsedMs - spaceCheckedMs >= 5000) { spaceCheckedMs = recording.elapsedMs; showSpace(recording) }
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
        views = NoteViews(this, dark = true)
        val root = views.column().apply {
            setBackgroundColor(Palette.night); setPadding(views.dp(8), views.dp(12), views.dp(16), views.dp(24))
        }
        val header = views.row()
        // Capture lives in RecordingService: leaving this screen keeps recording, and the library offers a way back.
        views.add(header, views.iconButton(R.drawable.ic_chevron_down, R.string.minimise_recording, Palette.nightText).apply {
            setOnClickListener {
                startActivity(Intent(this@RecordingActivity, LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }, -2)
        status = views.text(15f, Palette.nightRecord, medium = true).apply {
            id = R.id.capture_status; gravity = Gravity.CENTER; setText(R.string.recording_preparing)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        views.add(header, status, 0, weight = 1f)
        format = views.text(12f, Palette.nightMuted, mono = true).apply {
            text = getString(if (MicrophoneRecording.extension() == "opus") R.string.format_opus else R.string.format_aac)
            contentDescription = getString(if (MicrophoneRecording.extension() == "opus") R.string.microphone_format else R.string.microphone_format_aac)
            background = views.shape(Palette.night, 8, Palette.nightLine); setPadding(views.dp(8), views.dp(4), views.dp(8), views.dp(4))
        }
        views.add(header, format, -2)
        views.add(root, header)

        val content = views.column().apply { setPadding(views.dp(8), 0, 0, 0) }
        views.add(content, views.text(13f, Palette.nightMuted).apply { setText(R.string.note_title_hint) }, top = 24)
        title = EditText(this).apply {
            id = R.id.capture_title; setHint(R.string.recording_title); maxLines = 2
            textSize = 22f; setTextColor(Palette.nightText); setHintTextColor(Palette.nightMuted)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = views.underline(Palette.nightLine); setPadding(0, views.dp(6), 0, views.dp(10))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(getString(R.string.voice_note_name, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())))
        }
        views.add(content, title, top = 4)
        timer = views.text(68f, Palette.nightText, mono = true).apply { id = R.id.capture_timer; text = clock(0); gravity = Gravity.CENTER }
        views.add(content, timer, top = 40)
        waveform = WaveformView(this, views).apply { contentDescription = getString(R.string.microphone_level) }
        views.add(content, waveform, height = views.dp(110), top = 24)
        views.add(content, views.text(14f, Palette.nightMuted).apply {
            setText(R.string.recording_hint); gravity = Gravity.CENTER; setLineSpacing(views.dp(3).toFloat(), 1f)
        }, top = 24)
        space = views.text(13f, Palette.nightMuted).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        views.add(content, space, top = 6)
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(content) }
        views.add(root, scroll, height = 0, weight = 1f)

        val bottom = views.column().apply { setPadding(views.dp(8), 0, 0, 0) }
        start = views.button(R.string.enable_microphone, NoteViews.Style.RECORD).apply {
            id = R.id.capture_start
            setOnClickListener {
                if (permissionDenied && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } else requestCapture()
            }
        }
        views.add(bottom, start, top = 16)
        val controls = views.row()
        pause = views.button(R.string.pause_recording, NoteViews.Style.OUTLINE, R.drawable.ic_pause, height = 60).apply {
            id = R.id.capture_pause; isEnabled = false
            setOnClickListener { try { service?.togglePause() } catch (error: Exception) { recordingFailed(error) } }
        }
        done = views.button(R.string.finish_recording, NoteViews.Style.RECORD, R.drawable.ic_stop, height = 60).apply {
            id = R.id.capture_done; isEnabled = false; setOnClickListener { finishRecording(true) }
        }
        views.add(controls, pause, 0, weight = 1f)
        views.space(controls, 12)
        views.add(controls, done, 0, weight = 1f)
        views.add(bottom, controls, top = 16)
        views.add(root, bottom)
        setContentView(root)
        refreshEnabled()
        savedNote = savedInstanceState?.getString("savedNote")
        wantCapture = savedNote == null && savedInstanceState == null
        bindService(Intent(this, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    /** Recording continues until storage runs low; this says roughly how long that is, from the bytes written so far. */
    private fun showSpace(recording: MicrophoneRecording) {
        val free = (recording.file.parentFile?.usableSpace ?: return) - Limits.MIN_RECORDING_FREE_BYTES
        val written = recording.file.length()
        val rate = if (recording.elapsedMs >= 10_000 && written > 0) written * 1000 / recording.elapsedMs else Limits.RECORDING_BYTES_PER_SECOND
        val seconds = free.coerceAtLeast(0) / rate.coerceAtLeast(1)
        space.text = getString(R.string.recording_space, if (seconds >= 3600) getString(R.string.hours_short, seconds / 3600) else getString(R.string.minutes_short, seconds / 60))
        space.visibility = View.VISIBLE
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
        refreshEnabled()
        onRecordingChanged()
        handler.removeCallbacks(tick); handler.post(tick)
    }

    private fun refreshEnabled() = listOf(pause, done).forEach { it.alpha = if (it.isEnabled) 1f else .4f }

    override fun onRecordingChanged() {
        val bound = service ?: return
        val paused = bound.capture?.paused == true
        pause.setText(if (paused) R.string.resume_recording else R.string.pause_recording)
        pause.setCompoundDrawablesRelative(views.icon(if (paused) R.drawable.ic_play else R.drawable.ic_pause, Palette.nightText, 20), null, null, null)
        status.setTextColor(if (paused) Palette.nightMuted else Palette.nightRecord)
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
        pause.isEnabled = false; done.isEnabled = false; refreshEnabled()
        status.setText(R.string.recording_saving)
        bound.finish(autoTranscribe)
    }

    override fun onRecordingFinished(noteId: String?, autoTranscribe: Boolean, failed: Boolean) {
        handler.removeCallbacks(tick)
        pause.isEnabled = false; done.isEnabled = false; refreshEnabled()
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
        pause.isEnabled = false; done.isEnabled = false; refreshEnabled(); start.visibility = View.VISIBLE
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

    /** The last seven seconds of microphone level, newest on the right in the recording colour. */
    private class WaveformView(context: Context, private val views: NoteViews) : View(context) {
        private val levels = ArrayDeque<Float>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        fun push(level: Float) { levels.addLast(level.coerceIn(0f, 1f)); while (levels.size > BARS) levels.removeFirst(); invalidate() }
        override fun onDraw(canvas: Canvas) {
            paint.strokeWidth = views.dp(3).toFloat()
            val spacing = width / BARS.toFloat()
            levels.forEachIndexed { i, amplitude ->
                val half = (height * .48f * kotlin.math.sqrt(amplitude)).coerceAtLeast(views.dp(2).toFloat())
                val x = (BARS - levels.size + i) * spacing + spacing / 2
                paint.color = if (i >= levels.size - 8) Palette.nightRecord else Palette.nightLine
                canvas.drawLine(x, height / 2f - half, x, height / 2f + half, paint)
            }
        }
        companion object { const val BARS = 70 }
    }
    companion object { private const val MICROPHONE = 10; const val AUTO_TRANSCRIBE = "captureAutoTranscribe" }
}
