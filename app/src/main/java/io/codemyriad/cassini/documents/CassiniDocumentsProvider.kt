package io.codemyriad.cassini.documents

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Binder
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import io.codemyriad.cassini.CassiniApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

/** SAF grants are enforced by DocumentsProvider before these callbacks; existing notes are always read-only. */
@Suppress("TooManyFunctions") // Required SAF callbacks and projection helpers.
class CassiniDocumentsProvider : DocumentsProvider() {
    private val storage by lazy {
        val app = checkNotNull(context).applicationContext as CassiniApplication
        DocumentStorage(app, app.graph)
    }

    // Android creates providers before Application.onCreate: do not initialize the graph here.
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = cursor(projection, ROOT_COLUMNS).apply {
        val app = checkNotNull(context)
        add(
            mapOf(
                Root.COLUMN_ROOT_ID to DocumentStorage.ROOT,
                Root.COLUMN_DOCUMENT_ID to DocumentStorage.ROOT,
                Root.COLUMN_TITLE to app.getString(io.codemyriad.cassini.R.string.app_name),
                Root.COLUMN_ICON to app.applicationInfo.icon,
                Root.COLUMN_FLAGS to (Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD),
                Root.COLUMN_MIME_TYPES to "audio/*\napplication/ogg",
            ),
        )
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = io {
        cursor(projection, DOCUMENT_COLUMNS).apply {
            if (documentId == DocumentStorage.ROOT) {
                add(rootDocument())
            } else {
                val flags = if (storage.writable(documentId)) Document.FLAG_SUPPORTS_WRITE else 0
                add(document(storage.document(documentId), flags))
            }
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = io {
        requireRoot(parentDocumentId)
        cursor(projection, DOCUMENT_COLUMNS).apply {
            storage.copies.list().forEach { add(document(it)) }
            val app = checkNotNull(context)
            setNotificationUri(
                app.contentResolver,
                DocumentsContract.buildChildDocumentsUri(app.packageName + ".documents", parentDocumentId),
            )
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = io {
        if (parentDocumentId != DocumentStorage.ROOT || documentId == DocumentStorage.ROOT) return@io false
        try {
            storage.document(documentId)
            true
        } catch (_: FileNotFoundException) {
            false
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String = io {
        requireRoot(parentDocumentId)
        storage.create(displayName, mimeType)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor =
        if (mode == "r") {
            io(signal) { storage.open(documentId, mode, null) }
        } else {
            io {
                signal?.throwIfCanceled()
                storage.open(documentId, mode, signal)
            }
        }

    private fun requireRoot(id: String) {
        if (id != DocumentStorage.ROOT) throw FileNotFoundException("Unknown folder")
    }

    private fun rootDocument(): Map<String, Any?> = mapOf(
        Document.COLUMN_DOCUMENT_ID to DocumentStorage.ROOT,
        Document.COLUMN_DISPLAY_NAME to checkNotNull(context).getString(io.codemyriad.cassini.R.string.app_name),
        Document.COLUMN_MIME_TYPE to Document.MIME_TYPE_DIR,
        Document.COLUMN_FLAGS to Document.FLAG_DIR_SUPPORTS_CREATE,
    )

    private fun document(item: ExposedDocument, flags: Int = 0): Map<String, Any?> = mapOf(
        Document.COLUMN_DOCUMENT_ID to item.id,
        Document.COLUMN_DISPLAY_NAME to item.name,
        Document.COLUMN_MIME_TYPE to item.mime,
        Document.COLUMN_FLAGS to flags,
        Document.COLUMN_SIZE to item.size,
        Document.COLUMN_LAST_MODIFIED to item.modified,
    )

    private fun cursor(projection: Array<out String>?, defaults: Array<String>) = MatrixCursor(projection ?: defaults)
    private fun MatrixCursor.add(values: Map<String, Any?>) { addRow(columnNames.map { values[it] }) }

    private fun <T> io(signal: CancellationSignal? = null, action: suspend () -> T): T {
        signal?.throwIfCanceled()
        val identity = Binder.clearCallingIdentity()
        val job = Job()
        signal?.setOnCancelListener { job.cancel() }
        return try {
            runBlocking(Dispatchers.IO + job) { action() }
        } catch (_: CancellationException) {
            throw OperationCanceledException()
        } finally {
            signal?.setOnCancelListener(null)
            job.cancel()
            Binder.restoreCallingIdentity(identity)
        }
    }

    companion object {
        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
