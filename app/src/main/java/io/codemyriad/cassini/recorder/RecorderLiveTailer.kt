package io.codemyriad.cassini.recorder

import android.util.Log
import io.codemyriad.cassini.core.recorder.XrecException
import io.codemyriad.cassini.core.recorder.XrecFailure
import io.codemyriad.cassini.core.recorder.XrecLimits
import io.codemyriad.cassini.core.recorder.XrecReply
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Streams an actively-recording `.wav` file from a `live=1` firmware into its `.part` file while recording runs,
 * feeds [LivePrecomputeSession] incrementally, refreshes the finalized 44-byte RIFF header upon stop or 2 GiB split,
 * imports + installs the precompute checkpoint, and waits on the same open RFCOMM session for the transcript so
 * `RecorderSync` can push `<stem>.cwx` immediately before closing Bluetooth.
 */
@Suppress("TooManyFunctions") // Single-purpose helpers for locate, resume verify, chunk pull, finalize, and wait.
internal class RecorderLiveTailer(
    private val host: Host,
) {
    /** Internal bridge to [RecorderSync]'s session, store helpers, and import state updates. */
    interface Host {
        val parts: RecorderSync.Parts
        fun partFile(address: String, name: String): File
        fun prepare(part: File, keep: Long)
        fun progress(step: SyncStep, file: String, done: Long = 0, total: Long = 0)
        fun headSha256(file: File): String
        suspend fun partial(address: String): PartialDownload?
        suspend fun updatePartial(address: String, partial: PartialDownload?)

        @Suppress("LongParameterList") // Carries finalized segment metadata and optional precompute session.
        suspend fun importFinalized(
            address: String,
            name: String,
            size: Long,
            part: File,
            precompute: LivePrecomputeSession?,
            recordedAt: LocalDateTime?,
        ): String?
    }

    /** Result of tailing all consecutive live segments (including 2 GiB splits) until stopped or deferred. */
    data class Outcome(
        val downloaded: Int,
        val deferred: Int,
        val recording: Boolean,
        val deferredWait: Boolean,
        val listing: List<XrecReply.Entry>,
        val status: XrecReply.Status,
    )

    @Suppress("ReturnCount") // Segment loop handles stop, split, and early deferral cleanly.
    suspend fun tail(
        session: RecorderSync.Session,
        address: String,
        pong: XrecReply.Pong,
        initialListing: List<XrecReply.Entry>,
        initialStatus: XrecReply.Status,
    ): Outcome {
        var listing = initialListing
        var status = initialStatus
        var downloaded = 0
        var lastNoteId: String? = null
        var segments = 0
        while (status.recording && (status.recordingIndex ?: 0L) > 0L && segments < MAX_SEGMENTS) {
            segments++
            val result = try {
                tailOneSegment(session, address, pong.listTimestampsSupported, listing, status)
            } catch (error: XrecException) {
                if (error.failure != XrecFailure.LATER) throw error
                SegmentResult(deferred = true, listing = listing, status = status)
            }
            listing = result.listing
            status = result.status
            if (result.downloaded > 0) {
                downloaded += result.downloaded
                lastNoteId = result.noteId
            }
            if (result.deferred) {
                return Outcome(
                    downloaded = downloaded,
                    deferred = 1,
                    recording = true,
                    deferredWait = true,
                    listing = listing,
                    status = status,
                )
            }
            if (result.aborted) {
                return Outcome(
                    downloaded = downloaded,
                    deferred = 0,
                    recording = status.recording,
                    deferredWait = false,
                    listing = listing,
                    status = status,
                )
            }
        }
        if (!status.recording && lastNoteId != null && pong.recordTarget) {
            status = waitForTranscript(session, lastNoteId, status)
        }
        return Outcome(
            downloaded = downloaded,
            deferred = 0,
            recording = status.recording,
            deferredWait = false,
            listing = listing,
            status = status,
        )
    }

    @Suppress("ReturnCount") // Aborts cleanly when recording metadata disappears.
    private suspend fun tailOneSegment(
        session: RecorderSync.Session,
        address: String,
        withTimestamps: Boolean,
        initialListing: List<XrecReply.Entry>,
        initialStatus: XrecReply.Status,
    ): SegmentResult {
        val activeIndex = initialStatus.recordingIndex
            ?: return SegmentResult(aborted = true, listing = initialListing, status = initialStatus)
        val found = locateActiveEntry(session, activeIndex, withTimestamps, initialListing, initialStatus)
            ?: return SegmentResult(aborted = true, listing = initialListing, status = initialStatus)
        val activeName = found.entry.name
        val fallbackStart = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(
                host.parts.clock() - found.status.recordingSeconds.coerceAtLeast(0L) * MILLIS_PER_SECOND,
            ),
            host.parts.zone(),
        ).truncatedTo(ChronoUnit.SECONDS)
        val part = host.partFile(address, activeName)
        val startState = prepareLivePart(
            session = session,
            address = address,
            part = part,
            activeName = activeName,
            flushed = found.status.recordingBytes ?: 0L,
        )
        val precompute = PrecomputeHolder(part, activeName)
        precompute.tryOpen(startState.startOffset)
        return try {
            val stream = streamUntilBoundary(
                session = session,
                address = address,
                part = part,
                activeIndex = activeIndex,
                activeName = activeName,
                initialStatus = found.status,
                startState = startState,
                precompute = precompute,
            )
            if (stream.deferred) {
                SegmentResult(deferred = true, listing = found.listing, status = stream.status)
            } else {
                finalizeSegment(
                    session = session,
                    address = address,
                    part = part,
                    activeName = activeName,
                    withTimestamps = withTimestamps,
                    fallbackStart = fallbackStart,
                    stream = stream,
                    precompute = precompute.current(),
                )
            }
        } finally {
            precompute.close()
        }
    }

    @Suppress("LongParameterList") // Carries session/file cursors through the active polling loop.
    private suspend fun streamUntilBoundary(
        session: RecorderSync.Session,
        address: String,
        part: File,
        activeIndex: Long,
        activeName: String,
        initialStatus: XrecReply.Status,
        startState: LivePartState,
        precompute: PrecomputeHolder,
    ): StreamState {
        var status = initialStatus
        var offset = startState.startOffset
        var liveHeaderPending = startState.liveHeaderPending
        var deferred = false
        while (!deferred && status.recording && status.recordingIndex == activeIndex) {
            if (offset > WAV_HEADER_BYTES) precompute.ensureActive(offset)
            val flushed = status.recordingBytes ?: 0L
            if (flushed > offset && flushed > WAV_HEADER_BYTES) {
                if (!liveHeaderPending) {
                    liveHeaderPending = true
                    host.updatePartial(address, PartialDownload(activeName, flushed, offset, liveHeaderPending = true))
                }
                val step = stepLiveRange(session, address, part, activeName, status, offset, flushed, precompute)
                offset = step.offset
                if (offset > WAV_HEADER_BYTES) precompute.ensureActive(offset)
                deferred = step.deferred
            } else {
                host.parts.wait(host.parts.livePollMillis)
            }
            if (!deferred) status = session.call { stat() }
        }
        return StreamState(status, offset, liveHeaderPending || offset > 0L, deferred)
    }

    @Suppress("LongParameterList") // Single chunk pull and offset/partial-state reconciliation step.
    private suspend fun stepLiveRange(
        session: RecorderSync.Session,
        address: String,
        part: File,
        activeName: String,
        status: XrecReply.Status,
        offset: Long,
        flushed: Long,
        precompute: PrecomputeHolder,
    ): StepResult {
        val req = RangeRequest(
            name = activeName,
            part = part,
            offset = offset,
            // Repoll STAT between batches so Stop releases the recording-speed limit even with a large backlog.
            endOffset = minOf(flushed, offset + LIVE_BATCH_BYTES),
            window = XrecLimits.RECORDING_GETS_WINDOW,
            paceMillis = status.paceMillis ?: 0L,
        )
        return when (val step = pullFlushedRange(session, req)) {
            is PullResult.Appended -> {
                host.updatePartial(
                    address,
                    PartialDownload(activeName, flushed, step.newOffset, liveHeaderPending = true),
                )
                precompute.onBytesAvailable(step.newOffset)
                StepResult(offset = step.newOffset, deferred = false)
            }
            PullResult.Retry -> {
                host.parts.wait(host.parts.livePollMillis)
                StepResult(offset = offset, deferred = false)
            }
            is PullResult.Deferred -> {
                if (step.newOffset > offset) {
                    host.updatePartial(
                        address,
                        PartialDownload(activeName, flushed, step.newOffset, liveHeaderPending = true),
                    )
                }
                StepResult(offset = maxOf(offset, step.newOffset), deferred = true)
            }
        }
    }

    @Suppress("LongParameterList") // Completes tail download, header refresh, import, and precompute install.
    private suspend fun finalizeSegment(
        session: RecorderSync.Session,
        address: String,
        part: File,
        activeName: String,
        withTimestamps: Boolean,
        fallbackStart: LocalDateTime,
        stream: StreamState,
        precompute: LivePrecomputeSession?,
    ): SegmentResult {
        val listing = session.call { list(withTimestamps) }
        val finalEntry = listing.firstOrNull { it.name.equals(activeName, ignoreCase = true) }
        if (finalEntry == null) {
            return SegmentResult(aborted = true, listing = listing, status = stream.status)
        }
        val finalSize = finalEntry.size
        var offset = stream.offset
        var needsHeaderRefresh = stream.hadLiveBytes
        if (offset > finalSize) {
            precompute?.awaitStopped()
            withContext(host.parts.io) { host.prepare(part, 0L) }
            offset = 0L
            needsHeaderRefresh = false
        }
        if (offset < finalSize) {
            val pace = if (stream.status.recording) stream.status.paceMillis ?: 0L else 0L
            val window = if (stream.status.recording) XrecLimits.RECORDING_GETS_WINDOW else null
            pullRange(session, RangeRequest(activeName, part, offset, finalSize, window, pace), precompute)
        }
        if (needsHeaderRefresh && finalSize >= WAV_HEADER_BYTES) {
            refreshWavHeader(session, activeName, part, finalSize, host.parts.io)
        }
        // Wait for the precompute loop to stop touching `part` or `stagingDir` before import/install starts.
        precompute?.awaitStopped()
        val recordedAt = RecorderTimestamps.resolve(finalEntry) ?: fallbackStart
        val noteId = host.importFinalized(address, activeName, finalSize, part, precompute, recordedAt)
        return SegmentResult(
            downloaded = if (noteId != null) 1 else 0,
            noteId = noteId,
            listing = listing,
            status = stream.status,
        )
    }

    private suspend fun locateActiveEntry(
        session: RecorderSync.Session,
        activeIndex: Long,
        withTimestamps: Boolean,
        initialListing: List<XrecReply.Entry>,
        initialStatus: XrecReply.Status,
    ): Located? {
        var listing = initialListing
        var status = initialStatus
        var match = listing.firstOrNull { recordingIndexOf(it.name) == activeIndex }
        var attempt = 0
        var active = true
        while (match == null && active && attempt < MAX_LOCATE_ATTEMPTS) {
            if (attempt > 0) host.parts.wait(host.parts.livePollMillis)
            attempt++
            status = session.call { stat() }
            if (!status.recording || status.recordingIndex != activeIndex) {
                active = false
            } else {
                listing = session.call { list(withTimestamps) }
                match = listing.firstOrNull { recordingIndexOf(it.name) == activeIndex }
            }
        }
        return match?.let { Located(it, listing, status) }
    }

    private suspend fun prepareLivePart(
        session: RecorderSync.Session,
        address: String,
        part: File,
        activeName: String,
        flushed: Long,
    ): LivePartState {
        val storedPartial = host.partial(address)
        val existingLen = diskLength(part)
        val matchesStored = storedPartial != null && storedPartial.name.equals(activeName, ignoreCase = true)
        val resumeOffset = if (matchesStored) {
            verifiedLiveOffset(session, activeName, part, existingLen, flushed)
        } else {
            0L
        }
        if (resumeOffset == 0L) {
            withContext(host.parts.io) { host.prepare(part, 0L) }
            host.updatePartial(address, PartialDownload(activeName, flushed, 0L))
            return LivePartState(0L, liveHeaderPending = false)
        }
        val liveHeaderPending = storedPartial?.liveHeaderPending == true || resumeOffset > WAV_HEADER_BYTES
        return LivePartState(resumeOffset, liveHeaderPending)
    }

    /** Reused recorder numbers can share a silent opening. Probe both ends of the downloaded PCM, skipping the
     * mutable WAV header. A failed transfer leaves the prefix intact for a later sync. */
    suspend fun verifiedLiveOffset(
        session: RecorderSync.Session,
        name: String,
        part: File,
        length: Long,
        available: Long,
    ): Long {
        if (length !in MIN_VERIFY_OFFSET..available) return 0L
        val headEnd = minOf(length, MAX_VERIFY_OFFSET)
        val tailStart = maxOf(headEnd, length - MAX_VERIFY_OFFSET)
        val matches = matchesRange(session, name, part, WAV_HEADER_BYTES, headEnd) &&
            (tailStart == length || matchesRange(session, name, part, tailStart, length))
        return if (matches) length else 0L
    }

    private suspend fun matchesRange(
        session: RecorderSync.Session,
        name: String,
        part: File,
        from: Long,
        end: Long,
    ): Boolean {
        val local = withContext(host.parts.io) {
            ByteArray((end - from).toInt()).also { dst ->
                RandomAccessFile(part, "r").use { raf ->
                    raf.seek(from)
                    raf.readFully(dst)
                }
            }
        }
        val remote = ByteArrayOutputStream(local.size)
        session.call { download(name, end, remote, resumeOffset = from) { session.touch() } }
        return remote.toByteArray().contentEquals(local)
    }

    private suspend fun pullFlushedRange(session: RecorderSync.Session, req: RangeRequest): PullResult = try {
        pullRange(session, req)
        PullResult.Appended(diskLength(req.part))
    } catch (error: XrecException) {
        when (error.failure) {
            XrecFailure.LATER -> PullResult.Deferred(diskLength(req.part))
            XrecFailure.BUSY, XrecFailure.SIZE_CHANGED -> {
                val diskLen = diskLength(req.part)
                if (diskLen > req.offset) PullResult.Appended(diskLen) else PullResult.Retry
            }
            else -> throw error
        }
    }

    private suspend fun diskLength(file: File): Long =
        withContext(host.parts.io) { if (file.isFile) file.length() else 0L }

    private suspend fun pullRange(
        session: RecorderSync.Session,
        req: RangeRequest,
        precompute: LivePrecomputeSession? = null,
    ) {
        host.progress(SyncStep.DOWNLOADING, req.name, req.offset, req.endOffset)
        withContext(host.parts.io) {
            val actualLen = if (req.part.isFile) req.part.length() else 0L
            if (actualLen != req.offset) host.prepare(req.part, req.offset)
        }
        session.call {
            FileOutputStream(req.part, true).use { out ->
                val buffered = BufferedOutputStream(out, PART_BUFFER_BYTES)
                var lastWake = req.offset
                thenFlushing(buffered) {
                    download(req.name, req.endOffset, buffered, req.offset, req.window, req.paceMillis) { done ->
                        session.touch()
                        if (precompute != null && done - lastWake >= FINAL_WAKE_BYTES) {
                            buffered.flush()
                            precompute.onBytesAvailable(done)
                            lastWake = done
                        }
                        host.progress(SyncStep.DOWNLOADING, req.name, done, req.endOffset)
                    }
                }
                out.fd.sync()
            }
        }
    }

    private suspend fun waitForTranscript(
        session: RecorderSync.Session,
        noteId: String,
        initialStatus: XrecReply.Status,
    ): XrecReply.Status {
        var status = initialStatus
        val step = host.parts.sidecarPollMillis.coerceAtLeast(0L)
        var elapsed = 0L
        var keepaliveElapsed = 0L
        var polls = 0
        var waiting = true
        while (waiting && canWaitMore(polls, step, elapsed)) {
            if (!isTranscriptPending(noteId)) {
                waiting = false
            } else {
                polls++
                host.parts.wait(step)
                elapsed += step
                keepaliveElapsed += step
                val next = maybeKeepaliveStat(session, step, polls, keepaliveElapsed)
                if (next != null) {
                    keepaliveElapsed = 0L
                    status = next
                    waiting = !next.recording
                }
            }
        }
        return status
    }

    private fun canWaitMore(polls: Int, step: Long, elapsed: Long): Boolean =
        polls < MAX_SIDECAR_POLLS && (step == 0L || elapsed < host.parts.maxSidecarWaitMillis)

    private suspend fun isTranscriptPending(noteId: String): Boolean =
        host.parts.library.transcripts(listOf(noteId))[noteId] == null &&
            host.parts.library.activeTranscriptions(listOf(noteId)) > 0

    private suspend fun maybeKeepaliveStat(
        session: RecorderSync.Session,
        step: Long,
        polls: Int,
        keepaliveElapsed: Long,
    ): XrecReply.Status? {
        val zeroStepDue = step == 0L && polls % KEEPALIVE_ZERO_STEP_INTERVAL == 0
        val timedDue = step > 0L && keepaliveElapsed >= KEEPALIVE_INTERVAL_MS
        if (!zeroStepDue && !timedDue) return null
        val status = session.call { stat() }
        if (status.recording) Log.i(TAG, "New recording started while awaiting live transcript")
        return status
    }

    private fun recordingIndexOf(name: String): Long? =
        REC_NAME_REGEX.matchEntire(name)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private class RangeRequest(
        val name: String,
        val part: File,
        val offset: Long,
        val endOffset: Long,
        val window: Int?,
        val paceMillis: Long,
    )

    private class LivePartState(val startOffset: Long, val liveHeaderPending: Boolean)

    private class StepResult(val offset: Long, val deferred: Boolean)

    private class StreamState(
        val status: XrecReply.Status,
        val offset: Long,
        val hadLiveBytes: Boolean,
        val deferred: Boolean,
    )

    private class Located(
        val entry: XrecReply.Entry,
        val listing: List<XrecReply.Entry>,
        val status: XrecReply.Status,
    )

    private class SegmentResult(
        val downloaded: Int = 0,
        val noteId: String? = null,
        val deferred: Boolean = false,
        val aborted: Boolean = false,
        val listing: List<XrecReply.Entry>,
        val status: XrecReply.Status,
    )

    private inner class PrecomputeHolder(
        private val part: File,
        private val activeName: String,
    ) : AutoCloseable {
        private var active: LivePrecomputeSession? = null
        private var lastAttemptMs: Long = Long.MIN_VALUE / 2
        private var lastAttemptOffset: Long = 0L

        fun current(): LivePrecomputeSession? = active

        suspend fun tryOpen(offset: Long) {
            lastAttemptMs = host.parts.clock()
            lastAttemptOffset = offset
            val opened = host.parts.library.openLivePrecompute(part, activeName)
            active = opened
            if (opened != null && offset > WAV_HEADER_BYTES) {
                opened.onBytesAvailable(offset)
            }
        }

        suspend fun ensureActive(offset: Long) {
            val existing = active
            if (existing != null && existing.isActive) return
            val now = host.parts.clock()
            val timeDue = now - lastAttemptMs >= PRECOMPUTE_RETRY_MS
            val bytesDue = offset - lastAttemptOffset >= PRECOMPUTE_RETRY_BYTES
            if (timeDue || bytesDue) {
                // Join the stopped worker before a new one opens the same staging directory.
                existing?.awaitStopped()
                existing?.close()
                active = null
                tryOpen(offset)
            }
        }

        fun onBytesAvailable(bytes: Long) {
            active?.takeIf { it.isActive }?.onBytesAvailable(bytes)
        }

        override fun close() {
            active?.close()
            active = null
        }
    }

    private sealed interface PullResult {
        data class Appended(val newOffset: Long) : PullResult
        data object Retry : PullResult
        data class Deferred(val newOffset: Long) : PullResult
    }

    companion object {
        private const val TAG = "RecorderLiveTailer"
        private val REC_NAME_REGEX = Regex("REC_([0-9]+)\\.wav", RegexOption.IGNORE_CASE)
        private const val WAV_HEADER_BYTES = 44L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val MIN_VERIFY_OFFSET = 108L
        private const val MAX_VERIFY_OFFSET = 512L
        private const val PART_BUFFER_BYTES = 64 * 1024
        private const val LIVE_BATCH_BYTES = 4L * XrecLimits.RECORDING_GETS_WINDOW
        private const val FINAL_WAKE_BYTES = 256 * 1024L
        private const val MAX_SEGMENTS = 8
        private const val MAX_LOCATE_ATTEMPTS = 4
        private const val KEEPALIVE_INTERVAL_MS = 1_500L
        private const val KEEPALIVE_ZERO_STEP_INTERVAL = 5
        private const val MAX_SIDECAR_POLLS = 256
        private const val PRECOMPUTE_RETRY_MS = 5_000L
        private const val PRECOMPUTE_RETRY_BYTES = 256 * 1024L
    }
}

internal suspend fun refreshWavHeader(
    session: RecorderSync.Session,
    name: String,
    part: File,
    finalSize: Long,
    io: CoroutineDispatcher,
) {
    val headerSize = minOf(XrecLimits.EMPTY_WAV_BYTES, finalSize)
    val headerOut = ByteArrayOutputStream(headerSize.toInt())
    session.call {
        download(name, headerSize, headerOut, resumeOffset = 0L) { session.touch() }
    }
    val bytes = headerOut.toByteArray()
    require(bytes.size.toLong() == headerSize) { "Truncated finalized WAV header for $name: ${bytes.size}" }
    withContext(io) {
        RandomAccessFile(part, "rw").use { raf ->
            raf.seek(0L)
            raf.write(bytes)
            raf.fd.sync()
        }
    }
}
