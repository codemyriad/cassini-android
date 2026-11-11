package io.codemyriad.cassini.documents

import android.content.Context
import android.database.SQLException
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.Catalogue
import io.codemyriad.cassini.core.export.ExportNames
import io.codemyriad.cassini.data.NoteIds
import io.codemyriad.cassini.data.Sha256
import io.codemyriad.cassini.data.db.NoteEntity
import io.codemyriad.cassini.data.notes.ImportLimits
import io.codemyriad.cassini.data.notes.ImportOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Lifecycle state of the Cassini note associated with an accepted creation grant. */
internal enum class NoteLifecycle {
    LIVE,
    TRASHED,
    UNCATALOGUED,
    PURGED,
}

private sealed interface GrantImportResult {
    data class Accepted(val noteId: String) : GrantImportResult
    data object RetryableFailure : GrantImportResult
    data object PermanentRejection : GrantImportResult
}

private enum class FilePresence {
    REGULAR_FILE,
    ABSENT,
    IO_ERROR,
}

/**
 * Persistent creation grants allocated through [CassiniDocumentsProvider.createDocument].
 *
 * Each `createDocument` allocates a stable `pending:<uuid>` document ID persisted under
 * `filesDir/provider-grants/<uuid>/meta.json` (`state = AWAITING_WRITE`). Opening for write (`"w"` or `"wt"`) returns
 * the write end of a kernel pipe (`ParcelFileDescriptor.createPipe()`) while a background pump streams incoming bytes
 * into `payload.tmp`, enforcing [ImportLimits.maxBytes] and [ImportLimits.minFreeBytes] **during streaming** before
 * writer close and following real kernel descriptor EOF (so native clients that call `detachFd()` keep writing safely
 * until they close the detached descriptor).
 *
 * On clean EOF the submitted bytes are moved to the fixed internal filename `<uuid>/payload.bin` (keeping the caller's
 * `displayName` solely in `meta.json`, so names such as `meta.json` or `meta.json.tmp` never collide with grant
 * metadata) with a pre-allocated destination `noteId` (`state = IMPORTING`). Operational I/O or SQL failures (including
 * metadata-read `IOException`s and `ImportRepository.copy` stream/open/read/write errors) and cancellation retain the
 * persisted grant on disk for retry; only proven content rejections (`ImportOutcome.Rejected.retryable == false`),
 * permanently purged notes, or proven `ENOENT` placeholders delete a grant directory.
 */
@Suppress("TooManyFunctions", "LargeClass") // grant persistence, bounded pipe pump, lifecycle and startup recovery
internal class PendingImports(private val parts: Parts) {
    @Suppress("LongParameterList")
    data class Parts(
        val grantsRoot: () -> File,
        val scope: () -> CoroutineScope,
        val io: () -> CoroutineDispatcher,
        val clock: () -> Long,
        val limits: () -> ImportLimits,
        val ready: () -> Deferred<Unit>,
        val awaitCatalogue: suspend () -> Catalogue,
        val importLiveFile: suspend (File, String, String) -> ImportOutcome?,
        val importRecoveredFile: suspend (Catalogue, File, String, String) -> ImportOutcome?,
        val noteLifecycle: suspend (Catalogue, String) -> NoteLifecycle,
    )

    private val active = ConcurrentHashMap<String, ActiveEntry>()
    private val unwrittenOrder = ConcurrentLinkedDeque<String>()
    private val acceptedCache = ConcurrentHashMap<String, GrantRecord>()
    private val acceptedOrder = ConcurrentLinkedDeque<String>()
    private val recoveryMutex = Mutex()

    /** True when [documentId] has the `pending:<uuid>` syntax. */
    fun isSyntacticallyValid(documentId: String): Boolean = tokenOf(documentId) != null

    /** Allocates and persists a write-once `pending:<uuid>` document under `"root"`. */
    fun create(mimeType: String?, displayName: String?): String {
        val now = parts.clock()
        sweepAbandoned(now)
        val token = UUID.randomUUID().toString()
        val id = "$PREFIX$token"
        val cleanName = cleanDisplayName(displayName, mimeType)
        val cleanMime = mimeType?.trim()?.takeIf { it.isNotEmpty() && it != Document.MIME_TYPE_DIR } ?: DEFAULT_MIME
        val record = GrantRecord(
            token = token,
            displayName = cleanName,
            mimeType = cleanMime,
            createdAt = now,
            updatedAt = now,
            state = PersistedState.AWAITING_WRITE,
            size = 0L,
        )
        val dir = grantDir(token)
        val entry = ActiveEntry(token, id, cleanName, cleanMime, now)
        active[token] = entry
        var persisted = false
        try {
            if (!dir.mkdirs() && !dir.isDirectory) {
                throw FileNotFoundException("Cannot create grant directory")
            }
            writeMeta(dir, record)
            persisted = true
        } finally {
            if (!persisted) {
                active.remove(token, entry)
                deleteGrantDir(dir)
            }
        }
        unwrittenOrder.addLast(token)
        trimUnwrittenPlaceholders()
        return id
    }

