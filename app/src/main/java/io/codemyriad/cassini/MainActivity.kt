package io.codemyriad.cassini

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.ReportDrawnAfter
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import io.codemyriad.cassini.nav.CassiniApp
import io.codemyriad.cassini.nav.DeepLink
import io.codemyriad.cassini.nav.DeepLinks
import io.codemyriad.cassini.nav.ImportGrantService
import io.codemyriad.cassini.processing.ProcessingNotifications
import io.codemyriad.cassini.ui.theme.CassiniTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The app's only activity (docs/architecture.md section 6). It is an AppCompatActivity only for
 * `AppCompatDelegate.setApplicationLocales`; the UI is Compose. Deep links (notifications and ACTION_VIEW) arrive in
 * [onCreate] without saved state and in [onNewIntent] (the activity is `singleTop`); a handled intent is marked, so a
 * recreated activity does not open it again.
 */
class MainActivity : AppCompatActivity() {
    private val links = Channel<DeepLink>(Channel.BUFFERED)

    /** One flow for the activity's life: a new one would restart the collector in [CassiniApp]. */
    private val linkFlow = links.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as CassiniApplication).graph
        if (savedInstanceState == null) deliver(intent)
        setContent {
            ReportDrawnAfter { graph.ready.join() }
            CassiniTheme {
                CassiniApp(graph, linkFlow)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deliver(intent)
    }

    private fun deliver(intent: Intent) {
        // Recents starts the task again with its first intent: an import or a notification must not run twice.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (fromHistory || intent.getBooleanExtra(DeepLinks.EXTRA_HANDLED, false)) return
        val link = DeepLinks.parse(
            action = intent.action,
            data = intent.dataString,
            open = intent.getStringExtra(ProcessingNotifications.EXTRA_OPEN),
            noteId = intent.getStringExtra(ProcessingNotifications.EXTRA_NOTE_ID),
            resume = intent.getBooleanExtra(ProcessingNotifications.EXTRA_RESUME, false),
            stream = if (intent.action == Intent.ACTION_SEND) sharedStream(intent) else null,
        ) ?: return
        intent.putExtra(DeepLinks.EXTRA_HANDLED, true)
        // A file from another app: its read grant ends with this activity, so a service holds it for the import.
        if (link is DeepLink.Import) ImportGrantService.start(this, link.uri) else links.trySend(link)
    }

    /** The audio file of an ACTION_SEND from another app's share sheet. */
    private fun sharedStream(intent: Intent): String? =
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.toString()
}
