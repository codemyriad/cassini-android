package org.cassini.android.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import org.cassini.data.notes.NoteRepository
import org.cassini.data.settings.SettingsRepository
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    /** Plays at [speed] (1 = normal), keeping the pitch. */
    fun setSpeed(speed: Float)

    /** Called on the main thread when playback reaches the end. */
    fun onCompletion(listener: () -> Unit)

    fun release()
}

/**
 * [AudioPlayer] over Media3 ExoPlayer (architecture 5.9, coordination D53): Ogg Opus, M4A and the other imports, and
 * time-stretched speeds that keep the pitch. Create and call it on the main thread (the app's looper); its callbacks
 * come there. Audio focus and unplugged headphones stay with [PlaybackPlatform], as with the earlier `MediaPlayer`.
 */
class ExoAudioPlayer(context: Context) : AudioPlayer {
    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            // handleAudioFocus: no, PlaybackPlatform keeps the focus.
            false,
        )
        .setHandleAudioBecomingNoisy(false)
        .build()
    private var completion: (() -> Unit)? = null

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) completion?.invoke()
                }
            },
        )
    }

    override val positionMs: Long get() = player.currentPosition.coerceAtLeast(0)
    override val durationMs: Long get() = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0L

    // As MediaPlayer.isPlaying: true from play() on, also while the player buffers, until pause or the end.
    override val playing: Boolean get() = player.playWhenReady && player.playbackState != Player.STATE_ENDED

    override suspend fun prepare(file: File) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        suspendCancellableCoroutine { continuation ->
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && continuation.isActive) {
                        player.removeListener(this)
                        continuation.resume(Unit)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    player.removeListener(this)
                    if (continuation.isActive) continuation.resumeWithException(IOException("Cannot play $file", error))
                }
            }
            player.addListener(listener)
            continuation.invokeOnCancellation { player.removeListener(listener) }
            if (player.playbackState == Player.STATE_READY) {
                player.removeListener(listener)
                continuation.resume(Unit)
            }
        }
    }

    override fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(player.currentPosition)
        player.play()
    }

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)

    override fun onCompletion(listener: () -> Unit) {
        completion = listener
    }

    override fun release() {
        completion = null
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

/** Where the app-wide playback speed is kept (`SettingsRepository` in the app). */
interface SpeedStore {
    suspend fun load(): Float

    suspend fun save(speed: Float)

    /** Always normal speed, and nothing is kept: tests that do not care about speed. */
    object Fixed : SpeedStore {
        override suspend fun load(): Float = 1f

        override suspend fun save(speed: Float) = Unit
    }
}

/** [SpeedStore] over the app's settings. */
class SettingsSpeedStore(private val settings: SettingsRepository) : SpeedStore {
    override suspend fun load(): Float = settings.playbackSpeed.first() ?: 1f

    override suspend fun save(speed: Float) = settings.setPlaybackSpeed(speed)
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
