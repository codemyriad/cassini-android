package org.cassini.android.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Note screen's player as the MediaSession sees it (coordination D53): the lock screen, the media notification,
 * headset buttons, Bluetooth and Android Auto act on it. It owns no audio. It mirrors [PlaybackController.state] and
 * hands every command to the [controller], so the controller's rules (audio focus, saved positions, speaker samples)
 * hold whatever sent the command: play and pause, seek, −10 s/+10 s, and the speed. Main thread only.
 *
 * [title] gives a note's title for the notification; it is read once per opened note, apart from the state: the
 * commands are offered as soon as a note is open, before its title is read, so a controller that connects and plays
 * at once (a headset, Bluetooth, the lock screen right after the service starts) is never refused.
 */
@OptIn(UnstableApi::class) // SimpleBasePlayer
class SessionPlayer(
    private val controller: PlaybackController,
    scope: CoroutineScope,
    private val title: suspend (String) -> String?,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var shown = PlaybackState()
    private var titled: Pair<String, String?>? = null

    init {
        scope.launch {
            controller.state.collect { state ->
                shown = state
                invalidateState()
            }
        }
        scope.launch {
            controller.state.map { it.noteId }.distinctUntilChanged().collectLatest { id ->
                if (id != null && titled?.first != id) {
                    titled = id to title(id)
                    invalidateState()
                }
            }
        }
    }

    override fun getState(): State {
        val state = shown
        val id = state.noteId
        val builder = State.Builder()
            .setAvailableCommands(if (id == null) Player.Commands.EMPTY else COMMANDS)
            .setSeekBackIncrementMs(SKIP_MS)
            .setSeekForwardIncrementMs(SKIP_MS)
            .setPlaybackParameters(PlaybackParameters(state.speed))
        if (id == null) return builder.setPlaybackState(Player.STATE_IDLE).build()
        val metadata = MediaMetadata.Builder()
            .setTitle(titled?.takeIf { it.first == id }?.second)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        val item = MediaItemData.Builder(id)
            .setMediaItem(MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setDurationUs(state.durationMs * US_PER_MS)
            .setIsSeekable(true)
            .build()
        val playing = state.playing && !state.failed
        return builder
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(if (state.failed) Player.STATE_IDLE else Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(
                if (playing) {
                    PositionSupplier.getExtrapolating(state.positionMs, state.speed)
                } else {
                    PositionSupplier.getConstant(state.positionMs)
                },
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.play() else controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_BACK -> controller.skip(-SKIP_MS)
            Player.COMMAND_SEEK_FORWARD -> controller.skip(SKIP_MS)
            else -> controller.seekTo(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        controller.setSpeed(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    /** Stop from the notification or a headset: pause, which saves the position; the note stays open. */
    override fun handleStop(): ListenableFuture<*> {
        controller.pause()
        return Futures.immediateVoidFuture()
    }

    companion object {
        const val SKIP_MS = 10_000L
        private const val US_PER_MS = 1_000L

        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
            )
            .build()
    }
}
