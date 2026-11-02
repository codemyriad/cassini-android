package org.cassini.android.background

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.cassini.android.MainActivity
import org.cassini.android.background.Background.waitFor
import org.cassini.android.playback.PlaybackService
import org.cassini.test.CiSmoke
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The MediaSession of the Note screen's player (coordination D53) on a device: what the lock screen, the media
 * notification, Bluetooth and Android Auto see and send. A Media3 [MediaController] binds to [PlaybackService] as
 * those controllers do; a media key goes the way a headset button goes. Every command must reach the app's
 * `PlaybackController`, and the session must mirror it.
 *
 * The test follows the user: play starts while the app is on screen, the app goes to the background, and the rest
 * comes from the session. Resuming from the background goes through the system, as a media key, as it does from the
 * lock screen or a headset: Android 15 gives audio focus to a background app only for a play the system delivers,
 * never for one sent from inside the app's own process.
 */
@CiSmoke
@RunWith(AndroidJUnit4::class)
class PlaybackSessionTest {
    private val graph get() = Background.graph
    private val playback get() = graph.playback
    private var controller: MediaController? = null
    private var screen: ActivityScenario<MainActivity>? = null

    @Before fun grant() = Background.grant()

    @After fun leave() = runBlocking {
        withContext(Dispatchers.Main) {
            controller?.release()
            playback.setSpeed(1f)
            playback.leave()
        }
        Background.context.stopService(Intent(Background.context, PlaybackService::class.java))
        screen?.close()
        Unit
    }

    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    @Test fun theSessionMirrorsThePlayerAndItsCommandsReachIt() = runBlocking {
        val clip = Background.assetCopy("fleurs-it-short.wav")
        val noteId = Background.import(clip)
        // Not runBlocking on the main thread: the prepare ends with a callback on the main looper.
        val opened = withContext(Dispatchers.Main) { playback.open(noteId, clip, positionMs = 0) }
        assertTrue("The clip opens in the player", opened)
        // A controller binds to the session service, as the system's media controls do.
        val token = SessionToken(Background.context, ComponentName(Background.context, PlaybackService::class.java))
        val connected = MediaController.Builder(Background.context, token).buildAsync().get(CONNECT_S, TimeUnit.SECONDS)
        controller = connected
        waitFor("The session offers play for the open note") {
            main { connected.takeIf { it.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) } }
        }

        // The user presses play with the app on screen, then leaves the app: playback goes on in the background.
        val shown = ActivityScenario.launch(MainActivity::class.java).also { screen = it }
        main { playback.play() }
        waitFor("The session shows the note playing") { main { connected.takeIf { it.isPlaying } } }
        Background.shell("input keyevent KEYCODE_HOME")
        waitFor("The app goes to the background") { shown.state.takeIf { it == Lifecycle.State.CREATED } }
        waitFor("The note still plays in the background") { playback.state.value.takeIf { it.playing } }

        // Media3 posts the media notification of the session while it plays.
        val manager = Background.context.getSystemService(NotificationManager::class.java)
        waitFor("The media notification is posted", NOTIFICATION_MS) {
            manager.activeNotifications.firstOrNull { posted ->
                posted.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
            }
        }
        // The title follows the state once it has been read from the database.
        waitFor("The session names the note") {
            main { connected.mediaMetadata.title?.toString()?.takeIf { it == "fleurs-it-short" } }
        }

        main { connected.pause() }
        waitFor("Pause from the session pauses the player") { playback.state.value.takeUnless { it.playing } }

        main { connected.seekTo(SEEK_MS) }
        waitFor("A seek from the session moves the player") {
            playback.state.value.takeIf { it.positionMs in SEEK_MS - TOLERANCE_MS..SEEK_MS + TOLERANCE_MS }
        }
        main { connected.seekBack() }
        waitFor("−10 s from the session moves the player back") {
            playback.state.value.takeIf { it.positionMs <= SEEK_MS - SKIP_MS + TOLERANCE_MS }
        }

        main { connected.setPlaybackSpeed(SPEED) }
        waitFor("A speed from the session reaches the player") { playback.state.value.takeIf { it.speed == SPEED } }
        waitFor("The session shows the speed") { main { connected.takeIf { it.playbackParameters.speed == SPEED } } }

        // Resuming from the background, as the lock screen or a headset does: the system delivers the play.
        Background.shell("input keyevent KEYCODE_MEDIA_PLAY")
        waitFor("The media key resumes the note in the background") { playback.state.value.takeIf { it.playing } }
        waitFor("The session shows it playing") { main { connected.takeIf { it.isPlaying } } }

        // A headset's pause button: the media key goes to the playing session.
        Background.shell("input keyevent KEYCODE_MEDIA_PAUSE")
        waitFor("The media key pauses the player") { playback.state.value.takeUnless { it.playing } }

        // Leaving the note ends the session even though this controller is still bound: Media3 removes the
        // notification and the released session disconnects the controller, so nothing keeps the service alive.
        withContext(Dispatchers.Main) { playback.leave() }
        waitFor("The session disconnects its controllers") { main { connected.takeUnless { it.isConnected } } }
        waitFor("The media notification is gone", NOTIFICATION_MS) {
            val media = manager.activeNotifications.filter { posted ->
                posted.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
            }
            Unit.takeIf { media.isEmpty() }
        }
        Unit
    }

    private companion object {
        const val NOTIFICATION_MS = 20_000L
        const val CONNECT_S = 20L
        const val SEEK_MS = 12_000L
        const val SKIP_MS = 10_000L
        const val TOLERANCE_MS = 400L
        const val SPEED = 1.5f
    }
}
