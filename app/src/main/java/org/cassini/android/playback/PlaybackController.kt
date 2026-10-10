package org.cassini.android.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** What the player shows: the open note, its length, the position (every 150 ms while playing) and play/pause. */
data class PlaybackState(
    val noteId: String? = null,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    /** A speaker sample ([PlaybackController.playRange]) plays; the position returns when it ends. */
    val sample: Boolean = false,
    /** The open file could not be played. */
    val failed: Boolean = false,
)

/**
 * The audio player of the Note screen (architecture 5.9), over one prepared file at a time. Call it on the main
 * thread ([Parts.main]); `MediaPlayer` is controlled there.
 *
 * - [state] carries the position every 150 ms while playing.
 * - The position is saved every 5 s while playing, and with `final = true` (which also stores the Library preview
 *   excerpt) on pause and on [leave].
 * - [play] requests AUDIOFOCUS_GAIN (USAGE_MEDIA, CONTENT_TYPE_SPEECH); a focus loss and ACTION_AUDIO_BECOMING_NOISY
 *   pause.
 * - [reload] prepares the file again after a document replace and keeps the position.
 * - [playRange] plays a speaker sample, stops at its end and restores the previous position and play state. A pause,
 *   a focus loss or unplugged headphones during a sample restore the previous position and stay paused.
 */
// The player API of architecture 5.9: one function per control of the Note screen, each with guard clauses.
@Suppress("TooManyFunctions", "ReturnCount")
class PlaybackController(private val parts: Parts) {
    /** What the controller is built from; `AppGraph` passes the app's implementations, tests pass fakes. */
    @Suppress("LongParameterList") // one seam per collaborator of architecture 5.9
    class Parts(
        /** Runs the position ticker and the saves; it outlives the screen, so a save on leaving finishes. */
        val scope: CoroutineScope,
        val main: CoroutineDispatcher,
        val player: () -> AudioPlayer,
        val platform: PlaybackPlatform,
        val store: PositionStore,
        /** A monotonic clock in ms (`SystemClock.elapsedRealtime`). */
        val clock: () -> Long,
        val timing: Timing = Timing(),
    )

    data class Timing(val tickMs: Long = 150, val saveEveryMs: Long = 5_000)

    private data class Sample(val endMs: Long, val restoreMs: Long, val wasPlaying: Boolean)

    private val mutableState = MutableStateFlow(PlaybackState())
    private val saves = Mutex()

    private var player: AudioPlayer? = null
    private var noteId: String? = null
    private var file: File? = null
    private var ticker: Job? = null
    private var sample: Sample? = null
    private var lastSaved = 0L

    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    /**
     * Prepares [file] of [noteId] at [positionMs]. The same note and file again does nothing; another note leaves
     * the open one first. False when the file cannot be played.
     */
    @Suppress("TooGenericExceptionCaught") // any failure of the platform player means "cannot play"
    suspend fun open(noteId: String, file: File, positionMs: Long): Boolean {
        if (noteId == this.noteId && file == this.file && player != null) return true
        if (player != null) leave()
        val created = parts.player()
        try {
            created.prepare(file)
        } catch (error: CancellationException) {
            created.release()
            throw error
        } catch (_: Exception) {
            created.release()
            this.noteId = noteId
            mutableState.value = PlaybackState(noteId = noteId, failed = true)
            return false
        }
        val start = positionMs.coerceIn(0, created.durationMs)
        created.seekTo(start)
        created.onCompletion(::onCompletion)
        player = created
        this.noteId = noteId
        this.file = file
        mutableState.value = PlaybackState(noteId = noteId, durationMs = created.durationMs, positionMs = start)
        return true
    }

    /** The note's audio file was replaced (a new document): prepare [file] again at the same position and state. */
    @Suppress("TooGenericExceptionCaught") // any failure of the platform player means "cannot play"
    suspend fun reload(file: File): Boolean {
        val old = player ?: return false
        val id = noteId ?: return false
        val current = sample
        val positionMs = current?.restoreMs ?: old.positionMs
        val resume = current?.wasPlaying ?: old.playing
        stopTicker()
        sample = null
        old.release()
        player = null
        val created = parts.player()
        try {
            created.prepare(file)
        } catch (error: CancellationException) {
            created.release()
            throw error
        } catch (_: Exception) {
            created.release()
            if (noteId != id) return leftDuringReload(id, positionMs)
            release()
            mutableState.value = PlaybackState(noteId = id, failed = true)
            return false
        }
        if (noteId != id) {
            created.release()
            return leftDuringReload(id, positionMs)
        }
        created.seekTo(positionMs.coerceIn(0, created.durationMs))
        created.onCompletion(::onCompletion)
        player = created
        this.file = file
        if (resume) {
            created.play()
            startTicker()
        } else {
            release()
        }
        publish()
        return true
    }

    fun play() {
        val active = player ?: return
        if (active.playing) return
        if (!parts.platform.requestFocus(::pause)) return
        parts.platform.watchNoisy(::pause)
        if (sample == null && active.positionMs >= active.durationMs) active.seekTo(0)
        active.play()
        lastSaved = parts.clock()
        startTicker()
        publish()
    }

