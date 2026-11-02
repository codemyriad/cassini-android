package org.cassini.android.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.cassini.android.CassiniApplication
import org.cassini.android.processing.ProcessingNotifications
import org.cassini.ui.R as UiR

/**
 * The MediaSession of the Note screen's player (coordination D53): Media3 shows the media notification and the lock
 * screen controls from it, and runs this service in the foreground (`mediaPlayback`) while it plays, so playback goes
 * on when the app is in the background. The player is the app's [SessionPlayer]; the session lives as long as this
 * service. The notification opens the note.
 *
 * Only trusted controllers connect ([TrustedControllers]): the session shows the note's title and takes commands.
 * The session ends ([shutDown]: notification removed, controllers disconnected, service stopped) when no note is
 * open, when the note's player failed, and, once the app's task is gone, when playback pauses ([ends]).
 */
@OptIn(UnstableApi::class) // DefaultMediaNotificationProvider.setSmallIcon, ControllerInfo.isTrusted
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = MainScope()
    private val graph get() = (application as CassiniApplication).graph

    /** The app's task was swiped away: nothing brings the service down but the end of playback. */
    private var taskRemoved = false

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(UiR.drawable.ui_ic_stat_logo) },
        )
        open()
        // The notification and the lock screen open the note that plays.
        scope.launch {
            graph.playback.state.map { it.noteId }.distinctUntilChanged().collect { id ->
                session?.setSessionActivity(openNote(id))
            }
        }
        scope.launch {
            graph.playback.state.collect { state -> if (ends(state, taskRemoved)) shutDown() }
        }
    }

    /**
     * The app starts the service when playback starts (the user is in the app): after a [shutDown] the service may
     * still exist, so the session comes back here.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        taskRemoved = false
        if (!ends(graph.playback.state.value, taskRemoved)) open()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Swiping the app away while paused ends the service. While it plays, playback and the notification go on, and
     * the service ends when playback pauses (the state collector of [onCreate]).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        taskRemoved = true
        if (ends(graph.playback.state.value, taskRemoved)) shutDown()
    }

    override fun onDestroy() {
        scope.cancel()
        // The player belongs to the app's graph: only the session goes.
        session?.release()
        session = null
        super.onDestroy()
    }

    /** Creates the session and adds it, so Media3 posts its notification, unless there is one. */
    private fun open() {
        if (session != null) return
        val created = MediaSession.Builder(this, graph.sessionPlayer).setCallback(TrustedControllers).build()
        // Added at once, not at the first controller's onGetSession: Media3 posts the media notification only for
        // the sessions of the service, and the user's play comes before any controller connects.
        addSession(created)
        created.setSessionActivity(openNote(graph.playback.state.value.noteId))
        session = created
    }

    /**
     * The end of the session: Media3 takes down the notification (and the foreground state) of a removed session,
     * and releasing it disconnects every controller, so no bound controller (a watch, a car) keeps the service and
     * its notification alive. Then the service stops. Called only when nothing plays ([ends]).
     */
    private fun shutDown() {
        session?.let { current ->
            removeSession(current)
            current.release()
        }
        session = null
        stopSelf()
    }

    private fun openNote(noteId: String?): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent().setPackage(packageName)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (noteId != null) {
            launch.putExtra(ProcessingNotifications.EXTRA_OPEN, ProcessingNotifications.OPEN_NOTE)
                .putExtra(ProcessingNotifications.EXTRA_NOTE_ID, noteId)
        }
        return PendingIntent.getActivity(
            this,
            OPEN_REQUEST,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val OPEN_REQUEST = 4

        /**
         * Whether the session has nothing left to do: no note is open (the Note screen was left), the note's player
         * failed and was released, or the app's task is gone ([taskRemoved]) and playback paused.
         */
        fun ends(state: PlaybackState, taskRemoved: Boolean): Boolean =
            state.noteId == null || state.failed || (taskRemoved && !state.playing)

        /**
         * Starts the service when playback starts, so the session and its controls appear. The user started it in the
         * app, which is in the foreground then; a start that Android refuses only logs (no controls, playback works).
         * Starting a running service again does nothing.
         */
        fun start(context: Context) {
            try {
                context.startService(Intent(context, PlaybackService::class.java))
            } catch (refused: IllegalStateException) {
                Log.w("CassiniPlayback", "The playback session service could not start", refused)
            }
        }
    }
}

/**
 * The controllers the session accepts: the app itself, the system (the lock screen and media keys come as the system
 * or as System UI), Bluetooth, Android Auto and Automotive, Media3's own notification controller, and any controller
 * Android trusts (`MEDIA_CONTENT_CONTROL` or an enabled notification listener, `ControllerInfo.isTrusted`). Trust is
 * always positive: a controller that cannot be identified (a platform controller before API 28 has no package name)
 * is refused like any other app, which would read the note's title and drive playback.
 */
@OptIn(UnstableApi::class) // ControllerInfo.isTrusted
internal object TrustedControllers : MediaSession.Callback {
    private val SYSTEM_PACKAGES = setOf("com.android.systemui", "com.android.bluetooth")

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
        if (trusted(session, controller)) {
            super.onConnect(session, controller)
        } else {
            MediaSession.ConnectionResult.reject()
        }

    fun trusted(session: MediaSession, controller: MediaSession.ControllerInfo): Boolean =
        controller.uid == Process.myUid() ||
            controller.uid == Process.SYSTEM_UID ||
            controller.packageName in SYSTEM_PACKAGES ||
            controller.isTrusted ||
            session.isMediaNotificationController(controller) ||
            session.isAutomotiveController(controller) ||
            session.isAutoCompanionController(controller)
}
