package io.codemyriad.cassini.flows

import android.content.Intent
import android.database.ContentObserver
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.uiautomator.By
import io.codemyriad.cassini.MainActivity
import io.codemyriad.cassini.background.Background
import io.codemyriad.cassini.documents.DocumentStorage
import io.codemyriad.cassini.documents.PermissionProbeActivity
import io.codemyriad.cassini.test.CiSmoke
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File

/** Actual Binder permissions, reliable pipes and completion notifications; no deadline-based success checks. */
@CiSmoke
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DocumentsProviderDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context get() = Background.context
    private val resolver get() = context.contentResolver
    private val authority get() = context.packageName + ".documents"
    private val children get() = DocumentsContract.buildChildDocumentsUri(authority, DocumentStorage.ROOT)
    private val changed = Channel<Unit>(Channel.UNLIMITED)
    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) { changed.trySend(Unit) }
    }

    @Before fun setUp() = runBlocking {
        Background.grant()
        Background.graph.ready.await()
        Background.reset()
        resolver.registerContentObserver(children, false, observer)
    }

    @After fun tearDown() = runBlocking {
        resolver.unregisterContentObserver(observer)
        Background.reset()
    }

    @Test fun foreignUidWithoutAGrantCannotQueryTheProvider() {
        val returned = CompletableDeferred<Intent?>()
        lateinit var launch: androidx.activity.result.ActivityResultLauncher<Intent>
        rule.runOnUiThread {
            launch = rule.activity.activityResultRegistry.register(
                "provider-permission",
                ActivityResultContracts.StartActivityForResult(),
            ) {
                returned.complete(it.data)
            }
            val probe = Intent().setClassName(
                Background.instrumentation.context.packageName,
                PermissionProbeActivity::class.java.name,
            )
                .putExtra("uri", DocumentsContract.buildRootsUri(authority).toString())
            launch.launch(probe)
        }
        try {
            val result = checkNotNull(runBlocking { withTimeout(20_000) { returned.await() } })
            assertNotEquals(android.os.Process.myUid(), result.getIntExtra("uid", -1))
            assertTrue(result.getStringExtra("reason"), result.getBooleanExtra("denied", false))
        } finally {
            rule.runOnUiThread { launch.unregister() }
        }
    }

    @Test fun systemFilesPickerListsAndOpensAPortableNote() = runBlocking {
        val source = Flows.meetingDocument("Provider Monday")
        Background.import(source)
        val returned = CompletableDeferred<android.net.Uri?>()
        lateinit var launch: androidx.activity.result.ActivityResultLauncher<Array<String>>
        lateinit var root: android.view.View
        rule.runOnUiThread {
            root = rule.activity.window.decorView
            val contract = object : ActivityResultContracts.OpenDocument() {
                override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
                    super.createIntent(context, input).putExtra(
                        DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildDocumentUri(authority, DocumentStorage.ROOT),
                    )
            }
            launch = rule.activity.activityResultRegistry.register("provider-picker", contract) {
                returned.complete(it)
            }
            launch.launch(arrayOf("audio/*"))
        }
        try {
            val device = androidx.test.uiautomator.UiDevice.getInstance(Background.instrumentation)
            UiEvents.await(root, 20_000) { device.hasObject(By.text("Provider Monday.opus")) }
            device.findObject(By.text("Provider Monday.opus")).click()
            val uri = checkNotNull(withTimeout(20_000) { returned.await() })
            assertEquals(authority, uri.authority)
            assertArrayEquals(source.readBytes(), resolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally {
            rule.runOnUiThread { launch.unregister() }
        }
    }

    @Test fun incomingReliablePipesImportDeduplicateAndKeepTheirReadOnlyUris() = runBlocking {
        val audio = Background.assetCopy("fleurs-it-short.wav").readBytes()
        val root = DocumentsContract.buildDocumentUri(authority, DocumentStorage.ROOT)
        val aliases = (1..2).map {
            val uri = checkNotNull(DocumentsContract.createDocument(resolver, root, "audio/wav", "Incoming.wav"))
            resolver.openFileDescriptor(uri, "w")!!.use { descriptor ->
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { it.write(audio) }
            }
            completed()
            assertArrayEquals(audio, resolver.openInputStream(uri)!!.use { it.readBytes() })
            val flags = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)!!.use {
                check(it.moveToFirst())
                it.getInt(0)
            }
            assertEquals(0, flags)
            uri
        }
        assertNotEquals(aliases[0], aliases[1])
        assertEquals(1, Background.graph.awaitCatalogue().database.notes().all().count { it.trashedAt == null })
        assertClean()
    }

    @Test fun abortedCompletePayloadAndCancelledBlockedPipeLeaveNoNoteOrStaging() = runBlocking {
        val root = DocumentsContract.buildDocumentUri(authority, DocumentStorage.ROOT)
        val uri = checkNotNull(DocumentsContract.createDocument(resolver, root, "audio/wav", "Aborted.wav"))
        val descriptor = checkNotNull(resolver.openFileDescriptor(uri, "w"))
        val output = java.io.FileOutputStream(descriptor.fileDescriptor)
        output.write(Background.assetCopy("fleurs-it-short.wav").readBytes())
        descriptor.closeWithError("Client aborted after complete payload")
        runCatching { output.close() }
        completed()
        assertTrue(Background.graph.awaitCatalogue().database.notes().all().none { it.trashedAt == null })
        assertClean()
        Background.graph.notices.imports.dismissFailure()
        val storage = DocumentStorage(context, Background.graph)
        val cancelled = storage.create("Cancelled.wav", "audio/wav")
        val signal = CancellationSignal()
        storage.open(cancelled, "w", signal).use {
            signal.cancel()
            completed()
        }
        assertTrue(Background.graph.awaitCatalogue().database.notes().all().none { it.trashedAt == null })
        assertEquals(null, Background.graph.notices.imports.state.value.failure)
        assertClean()
    }

    @Test fun uploadQuotaFailureReleasesItsBudgetForTheNextValidImport() = runBlocking {
        val audio = Background.assetCopy("fleurs-it-short.wav").readBytes()
        val storage = DocumentStorage(context, Background.graph, maxBytes = audio.size.toLong())
        val tooLarge = storage.create("Too large.wav", "audio/wav")
        runCatching {
            ParcelFileDescriptor.AutoCloseOutputStream(storage.open(tooLarge, "w", null)).use {
                it.write(audio)
                it.write(1)
            }
        }
        completed()
        assertTrue(Background.graph.awaitCatalogue().database.notes().all().none { it.trashedAt == null })
        assertClean()
        val valid = storage.create("Valid.wav", "audio/wav")
        ParcelFileDescriptor.AutoCloseOutputStream(storage.open(valid, "w", null)).use { it.write(audio) }
        completed()
        assertEquals(1, Background.graph.awaitCatalogue().database.notes().all().count { it.trashedAt == null })
        ParcelFileDescriptor.AutoCloseInputStream(storage.open(valid, "r", null)).use {
            assertArrayEquals(audio, it.readBytes())
        }
    }

    private suspend fun completed() { withTimeout(20_000) { changed.receive() } }

    private fun assertClean() {
        val scratch = File(Background.graph.paths.cache, "documents-provider")
        assertFalse(scratch.walkTopDown().any { it.isFile })
    }
}
