package org.cassini.android.playback

import android.os.Looper
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The MediaSession's view of the Note screen's player (D53): it mirrors the controller and sends the lock screen's,
 * the notification's and the headset's commands to the controller.
 */
@RunWith(RobolectricTestRunner::class)
class SessionPlayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun stop() = scope.cancel()

    private class Player1 : AudioPlayer {
        override var positionMs = 0L
        override val durationMs = 60_000L
        override var playing = false
        var applied = 1f
        override suspend fun prepare(file: File) = Unit
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun seekTo(positionMs: Long) { this.positionMs = positionMs }
        override fun setSpeed(speed: Float) { applied = speed }
        override fun onCompletion(listener: () -> Unit) = Unit
        override fun release() = Unit
    }

    private val focus = object : PlaybackPlatform {
        override fun requestFocus(onLoss: () -> Unit) = true
        override fun abandonFocus() = Unit
        override fun watchNoisy(onNoisy: () -> Unit) = Unit
        override fun unwatchNoisy() = Unit
    }

    private fun TestScope.controller(audio: Player1) = PlaybackController(
        PlaybackController.Parts(
            scope = scope,
            main = UnconfinedTestDispatcher(testScheduler),
            player = { audio },
            platform = focus,
            store = { _, _, _ -> },
            clock = { 0L },
        ),
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun itMirrorsTheOpenNoteAndSendsCommandsToTheController() = runTest {
        val audio = Player1()
        val controller = controller(audio)
        val session = SessionPlayer(controller, scope) { id -> "Title of $id" }
        idle()
        assertEquals("no note: nothing to control", Player.STATE_IDLE, session.playbackState)
        assertFalse(session.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))

        assertTrue(controller.open("n1", File("a.opus"), positionMs = 5_000))
        idle()
        assertEquals(Player.STATE_READY, session.playbackState)
        assertEquals("Title of n1", session.mediaMetadata.title.toString())
        assertEquals(60_000L, session.duration)
        assertEquals(5_000L, session.currentPosition)

        session.play()
        idle()
        assertTrue("play from the lock screen plays through the controller", controller.state.value.playing)
        assertTrue(session.playWhenReady)

        session.seekForward()
        idle()
        assertEquals("+10 s", 15_000L, audio.positionMs)
        session.seekTo(30_000)
        idle()
        assertEquals(30_000L, audio.positionMs)
        session.playbackParameters = PlaybackParameters(1.5f)
        idle()
        assertEquals(1.5f, audio.applied)
        assertEquals(1.5f, session.playbackParameters.speed)

        session.pause()
        idle()
        assertFalse(controller.state.value.playing)
    }

    @Test fun theCommandsComeWithTheOpenNoteBeforeItsTitle() = runTest {
        val audio = Player1()
        val controller = controller(audio)
        val title = CompletableDeferred<String>()
        val session = SessionPlayer(controller, scope) { title.await() }
        assertTrue(controller.open("n1", File("a.opus"), positionMs = 0))
        idle()
        // A controller that connects now and plays at once (Media3 drops a play the player does not offer).
        val offered = session.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)
        assertTrue("play is offered while the title is still read", offered)
        session.play()
        idle()
        assertTrue(controller.state.value.playing)

        title.complete("Title of n1")
        idle()
        assertEquals("Title of n1", session.mediaMetadata.title.toString())
        // Paused, so the position ticker stops and runTest can end.
        session.pause()
        idle()
    }
}
