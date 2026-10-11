package org.cassini.android.recording

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cassini.android.processing.ProcessingCoordinator
import org.cassini.android.processing.ServiceStarter
import org.cassini.android.processing.StartOrigin
import org.cassini.android.processing.TranscribeOutcome
import org.cassini.core.library.JobKind
import org.cassini.data.models.ModelRepository
import org.cassini.data.notes.NoteRepository
import org.cassini.data.settings.SettingsRepository
import org.cassini.engine.capture.CaptureFactory
import org.cassini.engine.capture.MicrophoneCapture
import java.io.File

// The seams of RecordingController: the capture, the Android services it uses, the note rows and the hand-off to
// processing. The app uses the implementations below; JVM tests pass fakes.

/** Chooses the capture format and starts captures ([CaptureFactory] in the app). */
interface CaptureSource {
    suspend fun format(): CaptureFormat

    /** Starts capturing into [file]; throws when the microphone cannot start. [onError] runs on the capture thread. */
    fun start(file: File, onError: () -> Unit): MicrophoneCapture
}

class FactoryCaptureSource(context: Context) : CaptureSource {
    private val app = context.applicationContext

    override suspend fun format(): CaptureFormat = CaptureFormat.of(CaptureFactory.extension())

    override fun start(file: File, onError: () -> Unit): MicrophoneCapture = CaptureFactory.create(app, file, onError)
}

/** Watches for another app that silences the capture; [close] ends the watch. */
interface SilenceWatch {
    /** The user resumed: on API 26-28 the focus request is made again. */
    fun resumed() = Unit

    fun close()
}

/** The Android services the recording uses. */
interface RecordingPlatform {
    /** `startForegroundService(RecordingService)` inside a catch; false when Android refused the start. */
    fun startService(): Boolean

    /**
     * Holds the CPU awake for the next [RecordingController.WAKE_LOCK_TIMEOUT_MS]. The policy loop calls it again
     * each minute, so the lock lasts as long as the recording, and a process that stops calling it lets it go.
     */
    fun acquireWakeLock()

    fun releaseWakeLock()

    /** Free bytes on the volume of [directory]. */
    fun usableBytes(directory: File): Long

    /**
     * Calls [onSilenced] with true when another app silences [capture] and with false when that ends. API 29+:
     * `AudioManager.registerAudioRecordingCallback`, matched on the capture's own session id; no audio focus is
     * requested, so the user's music keeps playing. API 26-28: a focus request whose loss counts as silence.
     */
    fun watchSilence(capture: MicrophoneCapture, onSilenced: (Boolean) -> Unit): SilenceWatch
}

class AndroidRecordingPlatform(context: Context) : RecordingPlatform {
    private val app = context.applicationContext
    private val audio: AudioManager? get() = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun startService(): Boolean = try {
        ContextCompat.startForegroundService(app, RecordingService.startIntent(app))
        true
    } catch (error: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (API 31+) and the background start limit.
        Log.w(TAG, "Android refused to start the recording service", error)
        false
    } catch (error: SecurityException) {
        Log.w(TAG, "Android refused to start the recording service", error)
        false
    }

    @Synchronized
    override fun acquireWakeLock() {
        val lock = wakeLock ?: app.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        lock?.acquire(RecordingController.WAKE_LOCK_TIMEOUT_MS)
    }

    @Synchronized
    override fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    override fun usableBytes(directory: File): Long = directory.usableSpace

    override fun watchSilence(capture: MicrophoneCapture, onSilenced: (Boolean) -> Unit): SilenceWatch {
        val manager = audio ?: return object : SilenceWatch {
            override fun close() = Unit
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            recordingCallback(manager, capture, onSilenced)
        } else {
            focusListener(manager, onSilenced)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun recordingCallback(
        manager: AudioManager,
        capture: MicrophoneCapture,
        onSilenced: (Boolean) -> Unit,
    ): SilenceWatch {
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val session = capture.sessionId ?: return
                // A paused capture stops its AudioRecord, so its configuration is missing then: nothing changes.
                val own = configs.firstOrNull { it.clientAudioSessionId == session } ?: return
                onSilenced(own.isClientSilenced)
            }
        }
        manager.registerAudioRecordingCallback(callback, main)
        return object : SilenceWatch {
            override fun close() = manager.unregisterAudioRecordingCallback(callback)
        }
    }

