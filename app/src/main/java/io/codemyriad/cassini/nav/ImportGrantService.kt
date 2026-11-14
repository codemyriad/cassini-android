package io.codemyriad.cassini.nav

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import io.codemyriad.cassini.CassiniApplication
import io.codemyriad.cassini.core.Failure

/**
 * Holds the read grant of a file that another app opened with Cassini or shared to it until the file's import ends
 * (review of #45). The grant of an ACTION_VIEW or ACTION_SEND belongs to the activity that received it and ends with
 * it, while the import runs later on the app's scope: back to the sending app before the import opened the file, and
 * a valid share failed with "Can't open this file". [start] passes the grant on to this service, which runs the
 * import ([ViewedImports.start], so the note opens as before) and stops when the last import it holds a grant for has
 * ended. Not exported: only [start] reaches it.
 */
class ImportGrantService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var running = 0
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val uri = intent?.dataString
        if (uri == null) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        running++
        val graph = (application as CassiniApplication).graph
        graph.viewedImports.start(uri, intent.type).invokeOnCompletion {
            main.post {
                running--
                stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    /** Stops once nothing imports; a start that the system has not delivered yet keeps the service. */
    private fun stopIfIdle() {
        if (running == 0) stopSelf(lastStartId)
    }

    companion object {
        private const val TAG = "CassiniImport"

        /** How the service is started; tests make it throw as Android does. */
        @VisibleForTesting
        internal var starter: (Context, Intent) -> Unit = { context, intent -> context.startService(intent) }

        /**
         * Imports [uri], which [context] (the activity that received it) can read now, with the grant passed on to
         * the service; [mimeType] is the type of the intent that brought it.
         * - The caller sent no read grant (a uri Cassini may not read for it): Android refuses to pass one on with a
         *   SecurityException. Nothing is imported, and the Library shows "Can't open this file", as for a file that
         *   cannot be read.
         * - The activity is in the foreground, so the start is allowed; if Android refuses it anyway, the import runs
         *   without the service and may lose the grant.
         */
        fun start(context: Context, uri: String, mimeType: String? = null) {
            val intent = Intent(context, ImportGrantService::class.java)
                .setDataAndType(uri.toUri(), mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val graph = (context.applicationContext as CassiniApplication).graph
            try {
                starter(context, intent)
            } catch (denied: SecurityException) {
                Log.w(TAG, "No read grant came with a file from another app", denied)
                graph.notices.imports.refuse(Failure.OPEN)
            } catch (refused: IllegalStateException) {
                Log.w(TAG, "The import service could not start; importing without it", refused)
                graph.viewedImports.start(uri, mimeType)
            }
        }
    }
}
