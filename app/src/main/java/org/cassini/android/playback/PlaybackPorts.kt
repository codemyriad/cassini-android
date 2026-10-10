package org.cassini.android.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import org.cassini.data.notes.NoteRepository
import java.io.File

// The seams of PlaybackController: the player, audio focus and the noisy broadcast, and where positions go. The app
// uses the implementations below; JVM tests pass fakes.

/** One prepared audio file. Every call but [prepare] runs on the main thread. */
interface AudioPlayer {
    val positionMs: Long
    val durationMs: Long
    val playing: Boolean

    /** Opens [file]; throws when it cannot be played. The blocking part runs off the main thread. */
    suspend fun prepare(file: File)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /** Called on the main thread when playback reaches the end. */
    fun onCompletion(listener: () -> Unit)

    fun release()
}

/** [AudioPlayer] over `MediaPlayer` (architecture 5.9). Create it on the main thread, so its callbacks come there. */
class MediaAudioPlayer(private val io: CoroutineDispatcher) : AudioPlayer {
    private val player = MediaPlayer().apply { setAudioAttributes(PlaybackAttributes.speech) }

    @Volatile private var prepared = false

    override val positionMs: Long get() = if (prepared) player.currentPosition.toLong() else 0L
    override val durationMs: Long get() = if (prepared) player.duration.toLong().coerceAtLeast(0) else 0L
    override val playing: Boolean get() = prepared && player.isPlaying

    override suspend fun prepare(file: File) {
        runInterruptible(io) {
            player.setDataSource(file.path)
            player.prepare()
        }
        prepared = true
    }

    override fun play() = player.start()

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST)

    override fun onCompletion(listener: () -> Unit) = player.setOnCompletionListener { listener() }

    override fun release() {
        prepared = false
        player.release()
    }
}

/** Audio focus and the headphones-unplugged broadcast. Main thread only. */
interface PlaybackPlatform {
    /** Requests AUDIOFOCUS_GAIN; [onLoss] runs when another app takes it. False when it was refused. */
    fun requestFocus(onLoss: () -> Unit): Boolean

    fun abandonFocus()

    /** Calls [onNoisy] on `ACTION_AUDIO_BECOMING_NOISY` until [unwatchNoisy]. */
    fun watchNoisy(onNoisy: () -> Unit)

    fun unwatchNoisy()
}

object PlaybackAttributes {
    val speech: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
}

class AndroidPlaybackPlatform(context: Context) : PlaybackPlatform {
    private val app = context.applicationContext
    private val audio: AudioManager? get() = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null
    private var noisy: BroadcastReceiver? = null

    override fun requestFocus(onLoss: () -> Unit): Boolean {
        val manager = audio ?: return true
        abandonFocus()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(PlaybackAttributes.speech)
            // Speech is hard to follow ducked: pause instead.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(
                { change -> if (change < 0) onLoss() },
                main,
            )
            .build()
        focus = request
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandonFocus() {
        focus?.let { audio?.abandonAudioFocusRequest(it) }
        focus = null
    }

    override fun watchNoisy(onNoisy: () -> Unit) {
        unwatchNoisy()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onNoisy()
            }
        }
        noisy = receiver
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun unwatchNoisy() {
        noisy?.let(app::unregisterReceiver)
        noisy = null
    }
}

/** Where playback positions go ([NoteRepository.setPosition] in the app). */
fun interface PositionStore {
    /**
     * Saves [positionMs] for [noteId]. With [final] (pause and leave) the Library preview excerpt is stored as well:
     * `PreviewText` at that position over the selected variant's cached words.
     */
    suspend fun save(noteId: String, positionMs: Long, final: Boolean)
}

class RepositoryPositionStore(private val notes: suspend () -> NoteRepository) : PositionStore {
    override suspend fun save(noteId: String, positionMs: Long, final: Boolean) =
        notes().setPosition(noteId, positionMs, final)
}