    private fun focusListener(manager: AudioManager, onSilenced: (Boolean) -> Unit): SilenceWatch {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(
                { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onSilenced(true)
                        AudioManager.AUDIOFOCUS_GAIN -> onSilenced(false)
                        else -> Unit
                    }
                },
                main,
            )
            .build()
        manager.requestAudioFocus(request)
        return object : SilenceWatch {
            override fun resumed() {
                manager.requestAudioFocus(request)
            }

            override fun close() {
                manager.abandonAudioFocusRequest(request)
            }
        }
    }

    private companion object {
        const val TAG = "RecordingService"
        const val WAKE_LOCK_TAG = "Cassini:recording"
    }
}

/** The note rows of a recording ([NoteRepository] in the app). */
interface RecordingNotes {
    /** Creates `notes/<id>/` and the LIVE_CAPTURE row; returns the id. */
    suspend fun create(title: String, createdAt: Long, format: CaptureFormat): String

    fun directory(noteId: String): File

    suspend fun rename(noteId: String, title: String)

    /** The row becomes PLAIN with [durationMs]. */
    suspend fun finish(noteId: String, durationMs: Long)

    /** Removes the directory and the row of a recording that did not start or captured nothing. */
    suspend fun discard(noteId: String)
}

class RepositoryRecordingNotes(private val notes: NoteRepository, private val directory: (String) -> File) :
    RecordingNotes {
    override suspend fun create(title: String, createdAt: Long, format: CaptureFormat): String =
        notes.createRecording(title, createdAt, format.extension)

    override fun directory(noteId: String): File = directory.invoke(noteId)

    override suspend fun rename(noteId: String, title: String) {
        notes.rename(noteId, title)
    }

    override suspend fun finish(noteId: String, durationMs: Long) {
        notes.finishRecording(noteId, durationMs)
    }

    override suspend fun discard(noteId: String) {
        notes.discardRecording(noteId)
    }
}

/** Whether the model download is agreed to (architecture 5.6). */
interface RecordingConsent {
    suspend fun consented(): Boolean

    /** The user declined the download (`models.declinedAt`). */
    suspend fun declined(): Boolean
}

class SettingsConsent(private val models: ModelRepository, private val settings: SettingsRepository) :
    RecordingConsent {
    override suspend fun consented(): Boolean = models.consented()

    override suspend fun declined(): Boolean = settings.declinedAt.first() != null
}

/** Queues a saved recording and starts processing (architecture 5.8). */
fun interface TranscriptionHandoff {
    /** True when the note is QUEUED (whether or not the service could start). */
    suspend fun handOff(noteId: String): Boolean
}

/**
 * The hand-off of architecture 5.8, called while `RecordingService` is still in the foreground: the row becomes
 * QUEUED, `ProcessingService` is started inside a catch, and the call waits until that service is in the foreground
 * (up to [waitMs]). On a refusal or a timeout the note stays QUEUED and "Ready to transcribe" is posted; ON_START
 * picks it up.
 */
class ProcessingHandoff(
    private val coordinator: () -> ProcessingCoordinator,
    private val settings: SettingsRepository,
    private val starter: ServiceStarter,
    private val main: CoroutineDispatcher,
    private val waitMs: Long = FOREGROUND_WAIT_MS,
) : TranscriptionHandoff {
    override suspend fun handOff(noteId: String): Boolean {
        val processing = coordinator()
        val outcome = processing.transcribe(noteId, JobKind.TRANSCRIBE, StartOrigin.AUTOMATIC, startService = false)
        if (outcome !is TranscribeOutcome.Queued) return false
        // A held queue keeps the note QUEUED; Continue starts it. The starter posts "Ready to transcribe" itself
        // when Android refuses the start.
        val held = settings.queueHold.first() != null
        if (!held && withContext(main) { starter.start() }) {
            val foreground = withTimeoutOrNull(waitMs) { processing.state.first { it.serviceForeground } }
            if (foreground == null) {
                Log.w(TAG, "The processing service did not reach the foreground in $waitMs ms")
                processing.onServiceRefused()
            }
        }
        return true
    }

    private companion object {
        const val TAG = "RecordingService"
        const val FOREGROUND_WAIT_MS = 10_000L
    }
}
