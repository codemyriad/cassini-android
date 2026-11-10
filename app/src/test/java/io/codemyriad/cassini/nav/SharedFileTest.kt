package io.codemyriad.cassini.nav

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.BuildConfig
import io.codemyriad.cassini.CassiniApplication
import io.codemyriad.cassini.GraphFactory
import io.codemyriad.cassini.MainActivity
import io.codemyriad.cassini.TestFilesProvider
import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.models.ModelFetcher
import io.codemyriad.cassini.data.notes.ImportOutcome
import io.codemyriad.cassini.recording.FakeCaptureSource
import io.codemyriad.cassini.recording.FakeConsent
import io.codemyriad.cassini.recording.FakePlatform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Review of #45, through [MainActivity]: what another app may hand over ("Open with" and the share sheet), and how
 * long Cassini can read it. Only another app's `content://` uri starts an import; a path, Cassini's own provider or a
 * traversal starts nothing. A shared file's read grant belongs to the activity and ends with it, so the activity
 * passes it to [ImportGrantService], which holds it until the import ends.
 *
 * [GrantedProvider] enforces that rule the way Android does: it answers only while the receiving activity lives or
 * the service that was handed the grant runs. The control test shows that it refuses an import that runs after the
 * activity finished without the service.
 */
@RunWith(RobolectricTestRunner::class)
class SharedFileTest {
    @get:Rule val folder = TemporaryFolder()

    private val app: CassiniApplication = ApplicationProvider.getApplicationContext()
    private val fixture = File(checkNotNull(System.getProperty("cassini.fixtures")), "audio/fleurs-it-short.wav")

