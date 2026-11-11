package io.codemyriad.cassini.documents

import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import io.codemyriad.cassini.core.export.ExportNames
import io.codemyriad.cassini.data.NoteIds
import io.codemyriad.cassini.data.notes.ImportLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Persistent creation grants allocated through [CassiniDocumentsProvider.createDocument].
 *
 * Each `createDocument` allocates a stable `pending:<uuid>` document ID persisted under
 * `filesDir/provider-grants/<uuid>/meta.json` (`state = AWAITING_WRITE`). Opening for write returns the write end of a
 * kernel pipe (`ParcelFileDescriptor.createPipe()`) while a background pump streams incoming bytes into `payload.tmp`,
 * enforcing [ImportLimits.maxBytes] and [ImportLimits.minFreeBytes] **during streaming** before writer close and
 * following real kernel descriptor EOF (so native clients that call `detachFd()` keep writing safely until they close
 * the detached descriptor).
 *
 * On clean EOF the submitted bytes are moved to `<uuid>/<displayName>` (read-only) and handed to `NoticeCenter.imports`
 * (`ImportRepository`, with internal SHA-256 deduplication). Accepted grants retain **only** the submitted bytes and
 * their own metadata on disk — never resolving to or exposing an existing note's mutable transcript, speaker names or
 * title — and keep their `pending:<uuid>` identity across provider recreation, restarts and cache eviction.
 */
