package io.codemyriad.cassini.background

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.codemyriad.cassini.MainActivity
import io.codemyriad.cassini.playback.PlaybackService
import io.codemyriad.cassini.playback.PlaybackState
import io.codemyriad.cassini.test.CiSmoke
import io.codemyriad.cassini.testing.NotificationWatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
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
    @get:Rule val notifications = NotificationWatch()

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
        val disconnected = CompletableDeferred<Unit>()
        val connected = connect(disconnected)
        connected.awaitEvent(Player.EVENT_AVAILABLE_COMMANDS_CHANGED, "The session offers play for the open note") {
            isCommandAvailable(Player.COMMAND_PLAY_PAUSE)
        }

        // The user presses play with the app on screen, then leaves the app: playback goes on in the background.
        val shown = ActivityScenario.launch(MainActivity::class.java).also { screen = it }
        lateinit var lifecycle: Lifecycle
        shown.onActivity { lifecycle = it.lifecycle }
        main { playback.play() }
        connected.awaitEvent(Player.EVENT_IS_PLAYING_CHANGED, "The session shows the note playing") { isPlaying }
        Background.shell("input keyevent KEYCODE_HOME")
        await("The app goes to the background") { lifecycle.currentStateFlow.first { it == Lifecycle.State.CREATED } }
        awaitPlayback("The note still plays in the background") { it.playing }

        // Media3 posts the media notification of the session while it plays.
        val manager = Background.context.getSystemService(NotificationManager::class.java)
        notifications.await("The media notification is posted", NOTIFICATION_MS) {
            manager.activeNotifications.firstOrNull { posted ->
                posted.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
            }
        }
        // The title follows the state once it has been read from the database.
        connected.awaitEvent(Player.EVENT_MEDIA_METADATA_CHANGED, "The session names the note") {
            mediaMetadata.title?.toString() == "fleurs-it-short"
        }

        main { connected.pause() }
        awaitPlayback("Pause from the session pauses the player") { !it.playing }

        main { connected.seekTo(SEEK_MS) }
        awaitPlayback("A seek from the session moves the player") {
            it.positionMs in SEEK_MS - TOLERANCE_MS..SEEK_MS + TOLERANCE_MS
        }
        main { connected.seekBack() }
        awaitPlayback("−10 s from the session moves the player back") {
            it.positionMs <= SEEK_MS - SKIP_MS + TOLERANCE_MS
        }

        main { connected.setPlaybackSpeed(SPEED) }
        awaitPlayback("A speed from the session reaches the player") { it.speed == SPEED }
        connected.awaitEvent(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED, "The session shows the speed") {
            playbackParameters.speed == SPEED
        }

        // Resuming from the background, as the lock screen or a headset does: the system delivers the play.
        dispatchMediaKey("play")
        awaitPlayback("The media key resumes the note in the background") { it.playing }
        connected.awaitEvent(Player.EVENT_IS_PLAYING_CHANGED, "The session shows it playing") { isPlaying }

        // A headset's pause button: the media key goes to the playing session.
        dispatchMediaKey("pause")
        awaitPlayback("The media key pauses the player") { !it.playing }

        // Leaving the note ends the session even though this controller is still bound: Media3 removes the
        // notification and the released session disconnects the controller, so nothing keeps the service alive.
        withContext(Dispatchers.Main) { playback.leave() }
        await("The session disconnects its controllers") { disconnected.await() }
        notifications.await("The media notification is gone", NOTIFICATION_MS) {
            val media = manager.activeNotifications.filter { posted ->
                posted.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
            }
            Unit.takeIf { media.isEmpty() }
        }
        Unit
    }

    private fun dispatchMediaKey(key: String) {
        // HOME can finish the activity before InputDispatcher has a focused launcher window. Window input
        // can then drop ACTION_DOWN and even ANR the launcher (observed on API 26 and 35). Deliver the
        // headset command through the media service, independent of window focus. API 28–32's legacy
        // media shell supplies an empty controller package that Media3 rejects; retain input only there.
        val command = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> "cmd media_session dispatch $key"
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> "media dispatch $key"
            else -> "input keyevent KEYCODE_MEDIA_${key.uppercase(java.util.Locale.ROOT)}"
        }
        val output = Background.shell(command)
        assertTrue("System media dispatch failed: $output", output.isBlank())
    }

    private fun connect(disconnected: CompletableDeferred<Unit>): MediaController {
        val token = SessionToken(Background.context, ComponentName(Background.context, PlaybackService::class.java))
        return MediaController.Builder(Background.context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    disconnected.complete(Unit)
                }
            })
            .buildAsync().get(CONNECT_S, TimeUnit.SECONDS)
            .also { controller = it }
    }

    private suspend fun awaitPlayback(message: String, matches: (PlaybackState) -> Boolean) {
        await(message) { playback.state.first(matches) }
    }

    private suspend fun MediaController.awaitEvent(event: Int, message: String, matches: Player.() -> Boolean) {
        withContext(Dispatchers.Main) {
            val observed = CompletableDeferred<Unit>()
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.contains(event) && player.matches()) observed.complete(Unit)
                }
            }
            // Register and read on the controller's looper. The read also accepts an event that already arrived.
            addListener(listener)
            try {
                if (!matches()) await(message) { observed.await() }
            } finally {
                removeListener(listener)
            }
        }
    }

    private suspend fun <T : Any> await(message: String, event: suspend () -> T): T =
        withTimeoutOrNull(EVENT_MS) { event() } ?: throw AssertionError(message)

    private companion object {
        const val EVENT_MS = 20_000L
        const val NOTIFICATION_MS = 20_000L
        const val CONNECT_S = 20L
        const val SEEK_MS = 12_000L
        const val SKIP_MS = 10_000L
        const val TOLERANCE_MS = 400L
        const val SPEED = 1.5f
    }
}
