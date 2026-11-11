package io.codemyriad.cassini.documents

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.CassiniApplication
import io.codemyriad.cassini.ui.R as UiR

/**
 * Exposes Cassini notes read-only to the Android system file picker and Files app (`DocumentsProvider`, D97).
 *
 * Guarded by `android.permission.MANAGE_DOCUMENTS`, which only the platform document picker holds; other apps reach a
 * document only when the user selects it in the picker and receives a temporary read-only URI grant. Live, finished
 * notes are exposed read-only (`.opus` for Cassini documents, original container for plain-audio notes). Creation,
 * write modes, delete, rename, move, and copy through the provider are refused; inbound importing uses Cassini's
 * Share / Open with, multi-file picker, and ZIP import flows.
 */
@Suppress("TooManyFunctions") // one override per DocumentsProvider callback of D97
class CassiniDocumentsProvider : DocumentsProvider() {
    @Volatile
    private var providerAuthority: String? = null

    private val vault: DocumentsVault by lazy {
        val ctx = checkNotNull(context) { "Provider is not attached to a Context" }
        DocumentsVault(
            context = ctx.applicationContext,
            authority = ::resolvedAuthority,
            graphProvider = { graphOverrideForTesting?.invoke() ?: (ctx.applicationContext as CassiniApplication).graph },
        )
    }

    override fun attachInfo(context: Context, info: ProviderInfo) {
        providerAuthority = info.authority
        super.attachInfo(context, info)
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection?.let { Array(it.size) { idx -> it[idx] } } ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(columns, 1)
        val flags = Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD
        val row = cursor.newRow()
        for (column in columns) {
            when (column) {
                Root.COLUMN_ROOT_ID -> row.add(DocumentsVault.ROOT_ID)
                Root.COLUMN_FLAGS -> row.add(flags)
                Root.COLUMN_ICON -> row.add(UiR.mipmap.ui_ic_launcher)
                Root.COLUMN_TITLE -> row.add(vault.rootTitle())
                Root.COLUMN_DOCUMENT_ID -> row.add(DocumentsVault.ROOT_DOCUMENT_ID)
                Root.COLUMN_MIME_TYPES -> row.add(SUPPORTED_MIME_TYPES)
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean =
        vault.isChildDocument(parentDocumentId, documentId)

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val doc = vault.queryDocument(documentId)
        val columns = projection?.let { Array(it.size) { idx -> it[idx] } } ?: DEFAULT_DOCUMENT_PROJECTION
        return MatrixCursor(columns, 1).also { cursor -> addDocumentRow(cursor, columns, doc) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val docs = vault.listChildren(parentDocumentId)
        val columns = projection?.let { Array(it.size) { idx -> it[idx] } } ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(columns, docs.size)
        for (doc in docs) {
            addDocumentRow(cursor, columns, doc)
        }
        context?.contentResolver?.let { resolver ->
            val uri = DocumentsContract.buildChildDocumentsUri(resolvedAuthority(), DocumentsVault.ROOT_DOCUMENT_ID)
            cursor.setNotificationUri(resolver, uri)
        }
        return cursor
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = vault.openDocument(documentId, mode, signal)

    override fun createDocument(parentDocumentId: String?, mimeType: String?, displayName: String?): String {
        throw UnsupportedOperationException("CassiniDocumentsProvider is read-only; import files inside Cassini")
    }

    override fun deleteDocument(documentId: String?) {
        throw UnsupportedOperationException("Deleting notes from external apps is not supported")
    }

    override fun removeDocument(documentId: String?, parentDocumentId: String?) {
        throw UnsupportedOperationException("Removing notes from external apps is not supported")
    }

    override fun renameDocument(documentId: String?, displayName: String?): String {
        throw UnsupportedOperationException("Renaming notes from external apps is not supported")
    }

    override fun moveDocument(
        sourceDocumentId: String?,
        sourceParentDocumentId: String?,
        targetParentDocumentId: String?,
    ): String {
        throw UnsupportedOperationException("Moving notes is not supported")
    }

    override fun copyDocument(sourceDocumentId: String?, targetParentDocumentId: String?): String {
        throw UnsupportedOperationException("Copying notes in place is not supported")
    }

    private fun addDocumentRow(cursor: MatrixCursor, columns: Array<String>, doc: ExposedDocument) {
        val row = cursor.newRow()
        for (column in columns) {
            when (column) {
                Document.COLUMN_DOCUMENT_ID -> row.add(doc.documentId)
                Document.COLUMN_DISPLAY_NAME -> row.add(doc.displayName)
                Document.COLUMN_MIME_TYPE -> row.add(doc.mimeType)
                Document.COLUMN_SIZE -> row.add(doc.size)
                Document.COLUMN_LAST_MODIFIED -> row.add(doc.lastModified)
                Document.COLUMN_FLAGS -> row.add(doc.flags)
                else -> row.add(null)
            }
        }
    }

    private fun resolvedAuthority(): String =
        providerAuthority ?: "${checkNotNull(context).packageName}$AUTHORITY_SUFFIX"

    companion object {
        const val AUTHORITY_SUFFIX = ".documents"
        private const val SUPPORTED_MIME_TYPES = "audio/*\napplication/ogg"

        @Volatile
        internal var graphOverrideForTesting: (() -> AppGraph)? = null

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_MIME_TYPES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
        )
    }
}