    @Before fun graph() {
        val paths = AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder())
        CassiniApplication.graphFactory = GraphFactory { context, _ ->
            AppGraph(
                context,
                AppGraph.Config(
                    paths = paths,
                    fetcher = ModelFetcher { _, _, _ -> fail("No model is fetched in this test") },
                    network = { false },
                    recording = AppGraph.RecordingHooks(
                        capture = FakeCaptureSource(),
                        platform = FakePlatform(),
                        consent = FakeConsent(),
                    ),
                ),
            )
        }
        runBlocking { withTimeout(TIMEOUT_MS) { app.graph.ready.await() } }
        GrantedProvider.file = fixture
        GrantedProvider.entered = CountDownLatch(1)
        GrantedProvider.gate = CountDownLatch(0)
        GrantedProvider.held = { true }
        Robolectric.setupContentProvider(GrantedProvider::class.java, AUTHORITY)
    }

    @After fun close() {
        GrantedProvider.gate.countDown()
        app.graph.close { shadowOf(Looper.getMainLooper()).idle() }
        CassiniApplication.graphFactory = null
    }

    private fun view(uri: String) = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "audio/wav")
        .setClass(app, MainActivity::class.java)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun send(uri: String) = Intent(Intent.ACTION_SEND).setType("audio/wav")
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        .setClass(app, MainActivity::class.java)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** The import service intents that the activity started, in order. */
    private fun importStarts(): List<Intent> {
        shadowOf(Looper.getMainLooper()).idle()
        val shadow = shadowOf(app as Application)
        return generateSequence { shadow.nextStartedService }
            .filter { it.component?.className == ImportGrantService::class.java.name }
            .toList()
    }

    @Test fun providerAuthoritiesUseTheApplicationId() {
        val applicationId = BuildConfig.APPLICATION_ID
        assertEquals(applicationId, app.packageName)
        val packageInfo = app.packageManager.getPackageInfo(applicationId, PackageManager.GET_PROVIDERS)
        val providers = packageInfo.providers.orEmpty()
        val fixtures = providers.filter { it.name == TestFilesProvider::class.java.name }
        assertEquals("one deliberately external import fixture", 1, fixtures.size)
        assertEquals("testfiles.$applicationId", fixtures.single().authority)
        val shipping = providers.filterNot { it.name == TestFilesProvider::class.java.name }
        assertTrue("the merged manifest declares shipping providers", shipping.isNotEmpty())
        for (provider in shipping) {
            for (authority in requireNotNull(provider.authority).split(';')) {
                assertTrue("${provider.name}: $authority", authority.startsWith("$applicationId."))
            }
        }
    }

    @Test fun pathsOwnProvidersAndTraversalStartNoImport() {
        val refused = listOf(
            "file:///data/data/io.codemyriad.cassini/databases/cassini.db",
            "file:///proc/self/environ",
            "content://${app.packageName}.share/share/x/a.opus",
            "content://$AUTHORITY/voice/../../data/x",
            "content://$AUTHORITY/voice/%2e%2e/%2e%2e/x",
            "content://$AUTHORITY/voice/a%2F..%2Fb",
        )
        for (uri in refused) {
            for (intent in listOf(view(uri), send(uri))) {
                ActivityScenario.launch<MainActivity>(intent).use {
                    assertEquals("${intent.action} $uri", emptyList<Intent>(), importStarts())
                }
            }
        }
        assertTrue("nothing was imported", app.graph.viewedImports.ready.value.isEmpty())
        assertEquals("the provider was never asked", 1L, GrantedProvider.entered.count)
    }

    @Test fun aSharedFileImportsAfterTheActivityFinishesBecauseTheServiceHoldsTheGrant() {
        val uri = "content://$AUTHORITY/voice/7"
        var activityAlive = true
        var service: ImportGrantService? = null
        GrantedProvider.held = {
            activityAlive || service?.let { !shadowOf(it).isStoppedBySelf } == true
        }
        // Startup is gated: the import has started and waits at the provider while the activity finishes.
        GrantedProvider.gate = CountDownLatch(1)
        val scenario = ActivityScenario.launch<MainActivity>(send(uri))
        val start = importStarts().single()
        assertEquals(uri, start.dataString)
        assertTrue("the grant is passed on", start.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val started = Robolectric.buildService(ImportGrantService::class.java, start).create().startCommand(0, 1).get()
        service = started
        assertTrue("the import reached the provider", GrantedProvider.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))

        // Back to the sending app: the activity and its grant go.
        scenario.close()
        activityAlive = false
        GrantedProvider.gate.countDown()

        val noteId = runBlocking {
            withTimeout(TIMEOUT_MS) { app.graph.viewedImports.ready.first { it.isNotEmpty() }.single() }
        }
        assertNotNull(noteId)
        // The service stops on the main thread once the import has ended.
        val stopped = runBlocking {
            withTimeout(TIMEOUT_MS) {
                while (!shadowOf(started).isStoppedBySelf) {
                    shadowOf(Looper.getMainLooper()).idle()
                    kotlinx.coroutines.yield()
                }
                true
            }
        }
        assertTrue(stopped)
    }

    /** Review of #45: a nested document of the Files app, whose id has an encoded slash, imports by both routes. */
    @Test fun aNestedDocumentUriImportsByOpenWithAndByShare() {
        val nested = "content://$AUTHORITY/document/primary%3AMusic%2FInterviews%2Fvoice.wav"
        for (intent in listOf(view(nested), send(nested))) {
            ActivityScenario.launch<MainActivity>(intent).use {
                assertEquals(intent.action, listOf(nested), importStarts().map { it.dataString })
            }
        }
        val start = Intent(app, ImportGrantService::class.java).setData(Uri.parse(nested))
        Robolectric.buildService(ImportGrantService::class.java, start).create().startCommand(0, 1)
        val noteId = runBlocking {
            withTimeout(TIMEOUT_MS) { app.graph.viewedImports.ready.first { it.isNotEmpty() }.single() }
        }
        assertTrue(noteId.isNotEmpty())
    }

    /**
     * Review of #45: a uri sent without a read grant. Android refuses to pass the grant on, and startService throws
     * SecurityException: the activity stays, nothing is imported, and the Library shows "Can't open this file".
     */
    @Test fun aShareWithoutAReadGrantShowsCantOpenInsteadOfCrashing() {
        val starter = ImportGrantService.starter
        ImportGrantService.starter = { _, intent -> throw SecurityException("No permission to grant ${intent.data}") }
        try {
            ActivityScenario.launch<MainActivity>(send("content://$AUTHORITY/voice/9")).use { scenario ->
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                assertEquals(Failure.OPEN, app.graph.notices.imports.state.value.failure?.failure)
                assertTrue(app.graph.notices.imports.state.value.running.isEmpty())
            }
            assertEquals("the provider was never asked", 1L, GrantedProvider.entered.count)
        } finally {
            ImportGrantService.starter = starter
        }
    }

    /** The control: without the service, the provider refuses the import once the activity has finished. */
    @Test fun theProviderRefusesAnImportThatOutlivesTheActivityWithoutTheService() {
        val uri = "content://$AUTHORITY/voice/8"
        var activityAlive = true
        GrantedProvider.held = { activityAlive }
        GrantedProvider.gate = CountDownLatch(1)
        val scenario = ActivityScenario.launch<MainActivity>(send(uri))
        importStarts().single()
        // The import as it ran before the fix: on the app's scope, with no service holding the grant.
        val outcome = app.graph.notices.imports.start(uri)
        assertTrue(GrantedProvider.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        scenario.close()
        activityAlive = false
        GrantedProvider.gate.countDown()
        val result = runBlocking { withTimeout(TIMEOUT_MS) { outcome.await() } }
        assertEquals("Can't open this file", Failure.OPEN, (result as ImportOutcome.Rejected).failure)
        assertEquals(Failure.OPEN, app.graph.notices.imports.state.value.failure?.failure)
    }

    /** A provider that answers only while [held] says the receiving app still holds its read grant. */
    class GrantedProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            entered.countDown()
            check(gate.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "The test never released the provider" }
            if (!held()) throw SecurityException("No read grant for $uri")
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf<Any>("voice.wav", file.length()))
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (!held()) throw SecurityException("No read grant for $uri")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun getType(uri: Uri) = "audio/wav"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) =
            0

        companion object {
            @Volatile lateinit var file: File

            @Volatile var held: () -> Boolean = { true }

            @Volatile var entered = CountDownLatch(1)

            @Volatile var gate = CountDownLatch(0)
        }
    }

    private companion object {
        const val AUTHORITY = "org.example.chat.voice"
        const val TIMEOUT_MS = 20_000L
    }
}