@Suppress("TooManyFunctions", "LargeClass") // grant persistence, bounded pipe pump, cleanup and metadata queries
internal class PendingImports(
    private val grantsRoot: () -> File,
    private val scope: () -> CoroutineScope,
    private val io: () -> CoroutineDispatcher,
    private val clock: () -> Long,
    private val limits: () -> ImportLimits,
    private val importFileUri: suspend (String) -> Boolean,
) {
    private val active = ConcurrentHashMap<String, ActiveEntry>()
    private val unwrittenOrder = ConcurrentLinkedDeque<String>()
    private val acceptedCache = ConcurrentHashMap<String, GrantRecord>()
    private val acceptedOrder = ConcurrentLinkedDeque<String>()

    /** Allocates and persists a write-once `pending:<uuid>` document under `"root"`. */
    fun create(mimeType: String?, displayName: String?): String {
        val now = clock()
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
                dir.deleteRecursively()
            }
        }
        unwrittenOrder.addLast(token)
        trimUnwrittenPlaceholders()
        return id
    }

    /** True when [documentId] is a live unwritten, in-progress, or accepted creation grant. */
    fun isKnown(documentId: String): Boolean {
        val token = tokenOf(documentId) ?: return false
        val inMemory = active[token]?.let { it.stage.get() !is Stage.Rejected }
            ?: acceptedCache[token]?.let { payloadFile(it).isFile }
        return inMemory ?: isKnownOnDisk(token)
    }

    private fun isKnownOnDisk(token: String): Boolean = when (val meta = readMeta(grantDir(token))) {
        null -> false
        else -> when (meta.state) {
            PersistedState.AWAITING_WRITE -> true
            PersistedState.ACCEPTED, PersistedState.IMPORTING -> payloadFile(meta).isFile
            PersistedState.WRITING -> false
        }
    }

    /** Returns stable metadata for [documentId] (`COLUMN_DOCUMENT_ID` always stays `pending:<uuid>`). */
    suspend fun inspect(documentId: String): ExposedDocument {
        val token = tokenOf(documentId) ?: throw FileNotFoundException("Unknown document: $documentId")
        val entry = active[token] ?: restoreActiveFromDisk(token)
        if (entry != null) {
            return inspectActive(entry, documentId)
        }
        val accepted = loadAcceptedOrRecover(token)
            ?: throw FileNotFoundException("Unknown document: $documentId")
        return accepted.toExposedDocument()
    }

    private suspend fun inspectActive(entry: ActiveEntry, documentId: String): ExposedDocument =
        when (val stage = entry.stage.get()) {
            Stage.AwaitingWrite -> ExposedDocument(
                documentId = entry.documentId,
                displayName = entry.displayName,
                mimeType = entry.mimeType,
                size = 0L,
                lastModified = entry.createdAt,
                flags = Document.FLAG_SUPPORTS_WRITE,
            )
            is Stage.Writing -> if (!isWriterClosed(stage.readFd)) {
                ExposedDocument(
                    documentId = entry.documentId,
                    displayName = entry.displayName,
                    mimeType = entry.mimeType,
                    size = null,
                    lastModified = entry.createdAt,
                    flags = 0,
                )
            } else {
                awaitAcceptedRecord(entry, documentId, null).toExposedDocument()
            }
            Stage.Importing, is Stage.Accepted, Stage.Rejected ->
                awaitAcceptedRecord(entry, documentId, null).toExposedDocument()
        }

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
        writeMeta(dir, entry.toRecord(PersistedState.WRITING, 0L, clock()))
        signal?.setOnCancelListener {
            entry.cancelled.set(true)
            runCatching { readPfd.close() }
        }
        startPumpThread(entry, dir, readPfd, limits())
        return writePfd
    }

    private fun requireWritableEntry(documentId: String): ActiveEntry {
        val token = tokenOf(documentId)
        val entry = token?.let { active[it] ?: restoreActiveFromDisk(it) }
        if (entry != null) return entry
        val alreadyAccepted = token != null &&
            (acceptedCache.containsKey(token) || readMeta(grantDir(token))?.state == PersistedState.ACCEPTED)
        throw if (alreadyAccepted) {
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
        val record = resolveReadableRecord(documentId, signal)
        signal?.throwIfCanceled()
        val file = payloadFile(record).takeIf(File::isFile)
            ?: throw FileNotFoundException("Payload missing for $documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private suspend fun resolveReadableRecord(documentId: String, signal: CancellationSignal?): GrantRecord {
        val token = tokenOf(documentId) ?: throw FileNotFoundException("Unknown document: $documentId")
        val entry = active[token] ?: restoreActiveFromDisk(token)
        val resolved = if (entry != null) {
            if (entry.stage.get() == Stage.AwaitingWrite) null else awaitAcceptedRecord(entry, documentId, signal)
        } else {
            loadAcceptedOrRecover(token)
        }
        return resolved ?: throw FileNotFoundException("Unknown or unwritten document: $documentId")
    }

    private suspend fun awaitAcceptedRecord(
        entry: ActiveEntry,
        documentId: String,
        signal: CancellationSignal?,
    ): GrantRecord {
        val outcome = withContext(io()) {
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
        deferred: CompletableDeferred<T>,
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
        val root = grantsRoot().apply { mkdirs() }
        val tmp = File(dir, PAYLOAD_TMP)
        var total = 0L
        var failed = false
        try {
            if (currentLimits.usableSpace(root) < currentLimits.minFreeBytes) {
                throw IOException("Insufficient free space before staging")
            }
            // Do not wrap FileInputStream(readPfd.fileDescriptor) in .use: on failure, abortEntry must delete the
            // staging directory BEFORE closing readPfd so any writer observing EPIPE sees cleanup already complete.
            val input = FileInputStream(readPfd.fileDescriptor)
            FileOutputStream(tmp).use { out ->
                total = copyBounded(input, out, root, currentLimits, entry.cancelled)
                out.fd.sync()
            }
            if (entry.cancelled.get()) throw IOException("Cancelled")
            runCatching { readPfd.close() }
            val finalFile = File(dir, entry.displayName)
            atomicMove(tmp, finalFile)
            writeMeta(dir, entry.toRecord(PersistedState.IMPORTING, total, clock()))
            entry.stage.set(Stage.Importing)
        } catch (_: Throwable) {
            failed = true
        }
        if (failed) {
            // Delete staged files BEFORE closing readPfd so any writer observing EPIPE sees cleanup already complete.
            abortEntry(entry, dir, readPfd)
            return
        }
        val finalFile = File(dir, entry.displayName)
        scope().launch(io()) {
            finalizeImport(entry, dir, finalFile, total)
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

    private suspend fun finalizeImport(entry: ActiveEntry, dir: File, payload: File, totalBytes: Long) {
        val accepted = runCatching { importFileUri(Uri.fromFile(payload).toString()) }.getOrDefault(false)
        withContext(NonCancellable) {
            if (accepted && payload.isFile) {
                payload.setWritable(false)
                val record = entry.toRecord(PersistedState.ACCEPTED, totalBytes, clock())
                writeMeta(dir, record)
                cacheAccepted(record)
                active.remove(entry.token, entry)
                entry.finish(record)
            } else {
                dir.deleteRecursively()
                active.remove(entry.token, entry)
                entry.finish(null)
            }
        }
    }

    private fun abortEntry(entry: ActiveEntry, dir: File, readPfd: ParcelFileDescriptor) {
        dir.deleteRecursively()
        active.remove(entry.token, entry)
        entry.finish(null)
        runCatching { readPfd.close() }
    }

    private fun restoreActiveFromDisk(token: String): ActiveEntry? {
        val dir = grantDir(token)
        val meta = readMeta(dir) ?: return null
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
                // An on-disk WRITING state with no in-memory entry belongs to a dead process whose pipe is gone.
                dir.deleteRecursively()
                null
            }
            PersistedState.IMPORTING, PersistedState.ACCEPTED -> null
        }
    }

    private suspend fun loadAcceptedOrRecover(token: String): GrantRecord? {
        val cached = acceptedCache[token]?.takeIf { payloadFile(it).isFile }
        if (cached != null) return cached
        acceptedCache.remove(token)
        val dir = grantDir(token)
        val meta = readMeta(dir)
        val file = meta?.let(::payloadFile)
        return when {
            meta == null || file == null -> null
            meta.state == PersistedState.ACCEPTED && file.isFile -> meta.also(::cacheAccepted)
            meta.state == PersistedState.IMPORTING && file.isFile -> recoverInterruptedImport(meta, dir, file)
            meta.state == PersistedState.IMPORTING -> {
                dir.deleteRecursively()
                null
            }
            else -> null
        }
    }

    private suspend fun recoverInterruptedImport(meta: GrantRecord, dir: File, file: File): GrantRecord? {
        val ok = withContext(io()) {
            runCatching { importFileUri(Uri.fromFile(file).toString()) }.getOrDefault(false)
        }
        return if (ok && file.isFile) {
            file.setWritable(false)
            meta.copy(state = PersistedState.ACCEPTED, updatedAt = clock()).also { completed ->
                writeMeta(dir, completed)
                cacheAccepted(completed)
            }
        } else {
            dir.deleteRecursively()
            null
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

    private fun trimUnwrittenPlaceholders() {
        var guard = unwrittenOrder.size
        while (unwrittenCount() > MAX_UNWRITTEN_ENTRIES && guard-- > 0) {
            val oldest = unwrittenOrder.pollFirst()
            if (oldest != null) {
                val entry = active[oldest]
                if (entry != null && entry.stage.get() == Stage.AwaitingWrite && active.remove(oldest, entry)) {
                    grantDir(oldest).deleteRecursively()
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
        val iterator = active.entries.iterator()
        while (iterator.hasNext()) {
            val current = iterator.next().value
            val stage = current.stage.get()
            if ((stage == Stage.AwaitingWrite || stage == Stage.Rejected) && current.createdAt < cutoff) {
                iterator.remove()
                grantDir(current.token).deleteRecursively()
            }
        }
    }

    private fun sweepDiskAbandoned(cutoff: Long) {
        val root = grantsRoot()
        if (!root.isDirectory) return
        for (dir in root.listFiles().orEmpty()) {
            val token = dir.name
            if (NoteIds.isValid(token) && !active.containsKey(token) && isStalePlaceholderDir(dir, cutoff)) {
                dir.deleteRecursively()
            }
        }
    }

    private fun isStalePlaceholderDir(dir: File, cutoff: Long): Boolean {
        val meta = readMeta(dir) ?: return true
        return meta.state == PersistedState.WRITING ||
            (meta.state == PersistedState.AWAITING_WRITE && meta.createdAt < cutoff)
    }

    private fun isWriterClosed(readFd: FileDescriptor): Boolean = try {
        if (!readFd.valid()) {
            true
        } else {
            val pollFd = StructPollfd().apply {
                fd = readFd
                events = OsConstants.POLLIN.toShort()
            }
            Os.poll(arrayOf(pollFd), 0)
            val closedMask = OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL
            (pollFd.revents.toInt() and closedMask) != 0
        }
    } catch (_: Throwable) {
        writerClosedProbeForTesting?.invoke(readFd) ?: true
    }

    private fun grantDir(token: String): File = File(grantsRoot(), token)

    private fun payloadFile(record: GrantRecord): File {
        val dir = grantDir(record.token)
        return File(dir, record.displayName).takeIf { it.parentFile == dir }
            ?: throw FileNotFoundException("Invalid payload path")
    }

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
            .toString()
        val tmp = File(dir, META_TMP)
        FileOutputStream(tmp).use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        atomicMove(tmp, File(dir, META_FILE))
    }

    private fun readMeta(dir: File): GrantRecord? {
        val file = File(dir, META_FILE)
        if (!file.isFile) return null
        return try {
            parseGrantJson(dir.name, JSONObject(file.readText(Charsets.UTF_8)))
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    private fun parseGrantJson(expectedToken: String, json: JSONObject): GrantRecord? {
        val token = json.getString("token").takeIf { it == expectedToken && NoteIds.isValid(it) }
        val displayName = json.getString("displayName").takeIf(::isSafeFileName)
        val state = PersistedState.entries.firstOrNull { it.name == json.getString("state") }
        return if (token != null && displayName != null && state != null) {
            GrantRecord(
                token = token,
                displayName = displayName,
                mimeType = json.optString("mimeType", DEFAULT_MIME),
                createdAt = json.getLong("createdAt"),
                updatedAt = json.optLong("updatedAt", json.getLong("createdAt")),
                state = state,
                size = json.optLong("size", 0L),
            )
        } else {
            null
        }
    }

    private fun isSafeFileName(name: String): Boolean =
        name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != ".." &&
            name != META_FILE && name != META_TMP && name != PAYLOAD_TMP

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

        fun toRecord(state: PersistedState, size: Long, updatedAt: Long): GrantRecord = GrantRecord(
            token = token,
            displayName = displayName,
            mimeType = mimeType,
            createdAt = createdAt,
            updatedAt = updatedAt,
            state = state,
            size = size,
        )

        fun finish(record: GrantRecord?) {
            stage.set(if (record != null) Stage.Accepted(record) else Stage.Rejected)
            completed.complete(record)
        }
    }

    companion object {
        const val PREFIX = "pending:"
        const val GRANTS_DIR = "provider-grants"
        private const val META_VERSION = 1
        private const val META_FILE = "meta.json"
        private const val META_TMP = "meta.json.tmp"
        private const val PAYLOAD_TMP = "payload.tmp"
        private const val DEFAULT_MIME = "audio/ogg"
        private const val BUFFER_BYTES = 8_192
        private const val THREAD_TOKEN_LEN = 8
        internal const val MAX_CACHED_COMPLETED = 128
        private const val MAX_UNWRITTEN_ENTRIES = 128
        internal const val STALE_UNWRITTEN_MS = 60L * 60L * 1000L
        private val VALID_EXT = Regex("[a-z0-9]{1,5}")

        /** Robolectric fallback when `Os.poll` is unshadowed on the JVM. */
        @Volatile
        internal var writerClosedProbeForTesting: ((FileDescriptor) -> Boolean)? = null
    }
}
