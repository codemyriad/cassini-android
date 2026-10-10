package org.cassini.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.cassini.android.nav.CassiniApp
import org.cassini.android.nav.DeepLink
import org.cassini.android.nav.DeepLinks
import org.cassini.android.processing.ProcessingNotifications
import org.cassini.ui.theme.CassiniTheme

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
        if (intent.getBooleanExtra(DeepLinks.EXTRA_HANDLED, false)) return
        val link = DeepLinks.parse(
            action = intent.action,
            data = intent.dataString,
            open = intent.getStringExtra(ProcessingNotifications.EXTRA_OPEN),
            noteId = intent.getStringExtra(ProcessingNotifications.EXTRA_NOTE_ID),
            resume = intent.getBooleanExtra(ProcessingNotifications.EXTRA_RESUME, false),
        ) ?: return
        intent.putExtra(DeepLinks.EXTRA_HANDLED, true)
        links.trySend(link)
    }
}