    /**
     * Pauses and saves the position (final). It is also the focus-loss and noisy callback. During a speaker sample,
     * the sample ends, the previous position returns and playback stays paused.
     */
    fun pause() {
        val active = player ?: return
        if (sample != null) {
            endSample(interrupted = true)
            return
        }
        if (active.playing) active.pause()
        stopTicker()
        release()
        publish()
        save(active.positionMs, final = true)
    }

    fun toggle() = if (player?.playing == true) pause() else play()

    /** Seeks to [positionMs]; during a sample, the sample is dropped and the previous position is not restored. */
    fun seekTo(positionMs: Long) {
        val active = player ?: return
        sample = null
        active.seekTo(positionMs.coerceIn(0, active.durationMs))
        publish()
    }

    /** −10 s / +10 s. */
    fun skip(deltaMs: Long) {
        val active = player ?: return
        seekTo(active.positionMs + deltaMs)
    }

    /**
     * Plays [startMs]..[endMs] of the open note (a speaker sample), then pauses and returns to the position and play
     * state from before. False when [noteId] is not the open note.
     */
    fun playRange(noteId: String, startMs: Long, endMs: Long): Boolean {
        val active = player ?: return false
        if (noteId != this.noteId || endMs <= startMs) return false
        val previous = sample
        sample = Sample(
            endMs = endMs.coerceAtMost(active.durationMs),
            restoreMs = previous?.restoreMs ?: active.positionMs,
            wasPlaying = previous?.wasPlaying ?: active.playing,
        )
        active.seekTo(startMs.coerceIn(0, active.durationMs))
        if (active.playing) publish() else play()
        return true
    }

    /** The Note screen is left: pause, save the position (final, which refreshes the Library preview), release. */
    suspend fun leave() {
        val active = player
        val id = noteId
        if (active == null || id == null) {
            reset()
            return
        }
        val positionMs = sample?.restoreMs ?: active.positionMs
        if (active.playing) active.pause()
        stopTicker()
        release()
        active.release()
        reset()
        // On the controller's scope: leaving the screen cancels the caller, not the save.
        parts.scope.launch(parts.main) { saveNow(id, positionMs, final = true) }.join()
    }

    // Helpers. ----------------------------------------------------------------------------------------------------

    /**
     * The note [id] was left (or another note opened) while [reload] prepared its file. That leave found no player,
     * so it kept the focus and saved no position: do both here. The note keeps no player.
     */
    private fun leftDuringReload(id: String, positionMs: Long): Boolean {
        if (player == null) release()
        parts.scope.launch(parts.main) { saveNow(id, positionMs, final = true) }
        return false
    }

    private fun onCompletion() {
        val active = player ?: return
        if (sample != null) {
            endSample(interrupted = false)
            return
        }
        stopTicker()
        release()
        publish()
        save(active.durationMs, final = true)
    }

    /**
     * Ends the sample at the position from before. At the sample's end ([interrupted] false), playback goes on if it
     * played before. A pause, a focus loss or unplugged headphones ([interrupted] true) keep it paused.
     */
    private fun endSample(interrupted: Boolean) {
        val active = player ?: return
        val ended = sample ?: return
        sample = null
        if (active.playing) active.pause()
        active.seekTo(ended.restoreMs)
        stopTicker()
        release()
        if (!interrupted && ended.wasPlaying) {
            play()
            return
        }
        publish()
        if (interrupted) save(ended.restoreMs, final = true)
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = parts.scope.launch(parts.main) {
            while (isActive) {
                delay(parts.timing.tickMs)
                tick()
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun tick() {
        val active = player ?: return
        val positionMs = active.positionMs
        val current = sample
        if (current != null && positionMs >= current.endMs) {
            endSample(interrupted = false)
            return
        }
        publish()
        val now = parts.clock()
        if (current == null && active.playing && now - lastSaved >= parts.timing.saveEveryMs) {
            lastSaved = now
            save(positionMs, final = false)
        }
    }

    private fun publish() {
        val active = player ?: return
        mutableState.value = PlaybackState(
            noteId = noteId,
            durationMs = active.durationMs,
            positionMs = active.positionMs,
            playing = active.playing,
            sample = sample != null,
        )
    }

    /** Gives back focus and stops watching for unplugged headphones. */
    private fun release() {
        parts.platform.unwatchNoisy()
        parts.platform.abandonFocus()
    }

    private fun reset() {
        player = null
        noteId = null
        file = null
        sample = null
        mutableState.value = PlaybackState()
    }

    private fun save(positionMs: Long, final: Boolean) {
        val id = noteId ?: return
        parts.scope.launch(parts.main) { saveNow(id, positionMs, final) }
    }

    /** In call order: a 5 s save never lands after the final one. A failed save is dropped; the next one counts. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun saveNow(noteId: String, positionMs: Long, final: Boolean) = saves.withLock {
        try {
            parts.store.save(noteId, positionMs, final)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The position is a convenience; the next pause or leave saves it again.
        }
    }
}
