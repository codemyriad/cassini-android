package org.cassini.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.util.UUID

/**
 * Owns the microphone while a note records, so capture continues with the screen off or another app in front.
 * A partial wake lock keeps the CPU running; audio focus loss (a call, another recorder) pauses until the user resumes.
 */
class RecordingService : Service() {
    interface Listener {
        fun onRecordingChanged()
        /** [noteId] is null when nothing could be saved. */
        fun onRecordingFinished(noteId: String?, autoTranscribe: Boolean, failed: Boolean)
    }
    enum class Notice { NONE, FOCUS, NEAR_LIMIT }
    inner class Local : Binder() { val service get() = this@RecordingService }

    internal var capture: MicrophoneRecording? = null
        private set
    var listener: Listener? = null
    var title = ""
    var notice = Notice.NONE
        private set
    val stopping get() = finishing
    private var finishing = false
    private var previous = Session()
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val check = object : Runnable {
        override fun run() {
            val recording = capture ?: return
            val elapsed = recording.elapsedMs
            val space = recording.file.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (elapsed >= Limits.MAX_RECORDING_MS || space < Limits.MIN_RECORDING_FREE_BYTES) { finish(false); return }
            if (elapsed >= Limits.MAX_RECORDING_MS - Limits.RECORDING_WARNING_MS && notice != Notice.NEAR_LIMIT) {
                notice = Notice.NEAR_LIMIT; changed()
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))
    override fun onBind(intent: Intent): IBinder = Local()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PAUSE -> togglePause()
            STOP -> finish(false)
        }
        if (capture == null && !finishing) stopSelf(startId)
        return START_NOT_STICKY
    }

    /** Starts capture; called from the visible activity, as Android 14 requires for a microphone service. */
    internal fun begin(name: String) {
        if (capture != null) return
        title = name; notice = Notice.NONE
        previous = SessionStore(filesDir).load()
        val directory = File(filesDir, "documents").also { check(it.mkdirs() || it.isDirectory) }
        val file = File(directory, "${UUID.randomUUID()}.rec.${MicrophoneRecording.extension()}")
        live = file
        val recording = MicrophoneRecording.create(this, file) { handler.post { if (capture != null) finish(false, failed = true) } }
        capture = recording
        try {
            startForegroundService(Intent(this, RecordingService::class.java))
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notification())
        } catch (error: Exception) { capture = null; live = null; recording.release(); file.delete(); throw error }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Cassini:recording")
            .apply { setReferenceCounted(false); acquire(Limits.MAX_RECORDING_MS + 10 * 60_000L) }
        requestFocus()
        handler.postDelayed(check, 1000)
    }

    internal fun togglePause() {
        val recording = capture ?: return
        recording.togglePause()
        if (!recording.paused && notice == Notice.FOCUS) notice = Notice.NONE
        changed()
    }

    /** Stops and catalogues synchronously; the audio is kept whatever went wrong. */
    internal fun finish(autoTranscribe: Boolean, failed: Boolean = false) {
        val recording = capture ?: return
        capture = null; finishing = true
        handler.removeCallbacks(check)
        var noteId: String? = null
        var error = failed
        try {
            val file = try { recording.stop() } catch (e: Exception) {
                android.util.Log.e("Cassini", "Recording did not stop cleanly", e); error = true
                recording.file.takeIf { it.exists() && it.length() > 0 }
            }
            val name = title.trim().ifEmpty { getString(R.string.recording) } + "." + recording.file.extension
            if (file != null) noteId = catalogue(file, recording.elapsedMs, name)
        } catch (e: Exception) { android.util.Log.e("Cassini", "Could not catalogue retained recording", e); error = true }
        finally {
            abandonFocus()
            wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            finishing = false; live = null
        }
        listener?.onRecordingFinished(noteId, autoTranscribe && !error, error)
    }

    private fun catalogue(file: File, elapsed: Long, name: String): String? {
        val sessions = SessionStore(filesDir)
        val preferences = sessions.load()
        val captured = Session(uri = Uri.fromFile(file).toString(), libraryId = UUID.randomUUID().toString(), name = name, durationMs = elapsed)
        // If another screen selected a note while recording, retain that selection.
        // Otherwise keep a durable session reference even if updating the catalogue fails.
        val stillCurrent = preferences.uri == previous.uri && preferences.libraryId == previous.libraryId && preferences.screen == previous.screen
        if (stillCurrent) sessions.save(captured)
        val session = LibraryStore(filesDir).save(captured)
        if (stillCurrent) sessions.save(session)
        return session.libraryId
    }

    private fun changed() {
        if (capture != null) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
        listener?.onRecordingChanged()
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change != AudioManager.AUDIOFOCUS_LOSS && change != AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) return@setOnAudioFocusChangeListener
                val recording = capture ?: return@setOnAudioFocusChangeListener
                if (!recording.paused) { recording.togglePause(); notice = Notice.FOCUS; changed() }
            }, handler).build()
        focus = request
        audio.requestAudioFocus(request)
    }
    private fun abandonFocus() { focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW))
        val paused = capture?.paused == true
        fun action(action: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, RecordingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, RecordingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_record).setOngoing(true).setContentIntent(open)
            .setContentTitle(title.ifBlank { getString(R.string.recording) })
            .setContentText(getString(when {
                notice == Notice.NEAR_LIMIT -> R.string.recording_near_limit
                notice == Notice.FOCUS && paused -> R.string.recording_focus_paused
                paused -> R.string.recording_paused
                else -> R.string.recording_active
            }))
            .setUsesChronometer(!paused).setWhen(System.currentTimeMillis() - (capture?.elapsedMs ?: 0))
            .addAction(Notification.Action.Builder(null, getString(if (paused) R.string.resume_recording else R.string.pause_recording), action(PAUSE, 1)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_recording), action(STOP, 2)).build())
            .build()
    }

    override fun onDestroy() { if (capture != null) finish(false); handler.removeCallbacksAndMessages(null); super.onDestroy() }

    companion object {
        /** The capture this process is writing; [RecoveryScanner] leaves it alone. */
        @Volatile internal var live: File? = null
        private const val CHANNEL = "recording"
        private const val NOTIFICATION = 1
        private const val PAUSE = "org.cassini.android.recording.PAUSE"
        private const val STOP = "org.cassini.android.recording.STOP"
    }
}
