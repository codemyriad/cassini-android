package org.cassini.android.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
 */
@OptIn(UnstableApi::class) // DefaultMediaNotificationProvider.setSmallIcon
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        val graph = (application as CassiniApplication).graph
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(UiR.drawable.ui_ic_stat_logo) },
        )
        val created = MediaSession.Builder(this, graph.sessionPlayer).build()
        session = created
        // The notification and the lock screen open the note that plays.
        scope.launch {
            graph.playback.state.map { it.noteId }.distinctUntilChanged().collect { id ->
                created.setSessionActivity(openNote(id))
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away while paused ends the service; while it plays, playback and the notification go on. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (session?.player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        // The player belongs to the app's graph: only the session goes.
        session?.release()
        session = null
        super.onDestroy()
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