    /** True when [documentId] is a live unwritten, in-progress, or non-trashed accepted creation grant. */
    suspend fun isKnown(documentId: String): Boolean {
        val token = tokenOf(documentId) ?: return false
        return try {
            val entry = active[token]
            if (entry != null) isActiveKnown(entry) else isPersistedKnown(token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            false
        } catch (_: SQLException) {
            false
        }
    }

    private suspend fun isActiveKnown(entry: ActiveEntry): Boolean = when (val stage = entry.stage.get()) {
        Stage.AwaitingWrite, is Stage.Writing, Stage.Importing -> true
        is Stage.Accepted -> {
            val catalogue = awaitProviderCatalogue()
            resolveAccessible(stage.record, catalogue) != null
        }
        Stage.Rejected -> false
    }

    private suspend fun isPersistedKnown(token: String): Boolean {
        val catalogue = awaitProviderCatalogue()
        val restored = restoreActiveFromDisk(token)
        return if (restored != null) {
            true
        } else {
            loadAcceptedOrRecover(token, catalogue)?.let { resolveAccessible(it, catalogue) != null } ?: false
        }
    }

    /** Returns stable metadata for [documentId] (`COLUMN_DOCUMENT_ID` always stays `pending:<uuid>`). */
    suspend fun inspect(documentId: String): ExposedDocument {
        val token = tokenOf(documentId) ?: throw FileNotFoundException("Unknown document: $documentId")
        val immediate = active[token]?.let(::inspectImmediateActive)
        return immediate ?: runProviderLookup(documentId) {
            val catalogue = awaitProviderCatalogue()
            val entry = active[token] ?: restoreActiveFromDisk(token)
            if (entry != null) {
                inspectActive(entry, documentId, catalogue)
            } else {
                loadAcceptedOrRecover(token, catalogue)?.let { resolveAccessible(it, catalogue) }?.toExposedDocument()
                    ?: throw FileNotFoundException("Unknown document: $documentId")
            }
        }
    }

    private fun inspectImmediateActive(entry: ActiveEntry): ExposedDocument? = when (val stage = entry.stage.get()) {
        Stage.AwaitingWrite -> ExposedDocument(
            documentId = entry.documentId,
            displayName = entry.displayName,
            mimeType = entry.mimeType,
            size = 0L,
            lastModified = entry.createdAt,
            flags = Document.FLAG_SUPPORTS_WRITE,
        )
        is Stage.Writing -> if (!entry.cancelled.get() && !isWriterClosed(stage.readFd)) {
            ExposedDocument(
                documentId = entry.documentId,
                displayName = entry.displayName,
                mimeType = entry.mimeType,
                size = null,
                lastModified = entry.createdAt,
                flags = 0,
            )
        } else {
            null
        }
        Stage.Importing, is Stage.Accepted, Stage.Rejected -> null
    }

    private suspend fun inspectActive(entry: ActiveEntry, documentId: String, catalogue: Catalogue): ExposedDocument =
        inspectImmediateActive(entry)
            ?: requireAccessibleRecord(awaitAcceptedRecord(entry, documentId, null), documentId, catalogue)
                .toExposedDocument()

    /** Opens a bounded kernel pipe for writing [documentId] once. */
    fun openForWrite(documentId: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val entry = requireWritableEntry(documentId)
        val (readPfd, writePfd) = createStagingPipe()
        if (!entry.stage.compareAndSet(Stage.AwaitingWrite, Stage.Writing(readPfd.fileDescriptor))) {
            runCatching { readPfd.close() }
            runCatching { writePfd.close() }
            throw SecurityException("Document $documentId is not writable")
        }
        val dir = prepareGrantDir(entry, readPfd, writePfd)
        try {
            writeMeta(dir, entry.toRecord(PersistedState.WRITING, 0L, parts.clock()))
        } catch (error: IOException) {
            abortEntry(entry, dir, readPfd)
            runCatching { writePfd.close() }
            throw FileNotFoundException(error.message ?: "Cannot persist grant state").apply { initCause(error) }
        }
        signal?.setOnCancelListener {
            entry.cancelled.set(true)
            runCatching { readPfd.close() }
        }
        startPumpThread(entry, dir, readPfd, parts.limits())
        return writePfd
    }

    private fun requireWritableEntry(documentId: String): ActiveEntry {
        val token = tokenOf(documentId)
        val entry = token?.let { active[it] ?: restoreActiveFromDisk(it) }
        if (entry != null) return entry
        val persistedState = (token?.let { readMeta(grantDir(it)) } as? MetaRead.Valid)?.record?.state
        val alreadyWritten = token != null && (
            acceptedCache.containsKey(token) ||
                persistedState == PersistedState.ACCEPTED ||
                persistedState == PersistedState.IMPORTING
            )
        throw if (alreadyWritten) {
            SecurityException("Document $documentId is not writable")
        } else {
            FileNotFoundException("Unknown document: $documentId")
        }
    }

    private fun createStagingPipe(): Pair<ParcelFileDescriptor, ParcelFileDescriptor> = try {
        val pipe = ParcelFileDescriptor.createPipe()
        pipe[0] to pipe[1]
    } catch (error: IOException) {
        throw FileNotFoundException(error.message ?: "Cannot open staging pipe").apply { initCause(error) }
    }

    private fun prepareGrantDir(
        entry: ActiveEntry,
        readPfd: ParcelFileDescriptor,
        writePfd: ParcelFileDescriptor,
    ): File {
        val dir = grantDir(entry.token)
        if (!dir.mkdirs() && !dir.isDirectory) {
            abortEntry(entry, dir, readPfd)
            runCatching { writePfd.close() }
            throw FileNotFoundException("Cannot prepare staging directory")
        }
        return dir
    }

    /** Opens the immutable submitted payload of [documentId] read-only, never reading any existing note. */
    suspend fun openForRead(documentId: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val token = tokenOf(documentId) ?: throw FileNotFoundException("Unknown document: $documentId")
        val record = runProviderLookup(documentId) {
            val catalogue = awaitProviderCatalogue(signal)
            resolveReadableRecord(token, documentId, catalogue, signal)
        }
        signal?.throwIfCanceled()
        return ParcelFileDescriptor.open(payloadFile(record), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private suspend fun <T> runProviderLookup(documentId: String, block: suspend () -> T): T = try {
        block()
    } catch (error: IOException) {
        throw (error as? FileNotFoundException)
            ?: FileNotFoundException(error.message ?: "Cannot access $documentId").apply { initCause(error) }
    } catch (error: SQLException) {
        throw FileNotFoundException(error.message ?: "Cannot access $documentId").apply { initCause(error) }
    }

    /**
     * Reconciles `filesDir/provider-grants/` during `STEP_SWEEP` after `STEP_REBUILD` and `STEP_PURGE`:
     * - deletes proven-empty corrupt/crashed directories and expired `AWAITING_WRITE` placeholders;
     * - imports any persisted `IMPORTING` (or crashed-after-EOF `WRITING`) payload idempotently into
     *   `ImportRepository`, retaining grants on operational I/O or SQL failures and cancellation;
     * - deletes any `ACCEPTED` grant whose associated note was permanently purged.
     */
    suspend fun reconcileStartup(catalogue: Catalogue) = withContext(parts.io()) {
        val cutoff = parts.clock() - STALE_UNWRITTEN_MS
        sweepActiveAbandoned(cutoff)
        val root = parts.grantsRoot()
        if (!root.isDirectory) return@withContext
        for (dir in root.listFiles().orEmpty().sortedBy { it.name }) {
            try {
                reconcileGrantDir(dir, cutoff, catalogue)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                // Retain this grant for the next startup pass and continue reconciling sibling grants.
            } catch (_: SQLException) {
                // Retain this grant for the next startup pass and continue reconciling sibling grants.
            }
        }
    }

    /**
     * Deletes every persisted creation grant tied to [noteId] when the note is permanently purged (`NoteFilesCleaner`).
     * Returns true when no grant directory for [noteId] remains on disk and no metadata read failed with I/O error.
     */
    suspend fun deleteForNote(noteId: String): Boolean = withContext(parts.io()) {
        recoveryMutex.withLock {
            var allDeleted = deleteCachedGrantsForNoteLocked(noteId)
            val root = parts.grantsRoot()
            if (root.isDirectory) {
                for (dir in root.listFiles().orEmpty()) {
                    if (!reclaimDiskGrantForNoteLocked(dir, noteId)) allDeleted = false
                }
            }
            allDeleted
        }
    }

    private fun deleteCachedGrantsForNoteLocked(noteId: String): Boolean {
        var allDeleted = true
        for ((token, record) in acceptedCache.entries.toList()) {
            if (record.noteId == noteId) {
                evictAccepted(token)
                if (!deleteGrantDir(grantDir(token))) allDeleted = false
            }
        }
        return allDeleted
    }

    private fun reclaimDiskGrantForNoteLocked(dir: File, noteId: String): Boolean {
        val token = dir.name
        if (!NoteIds.isValid(token)) return true
        return when (val meta = readMeta(dir)) {
            is MetaRead.Valid -> if (meta.record.noteId == noteId) {
                evictAccepted(token)
                deleteGrantDir(dir)
            } else {
                true
            }
            MetaRead.IoFailure -> false
            MetaRead.Missing, MetaRead.Corrupt -> true
        }
    }

    internal fun clearInMemoryStateForTesting() {
        active.clear()
        unwrittenOrder.clear()
        acceptedCache.clear()
        acceptedOrder.clear()
    }

    private suspend fun awaitProviderCatalogue(signal: CancellationSignal? = null): Catalogue {
        val ready = parts.ready()
        if (signal == null) {
            ready.await()
        } else {
            awaitWithSignal(ready, signal)
        }
        return parts.awaitCatalogue()
    }

    private suspend fun reconcileGrantDir(dir: File, cutoff: Long, catalogue: Catalogue) {
        val token = dir.name
        when {
            !NoteIds.isValid(token) -> deleteGrantDir(dir)
            !active.containsKey(token) -> reconcileInactiveGrantDir(token, dir, cutoff, catalogue)
        }
    }

    private suspend fun reconcileInactiveGrantDir(token: String, dir: File, cutoff: Long, catalogue: Catalogue) {
        var acceptedToVerify: GrantRecord? = null
        recoveryMutex.withLock {
            if (!active.containsKey(token)) {
                acceptedToVerify = reconcileInactiveLocked(token, dir, cutoff, catalogue)
            }
        }
        acceptedToVerify?.let { resolveAccessible(it, catalogue) }
    }

    private suspend fun reconcileInactiveLocked(
        token: String,
        dir: File,
        cutoff: Long,
        catalogue: Catalogue,
    ): GrantRecord? = when (val meta = readMeta(dir)) {
        MetaRead.IoFailure -> null
        MetaRead.Missing, MetaRead.Corrupt -> reconcileUnreadableMetaLocked(token, dir, catalogue)
        is MetaRead.Valid -> reconcileValidMetaLocked(meta.record, dir, cutoff, catalogue)
    }

    private suspend fun reconcileUnreadableMetaLocked(token: String, dir: File, catalogue: Catalogue): GrantRecord? {
        val payload = payloadFile(token)
        return when (probeRegularFile(payload)) {
            FilePresence.ABSENT -> {
                deleteGrantDir(dir)
                null
            }
            FilePresence.IO_ERROR -> null
            FilePresence.REGULAR_FILE -> recoverSynthesizedLocked(token, dir, payload, catalogue)
        }
    }

    private suspend fun reconcileValidMetaLocked(
        meta: GrantRecord,
        dir: File,
        cutoff: Long,
        catalogue: Catalogue,
    ): GrantRecord? {
        val payload = payloadFile(meta)
        return when (meta.state) {
            PersistedState.WRITING -> when (probeRegularFile(payload)) {
                FilePresence.ABSENT -> {
                    deleteGrantDir(dir)
                    null
                }
                FilePresence.IO_ERROR -> null
                FilePresence.REGULAR_FILE -> promoteFinishedWritingLocked(meta, dir, payload, catalogue)
            }
            PersistedState.AWAITING_WRITE -> {
                if (meta.createdAt < cutoff && probeRegularFile(payload) == FilePresence.ABSENT) {
                    deleteGrantDir(dir)
                }
                null
            }
            PersistedState.IMPORTING -> recoverLocked(meta, dir, catalogue)
            PersistedState.ACCEPTED -> meta
        }
    }

    private suspend fun resolveReadableRecord(
        token: String,
        documentId: String,
        catalogue: Catalogue,
        signal: CancellationSignal?,
    ): GrantRecord {
        val entry = active[token] ?: restoreActiveFromDisk(token)
        val candidate = if (entry != null) {
            if (entry.stage.get() == Stage.AwaitingWrite) null else awaitAcceptedRecord(entry, documentId, signal)
        } else {
            loadAcceptedOrRecover(token, catalogue)
        }
        return candidate?.let { resolveAccessible(it, catalogue) }
            ?: throw FileNotFoundException("Unknown or unavailable document: $documentId")
    }

    private suspend fun requireAccessibleRecord(
        record: GrantRecord,
        documentId: String,
        catalogue: Catalogue,
    ): GrantRecord =
        resolveAccessible(record, catalogue) ?: throw FileNotFoundException("Document is not available: $documentId")

    private suspend fun resolveAccessible(record: GrantRecord, catalogue: Catalogue): GrantRecord? {
        val noteId = record.noteId
        val lifecycle = if (noteId != null) parts.noteLifecycle(catalogue, noteId) else NoteLifecycle.PURGED
        return when {
            lifecycle == NoteLifecycle.PURGED -> {
                purgeGrant(record.token)
                null
            }
            lifecycle == NoteLifecycle.TRASHED || lifecycle == NoteLifecycle.UNCATALOGUED -> null
            else -> when (probeRegularFile(payloadFile(record))) {
                FilePresence.REGULAR_FILE -> record
                FilePresence.IO_ERROR -> null
                FilePresence.ABSENT -> {
                    purgeGrant(record.token)
                    null
                }
            }
        }
    }

    private suspend fun purgeGrant(token: String) {
        recoveryMutex.withLock {
            evictAccepted(token)
            deleteGrantDir(grantDir(token))
        }
    }

    private suspend fun awaitAcceptedRecord(
        entry: ActiveEntry,
        documentId: String,
        signal: CancellationSignal?,
    ): GrantRecord {
        val outcome = withContext(parts.io()) {
            if (signal == null) {
                entry.completed.await()
            } else {
                awaitWithSignal(entry.completed, signal)
            }
        }
        return outcome ?: throw FileNotFoundException("Import of $documentId was rejected")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun <T> awaitWithSignal(
        deferred: Deferred<T>,
        signal: CancellationSignal,
    ): T = suspendCancellableCoroutine { cont ->
        signal.setOnCancelListener {
            cont.cancel(OperationCanceledException())
        }
        deferred.invokeOnCompletion { error ->
            if (!cont.isActive) return@invokeOnCompletion
            if (error != null) cont.resumeWithException(error) else cont.resume(deferred.getCompleted())
        }
    }

    private fun startPumpThread(
        entry: ActiveEntry,
        dir: File,
        readPfd: ParcelFileDescriptor,
        currentLimits: ImportLimits,
    ) {
        val pump = Thread(
            { pumpPipe(entry, dir, readPfd, currentLimits) },
            "cassini-doc-pipe-${entry.token.take(THREAD_TOKEN_LEN)}",
        )
        pump.isDaemon = true
        pump.start()
    }

    private fun pumpPipe(
        entry: ActiveEntry,
        dir: File,
        readPfd: ParcelFileDescriptor,
        currentLimits: ImportLimits,
    ) {
        val finalFile = File(dir, PAYLOAD_FILE)
        val total = stagePipeToPayload(entry, dir, readPfd, finalFile, currentLimits) ?: return
        val targetNoteId = UUID.randomUUID().toString()
        try {
            writeMeta(dir, entry.toRecord(PersistedState.IMPORTING, total, parts.clock(), noteId = targetNoteId))
        } catch (_: IOException) {
            // Keep committed payload.bin and prior WRITING meta.json intact; finalizeImport or recovery will adopt it.
        }
        entry.stage.set(Stage.Importing)
        parts.scope().launch(parts.io()) {
            finalizeImport(entry, dir, finalFile, total, targetNoteId)
        }
    }

    /**
     * Streams pipe bytes into `payload.tmp`, fsyncs, and atomically commits `payload.bin`.
     * Returns byte count on clean commit, or cleans up `payload.tmp` via [abortEntry] and returns null on stream abort.
     */
    @Suppress("TooGenericExceptionCaught") // pipe thread boundary: any stream failure before commit aborts staging
    private fun stagePipeToPayload(
        entry: ActiveEntry,
        dir: File,
        readPfd: ParcelFileDescriptor,
        finalFile: File,
        currentLimits: ImportLimits,
    ): Long? {
        val root = parts.grantsRoot().apply { mkdirs() }
        val tmp = File(dir, PAYLOAD_TMP)
        return try {
            if (currentLimits.usableSpace(root) < currentLimits.minFreeBytes) {
                throw IOException("Insufficient free space before staging")
            }
            // Do not wrap FileInputStream(readPfd.fileDescriptor) in .use: on failure, abortEntry must delete the
            // staging directory BEFORE closing readPfd so any writer observing EPIPE sees cleanup already complete.
            val input = FileInputStream(readPfd.fileDescriptor)
            val total = FileOutputStream(tmp).use { out ->
                copyBounded(input, out, root, currentLimits, entry.cancelled).also { out.fd.sync() }
            }
            if (entry.cancelled.get()) throw IOException("Cancelled")
            runCatching { readPfd.close() }
            atomicMove(tmp, finalFile)
            total
        } catch (_: Throwable) {
            abortEntry(entry, dir, readPfd)
            null
        }
    }

    private fun copyBounded(
        input: InputStream,
        out: FileOutputStream,
        root: File,
        currentLimits: ImportLimits,
        cancelled: AtomicBoolean,
    ): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            ensureNotCancelled(cancelled)
            val read = input.read(buffer)
            if (read < 0) break
            ensureChunkAllowed(read, total, root, currentLimits, cancelled)
            out.write(buffer, 0, read)
            total += read
        }
        return total
    }

    private fun ensureNotCancelled(cancelled: AtomicBoolean) {
        if (cancelled.get()) throw IOException("Staging cancelled")
    }

    private fun ensureChunkAllowed(
        read: Int,
        total: Long,
        root: File,
        currentLimits: ImportLimits,
        cancelled: AtomicBoolean,
    ) {
        val failure = when {
            cancelled.get() -> "Staging cancelled"
            total + read > currentLimits.maxBytes -> "Import exceeds cap (${currentLimits.maxBytes})"
            currentLimits.usableSpace(root) < currentLimits.minFreeBytes + read -> "Insufficient free space"
            else -> null
        }
        if (failure != null) throw IOException(failure)
    }

    private suspend fun finalizeImport(
        entry: ActiveEntry,
        dir: File,
        payload: File,
        totalBytes: Long,
        targetNoteId: String,
    ) {
        val classified = try {
            classifyImportOutcome(parts.importLiveFile(payload, entry.displayName, targetNoteId))
        } catch (cancelled: CancellationException) {
            active.remove(entry.token, entry)
            entry.completed.completeExceptionally(cancelled)
            throw cancelled
        }
        withContext(NonCancellable) {
            val catalogue = if (classified is GrantImportResult.Accepted) parts.awaitCatalogue() else null
            val lifecycle = if (classified is GrantImportResult.Accepted && catalogue != null) {
                parts.noteLifecycle(catalogue, classified.noteId)
            } else {
                NoteLifecycle.PURGED
            }
            recoveryMutex.withLock {
                commitFinalizedEntryLocked(
                    entry = entry,
                    dir = dir,
                    payload = payload,
                    totalBytes = totalBytes,
                    outcome = classified to lifecycle,
                )
            }
        }
    }

    private fun commitFinalizedEntryLocked(
        entry: ActiveEntry,
        dir: File,
        payload: File,
        totalBytes: Long,
        outcome: Pair<GrantImportResult, NoteLifecycle>,
    ) {
        val (classified, lifecycle) = outcome
        when (classified) {
            is GrantImportResult.Accepted -> commitAcceptedEntryLocked(
                entry = entry,
                dir = dir,
                payload = payload,
                totalBytes = totalBytes,
                accepted = classified.noteId to lifecycle,
            )
            GrantImportResult.RetryableFailure -> {
                // Retain persisted IMPORTING metadata and payload.bin so a subsequent retry or restart imports it.
                active.remove(entry.token, entry)
                entry.finish(null)
            }
            GrantImportResult.PermanentRejection -> {
                deleteGrantDir(dir)
                active.remove(entry.token, entry)
                entry.finish(null)
            }
        }
    }

    private fun commitAcceptedEntryLocked(
        entry: ActiveEntry,
        dir: File,
        payload: File,
        totalBytes: Long,
        accepted: Pair<String, NoteLifecycle>,
    ) {
        val (noteId, lifecycle) = accepted
        val presence = probeRegularFile(payload)
        if (lifecycle != NoteLifecycle.PURGED && presence == FilePresence.REGULAR_FILE) {
            payload.setWritable(false)
            val record = entry.toRecord(PersistedState.ACCEPTED, totalBytes, parts.clock(), noteId)
            try {
                writeMeta(dir, record)
            } catch (_: IOException) {
                // Keep payload.bin on disk; recovery will adopt noteId and rewrite ACCEPTED metadata on next pass.
            }
            cacheAccepted(record)
            active.remove(entry.token, entry)
            entry.finish(record)
        } else {
            if (lifecycle == NoteLifecycle.PURGED || presence == FilePresence.ABSENT) {
                deleteGrantDir(dir)
            }
            active.remove(entry.token, entry)
            entry.finish(null)
        }
    }

    private fun abortEntry(entry: ActiveEntry, dir: File, readPfd: ParcelFileDescriptor) {
        deleteGrantDir(dir)
        active.remove(entry.token, entry)
        entry.finish(null)
        runCatching { readPfd.close() }
    }

    private fun restoreActiveFromDisk(token: String): ActiveEntry? {
        val dir = grantDir(token)
        val meta = (readMeta(dir) as? MetaRead.Valid)?.record ?: return null
        return when (meta.state) {
            PersistedState.AWAITING_WRITE -> {
                val restored = ActiveEntry(
                    token = meta.token,
                    documentId = "$PREFIX${meta.token}",
                    displayName = meta.displayName,
                    mimeType = meta.mimeType,
                    createdAt = meta.createdAt,
                )
                active.putIfAbsent(token, restored) ?: restored.also { unwrittenOrder.addLast(token) }
            }
            PersistedState.WRITING -> {
                // Only delete a dead-process WRITING directory when payload.bin is proven absent (never committed).
                if (!active.containsKey(token) && probeRegularFile(payloadFile(token)) == FilePresence.ABSENT) {
                    deleteGrantDir(dir)
                }
                null
            }
            PersistedState.IMPORTING, PersistedState.ACCEPTED -> null
        }
    }

    private suspend fun loadAcceptedOrRecover(token: String, catalogue: Catalogue): GrantRecord? =
        recoveryMutex.withLock {
            val cached = acceptedCache[token]
            if (cached != null) {
                when (probeRegularFile(payloadFile(cached))) {
                    FilePresence.REGULAR_FILE -> return@withLock cached
                    FilePresence.IO_ERROR -> return@withLock null
                    FilePresence.ABSENT -> evictAccepted(token)
                }
            }
            loadFromDiskOrRecoverLocked(token, grantDir(token), catalogue)
        }

    private suspend fun loadFromDiskOrRecoverLocked(token: String, dir: File, catalogue: Catalogue): GrantRecord? {
        val payload = payloadFile(token)
        return when (val meta = readMeta(dir)) {
            MetaRead.IoFailure -> null
            MetaRead.Missing, MetaRead.Corrupt ->
                if (probeRegularFile(payload) == FilePresence.REGULAR_FILE) {
                    recoverSynthesizedLocked(token, dir, payload, catalogue)
                } else {
                    null
                }
            is MetaRead.Valid -> loadValidMetaOrRecoverLocked(meta.record, dir, payload, catalogue)
        }
    }

    private suspend fun loadValidMetaOrRecoverLocked(
        meta: GrantRecord,
        dir: File,
        payload: File,
        catalogue: Catalogue,
    ): GrantRecord? = when (meta.state) {
        PersistedState.ACCEPTED -> when (probeRegularFile(payload)) {
            FilePresence.REGULAR_FILE -> meta.also(::cacheAccepted)
            FilePresence.IO_ERROR -> null
            FilePresence.ABSENT -> {
                deleteGrantDir(dir)
                null
            }
        }
        PersistedState.IMPORTING -> recoverLocked(meta, dir, catalogue)
        PersistedState.WRITING ->
            if (probeRegularFile(payload) == FilePresence.REGULAR_FILE) {
                promoteFinishedWritingLocked(meta, dir, payload, catalogue)
            } else {
                null
            }
        PersistedState.AWAITING_WRITE -> null
    }

    private suspend fun promoteFinishedWritingLocked(
        meta: GrantRecord,
        dir: File,
        payload: File,
        catalogue: Catalogue,
    ): GrantRecord? {
        val size = Files.size(payload.toPath())
        val promoted = meta.copy(
            state = PersistedState.IMPORTING,
            size = size,
            updatedAt = parts.clock(),
            noteId = meta.noteId ?: UUID.randomUUID().toString(),
        )
        writeMeta(dir, promoted)
        return recoverLocked(promoted, dir, catalogue)
    }

    private suspend fun recoverSynthesizedLocked(
        token: String,
        dir: File,
        payload: File,
        catalogue: Catalogue,
    ): GrantRecord? {
        val now = parts.clock()
        val synthesized = GrantRecord(
            token = token,
            displayName = FALLBACK_DISPLAY_NAME,
            mimeType = DEFAULT_MIME,
            createdAt = now,
            updatedAt = now,
            state = PersistedState.IMPORTING,
            size = Files.size(payload.toPath()),
            noteId = UUID.randomUUID().toString(),
        )
        writeMeta(dir, synthesized)
        return recoverLocked(synthesized, dir, catalogue)
    }

    private suspend fun recoverLocked(meta: GrantRecord, dir: File, catalogue: Catalogue): GrantRecord? {
        val file = payloadFile(meta)
        return when (probeRegularFile(file)) {
            FilePresence.ABSENT -> {
                deleteGrantDir(dir)
                null
            }
            FilePresence.IO_ERROR -> null
            FilePresence.REGULAR_FILE -> recoverExistingPayloadLocked(meta, dir, file, catalogue)
        }
    }

    private suspend fun recoverExistingPayloadLocked(
        meta: GrantRecord,
        dir: File,
        file: File,
        catalogue: Catalogue,
    ): GrantRecord? {
        val prepared = ensureTargetNoteId(meta, dir)
        val targetNoteId = checkNotNull(prepared.noteId)
        val existingRow = catalogue.database.notes().note(targetNoteId)
        return if (existingRow != null) {
            backfillDigestIfMissing(catalogue, existingRow, file)
            acceptGrantLocked(prepared, dir, file, targetNoteId)
        } else {
            importAndAcceptOrRetainLocked(prepared, dir, file, targetNoteId, catalogue)
        }
    }

    private fun ensureTargetNoteId(meta: GrantRecord, dir: File): GrantRecord =
        if (meta.noteId != null) {
            meta
        } else {
            meta.copy(noteId = UUID.randomUUID().toString(), updatedAt = parts.clock()).also { writeMeta(dir, it) }
        }

    private suspend fun backfillDigestIfMissing(catalogue: Catalogue, row: NoteEntity, file: File) {
        if (row.sourceSha256 != null) return
        try {
            val sha256 = withContext(parts.io()) { Sha256.of(file) }
            catalogue.database.notes().setSourceSha256IfMissing(row.id, sha256)
        } catch (_: IOException) {
            // Best-effort digest backfill; the reconstructed row is already live.
        } catch (_: SQLException) {
            // Best-effort digest backfill; the reconstructed row is already live.
        }
    }

    private suspend fun importAndAcceptOrRetainLocked(
        prepared: GrantRecord,
        dir: File,
        file: File,
        targetNoteId: String,
        catalogue: Catalogue,
    ): GrantRecord? {
        val outcome = parts.importRecoveredFile(catalogue, file, prepared.displayName, targetNoteId)
        return when (val classified = classifyImportOutcome(outcome)) {
            is GrantImportResult.Accepted ->
                if (probeRegularFile(file) == FilePresence.REGULAR_FILE) {
                    acceptGrantLocked(prepared, dir, file, classified.noteId)
                } else {
                    null
                }
            GrantImportResult.RetryableFailure -> null
            GrantImportResult.PermanentRejection -> {
                deleteGrantDir(dir)
                null
            }
        }
    }

    private fun acceptGrantLocked(meta: GrantRecord, dir: File, file: File, noteId: String): GrantRecord {
        file.setWritable(false)
        return meta.copy(state = PersistedState.ACCEPTED, updatedAt = parts.clock(), noteId = noteId).also { done ->
            try {
                writeMeta(dir, done)
            } catch (_: IOException) {
                // Keep payload.bin intact; next startup/query re-runs acceptGrantLocked over the adopted noteId.
            }
            cacheAccepted(done)
        }
    }

    private fun cacheAccepted(record: GrantRecord) {
        if (acceptedCache.put(record.token, record) == null) {
            acceptedOrder.addLast(record.token)
        }
        while (acceptedOrder.size > MAX_CACHED_COMPLETED) {
            val evicted = acceptedOrder.pollFirst() ?: break
            acceptedCache.remove(evicted)
        }
    }

    private fun evictAccepted(token: String) {
        if (acceptedCache.remove(token) != null) {
            acceptedOrder.remove(token)
        }
    }

    private fun trimUnwrittenPlaceholders() {
        var guard = unwrittenOrder.size
        while (unwrittenCount() > MAX_UNWRITTEN_ENTRIES && guard-- > 0) {
            val oldest = unwrittenOrder.pollFirst()
            if (oldest != null) {
                val entry = active[oldest]
                if (entry != null && entry.stage.compareAndSet(Stage.AwaitingWrite, Stage.Rejected)) {
                    active.remove(oldest, entry)
                    entry.completed.complete(null)
                    deleteGrantDir(grantDir(oldest))
                }
            }
        }
    }

    private fun unwrittenCount(): Int = active.values.count { it.stage.get() == Stage.AwaitingWrite }

    private fun sweepAbandoned(now: Long) {
        val cutoff = now - STALE_UNWRITTEN_MS
        sweepActiveAbandoned(cutoff)
        sweepDiskAbandoned(cutoff)
    }

    private fun sweepActiveAbandoned(cutoff: Long) {
        for ((token, current) in active.entries.toList()) {
            if (current.createdAt < cutoff && current.stage.compareAndSet(Stage.AwaitingWrite, Stage.Rejected)) {
                active.remove(token, current)
                current.completed.complete(null)
                deleteGrantDir(grantDir(token))
            }
        }
    }

    private fun sweepDiskAbandoned(cutoff: Long) {
        val root = parts.grantsRoot()
        if (!root.isDirectory) return
        for (dir in root.listFiles().orEmpty()) {
            val token = dir.name
            if (NoteIds.isValid(token) && !active.containsKey(token) && isStalePlaceholderDir(token, dir, cutoff)) {
                deleteGrantDir(dir)
            }
        }
    }

    private fun isStalePlaceholderDir(token: String, dir: File, cutoff: Long): Boolean {
        val meta = (readMeta(dir) as? MetaRead.Valid)?.record ?: return false
        return meta.state == PersistedState.AWAITING_WRITE &&
            meta.createdAt < cutoff &&
            probeRegularFile(payloadFile(token)) == FilePresence.ABSENT
    }

    private fun deleteGrantDir(dir: File): Boolean {
        if (!dir.exists()) return true
        dir.listFiles()?.forEach { it.setWritable(true) }
        return dir.deleteRecursively() || !dir.exists()
    }

    private fun isWriterClosed(readFd: FileDescriptor): Boolean {
        if (!readFd.valid()) return true
        // Under Robolectric on the JVM, android.system.Os.poll is an unimplemented stub that returns 0 (revents = 0)
        // without throwing, so consult the FIFO shadow probe first when installed.
        val probe = writerClosedProbeForTesting
        return if (probe != null) {
            probe(readFd)
        } else {
            pollPeerHup(readFd)
        }
    }

    private fun pollPeerHup(readFd: FileDescriptor): Boolean = try {
        val pollFd = StructPollfd().apply {
            fd = readFd
            events = OsConstants.POLLIN.toShort()
        }
        Os.poll(arrayOf(pollFd), 0)
        val closedMask = OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL
        (pollFd.revents.toInt() and closedMask) != 0
    } catch (_: ErrnoException) {
        true
    }

    private fun grantDir(token: String): File = File(parts.grantsRoot(), token)

    private fun payloadFile(record: GrantRecord): File = payloadFile(record.token)

    private fun payloadFile(token: String): File = File(grantDir(token), PAYLOAD_FILE)

    private fun tokenOf(documentId: String): String? {
        if (!documentId.startsWith(PREFIX)) return null
        return documentId.removePrefix(PREFIX).takeIf(NoteIds::isValid)
    }

    private fun cleanDisplayName(rawName: String?, mimeType: String?): String {
        val trimmed = rawName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
        val dot = trimmed.lastIndexOf('.')
        val rawExt = if (dot > 0 && dot < trimmed.lastIndex) trimmed.substring(dot + 1) else ""
        val ext = rawExt.lowercase(Locale.ROOT).takeIf { VALID_EXT.matches(it) } ?: defaultExtension(mimeType)
        val title = if (dot > 0) trimmed.substring(0, dot) else trimmed
        return ExportNames.file(title, ext)
    }

    private fun defaultExtension(mimeType: String?): String = when (mimeType?.lowercase(Locale.ROOT)) {
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/m4a", "audio/aac" -> "m4a"
        "audio/flac", "audio/x-flac" -> "flac"
        else -> "opus"
    }

    private fun writeMeta(dir: File, record: GrantRecord) {
        val json = JSONObject()
            .put("version", META_VERSION)
            .put("token", record.token)
            .put("displayName", record.displayName)
            .put("mimeType", record.mimeType)
            .put("createdAt", record.createdAt)
            .put("updatedAt", record.updatedAt)
            .put("state", record.state.name)
            .put("size", record.size)
            .apply { if (record.noteId != null) put("noteId", record.noteId) }
            .toString()
        val tmp = File(dir, META_TMP)
        FileOutputStream(tmp).use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        atomicMove(tmp, File(dir, META_FILE))
    }

    private fun readMeta(dir: File): MetaRead {
        val file = File(dir, META_FILE)
        return try {
            CassiniDocumentsProvider.metaReadFaultForTesting?.invoke(file)
            val rawText = String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
            parseGrantJson(dir.name, JSONObject(rawText))?.let(MetaRead::Valid) ?: MetaRead.Corrupt
        } catch (_: NoSuchFileException) {
            MetaRead.Missing
        } catch (_: IOException) {
            MetaRead.IoFailure
        } catch (_: SecurityException) {
            MetaRead.IoFailure
        } catch (_: JSONException) {
            MetaRead.Corrupt
        }
    }

    private fun probeRegularFile(file: File): FilePresence = try {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (attrs.isRegularFile) FilePresence.REGULAR_FILE else FilePresence.ABSENT
    } catch (_: NoSuchFileException) {
        FilePresence.ABSENT
    } catch (_: IOException) {
        FilePresence.IO_ERROR
    } catch (_: SecurityException) {
        FilePresence.IO_ERROR
    }

    private fun parseGrantJson(expectedToken: String, json: JSONObject): GrantRecord? {
        val token = json.getString("token").takeIf { it == expectedToken && NoteIds.isValid(it) }
        val displayName = json.getString("displayName").takeIf(::isValidDisplayName)
        val state = PersistedState.entries.firstOrNull { it.name == json.getString("state") }
        val (noteId, noteIdValid) = parseOptionalNoteId(json, state)
        val headerValid = token != null && displayName != null && state != null
        return if (headerValid && noteIdValid) {
            GrantRecord(
                token = checkNotNull(token),
                displayName = checkNotNull(displayName),
                mimeType = json.optString("mimeType", DEFAULT_MIME),
                createdAt = json.getLong("createdAt"),
                updatedAt = json.optLong("updatedAt", json.getLong("createdAt")),
                state = checkNotNull(state),
                size = json.optLong("size", 0L),
                noteId = noteId,
            )
        } else {
            null
        }
    }

    private fun parseOptionalNoteId(json: JSONObject, state: PersistedState?): Pair<String?, Boolean> {
        val hasNoteId = json.has("noteId") && !json.isNull("noteId")
        val rawNoteId = if (hasNoteId) json.getString("noteId") else null
        val noteId = rawNoteId?.takeIf(NoteIds::isValid)
        val presentWhenRequired = state != PersistedState.ACCEPTED || noteId != null
        val validIfPresent = !hasNoteId || noteId != null
        return noteId to (validIfPresent && presentWhenRequired)
    }

    private fun isValidDisplayName(name: String): Boolean =
        name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != ".."

    private fun atomicMove(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private sealed interface MetaRead {
        data class Valid(val record: GrantRecord) : MetaRead
        data object Missing : MetaRead
        data object Corrupt : MetaRead
        data object IoFailure : MetaRead
    }

    private enum class PersistedState {
        AWAITING_WRITE,
        WRITING,
        IMPORTING,
        ACCEPTED,
    }

    private data class GrantRecord(
        val token: String,
        val displayName: String,
        val mimeType: String,
        val createdAt: Long,
        val updatedAt: Long,
        val state: PersistedState,
        val size: Long,
        val noteId: String? = null,
    ) {
        fun toExposedDocument(): ExposedDocument = ExposedDocument(
            documentId = "$PREFIX$token",
            displayName = displayName,
            mimeType = mimeType,
            size = size,
            lastModified = updatedAt,
            flags = 0,
        )
    }

    private sealed interface Stage {
        data object AwaitingWrite : Stage
        class Writing(val readFd: FileDescriptor) : Stage
        data object Importing : Stage
        class Accepted(val record: GrantRecord) : Stage
        data object Rejected : Stage
    }

    private class ActiveEntry(
        val token: String,
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val createdAt: Long,
    ) {
        val stage = AtomicReference<Stage>(Stage.AwaitingWrite)
        val cancelled = AtomicBoolean(false)
        val completed = CompletableDeferred<GrantRecord?>()

        fun toRecord(state: PersistedState, size: Long, updatedAt: Long, noteId: String? = null): GrantRecord =
            GrantRecord(
                token = token,
                displayName = displayName,
                mimeType = mimeType,
                createdAt = createdAt,
                updatedAt = updatedAt,
                state = state,
                size = size,
                noteId = noteId,
            )

        fun finish(record: GrantRecord?) {
            stage.set(if (record != null) Stage.Accepted(record) else Stage.Rejected)
            completed.complete(record)
        }
    }

    companion object {
        const val PREFIX = "pending:"
        const val GRANTS_DIR = "provider-grants"
        internal const val PAYLOAD_FILE = "payload.bin"
        private const val META_VERSION = 1
        private const val META_FILE = "meta.json"
        private const val META_TMP = "meta.json.tmp"
        private const val PAYLOAD_TMP = "payload.tmp"
        private const val DEFAULT_MIME = "audio/ogg"
        private const val FALLBACK_DISPLAY_NAME = "Imported audio.opus"
        private const val BUFFER_BYTES = 8_192
        private const val THREAD_TOKEN_LEN = 8
        internal const val MAX_CACHED_COMPLETED = 128
        private const val MAX_UNWRITTEN_ENTRIES = 128
        internal const val STALE_UNWRITTEN_MS = 60L * 60L * 1000L
        private val VALID_EXT = Regex("[a-z0-9]{1,5}")

        /** Robolectric fallback when `Os.poll` is unshadowed on the JVM. */
        @Volatile
        internal var writerClosedProbeForTesting: ((FileDescriptor) -> Boolean)? = null

        /** Binds a per-process [PendingImports] instance to [graph]. */
        fun forGraph(context: Context, graph: AppGraph): PendingImports {
            val app = context.applicationContext
            fun notifyChildrenChanged() {
                val authority = "${app.packageName}${CassiniDocumentsProvider.AUTHORITY_SUFFIX}"
                val uri = DocumentsContract.buildChildDocumentsUri(authority, DocumentsVault.ROOT_DOCUMENT_ID)
                app.contentResolver.notifyChange(uri, null)
            }
            return PendingImports(
                Parts(
                    grantsRoot = { File(graph.paths.root, GRANTS_DIR) },
                    scope = { graph.appScope },
                    io = { graph.dispatchers.io },
                    clock = graph.config.clock,
                    limits = { CassiniDocumentsProvider.limitsForTesting ?: graph.config.importLimits },
                    ready = { graph.ready },
                    awaitCatalogue = { graph.awaitCatalogue() },
                    importLiveFile = { file, displayName, targetNoteId ->
                        graph.ready.await()
                        val outcome = graph.notices.imports.track(displayName) {
                            val catalogue = graph.awaitCatalogue()
                            catalogue.imports.import(Uri.fromFile(file), displayName, targetNoteId)
                        }
                        if (outcome is ImportOutcome.Created || outcome is ImportOutcome.Existing) {
                            notifyChildrenChanged()
                        }
                        outcome
                    },
                    importRecoveredFile = { catalogue, file, displayName, targetNoteId ->
                        val outcome = graph.notices.imports.track(displayName) {
                            catalogue.imports.import(Uri.fromFile(file), displayName, targetNoteId)
                        }
                        if (outcome is ImportOutcome.Created || outcome is ImportOutcome.Existing) {
                            notifyChildrenChanged()
                        }
                        outcome
                    },
                    noteLifecycle = { catalogue, noteId ->
                        val row = catalogue.database.notes().note(noteId)
                        when {
                            row != null && row.trashedAt != null -> NoteLifecycle.TRASHED
                            row != null -> NoteLifecycle.LIVE
                            noteDirHasAudioOrUnreadable(graph.paths.note(noteId)) -> NoteLifecycle.UNCATALOGUED
                            else -> NoteLifecycle.PURGED
                        }
                    },
                ),
            )
        }

        private fun classifyImportOutcome(outcome: ImportOutcome?): GrantImportResult = when (outcome) {
            is ImportOutcome.Created -> GrantImportResult.Accepted(outcome.noteId)
            is ImportOutcome.Existing -> GrantImportResult.Accepted(outcome.noteId)
            is ImportOutcome.Rejected -> if (outcome.retryable) {
                GrantImportResult.RetryableFailure
            } else {
                GrantImportResult.PermanentRejection
            }
            null -> GrantImportResult.RetryableFailure
        }

        private fun noteDirHasAudioOrUnreadable(noteDir: File): Boolean = try {
            Files.list(noteDir.toPath()).use { entries ->
                entries.anyMatch { path -> isNoteAudioFileName(path.fileName?.toString().orEmpty()) }
            }
        } catch (_: NoSuchFileException) {
            false
        } catch (_: NotDirectoryException) {
            false
        } catch (_: IOException) {
            // Operational stat/read error on notes/<noteId>: treat as UNCATALOGUED rather than PURGED.
            true
        } catch (_: SecurityException) {
            true
        }

        private fun isNoteAudioFileName(name: String): Boolean =
            name == DOCUMENT_FILE ||
                ((name.startsWith(CAPTURE_PREFIX) || name.startsWith(SOURCE_PREFIX)) && !name.endsWith(TMP_SUFFIX))

        private const val DOCUMENT_FILE = "document.opus"
        private const val CAPTURE_PREFIX = "capture."
        private const val SOURCE_PREFIX = "source."
        private const val TMP_SUFFIX = ".tmp"
    }
}
