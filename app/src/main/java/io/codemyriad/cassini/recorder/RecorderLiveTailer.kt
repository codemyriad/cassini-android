package io.codemyriad.cassini.recorder

import android.util.Log
import io.codemyriad.cassini.core.recorder.XrecException
import io.codemyriad.cassini.core.recorder.XrecFailure
import io.codemyriad.cassini.core.recorder.XrecLimits
import io.codemyriad.cassini.core.recorder.XrecReply
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

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
        suspend fun partialName(address: String): String?
        suspend fun updatePartial(address: String, partial: PartialDownload?)
        suspend fun importFinalized(
            address: String,
            name: String,
            size: Long,
            part: File,
            precompute: LivePrecomputeSession?,
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
            val result = tailOneSegment(session, address, listing, status)
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
        initialListing: List<XrecReply.Entry>,
        initialStatus: XrecReply.Status,
    ): SegmentResult {
        val activeIndex = initialStatus.recordingIndex
            ?: return SegmentResult(aborted = true, listing = initialListing, status = initialStatus)
        val found = locateActiveEntry(session, activeIndex, initialListing, initialStatus)
            ?: return SegmentResult(aborted = true, listing = initialListing, status = initialStatus)
        val activeName = found.entry.name
        val part = host.partFile(address, activeName)
        val startOffset = prepareLivePart(
            session = session,
            address = address,
            part = part,
            activeName = activeName,
            flushed = found.status.recordingBytes ?: 0L,
        )
        val precompute = host.parts.library.openLivePrecompute(part, activeName)
        return try {
            if (startOffset > WAV_HEADER_BYTES) precompute?.onBytesAvailable(startOffset)
            val stream = streamUntilBoundary(
                session = session,
                address = address,
                part = part,
                activeIndex = activeIndex,
                activeName = activeName,
                initialStatus = found.status,
                startOffset = startOffset,
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
                    stream = stream,
                    precompute = precompute,
                )
            }
        } finally {
            precompute?.close()
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
        startOffset: Long,
        precompute: LivePrecomputeSession?,
    ): StreamState {
        var status = initialStatus
        var offset = startOffset
        var liveStreamed = startOffset > WAV_HEADER_BYTES
        var deferred = false
        while (!deferred && status.recording && status.recordingIndex == activeIndex) {
            val flushed = status.recordingBytes ?: 0L
            if (flushed > offset && flushed > WAV_HEADER_BYTES) {
                val req = RangeRequest(
                    name = activeName,
                    part = part,
                    offset = offset,
                    endOffset = flushed,
                    window = XrecLimits.RECORDING_GETS_WINDOW,
                    paceMillis = status.paceMillis ?: 0L,
                )
                when (val step = pullFlushedRange(session, req)) {
                    is PullResult.Appended -> {
                        offset = step.newOffset
                        liveStreamed = true
                        host.updatePartial(address, PartialDownload(activeName, flushed, offset))
                        precompute?.onBytesAvailable(offset)
                    }
                    PullResult.Retry -> host.parts.wait(host.parts.livePollMillis)
                    PullResult.Deferred -> deferred = true
                }
            } else {
                host.parts.wait(host.parts.livePollMillis)
            }
            if (!deferred) status = session.call { stat() }
        }
        return StreamState(status, offset, liveStreamed || startOffset > 0L, deferred)
    }

    @Suppress("LongParameterList") // Completes tail download, header refresh, import, and precompute install.
    private suspend fun finalizeSegment(
        session: RecorderSync.Session,
        address: String,
        part: File,
        activeName: String,
        stream: StreamState,
        precompute: LivePrecomputeSession?,
    ): SegmentResult {
        val listing = session.call { list() }
        val finalEntry = listing.firstOrNull { it.name.equals(activeName, ignoreCase = true) }
        if (finalEntry == null) {
            return SegmentResult(aborted = true, listing = listing, status = stream.status)
        }
        val finalSize = finalEntry.size
        var offset = stream.offset
        var needsHeaderRefresh = stream.hadLiveBytes
        if (offset > finalSize) {
            withContext(host.parts.io) { host.prepare(part, 0L) }
            offset = 0L
            needsHeaderRefresh = false
        }
        if (offset < finalSize) {
            val pace = if (stream.status.recording) stream.status.paceMillis ?: 0L else 0L
            val window = if (stream.status.recording) XrecLimits.RECORDING_GETS_WINDOW else null
            pullRange(session, RangeRequest(activeName, part, offset, finalSize, window, pace))
        }
        if (needsHeaderRefresh && finalSize >= WAV_HEADER_BYTES) {
            refreshWavHeader(session, activeName, part, finalSize)
        }
        val noteId = host.importFinalized(address, activeName, finalSize, part, precompute)
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
                listing = session.call { list() }
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
    ): Long {
        val storedPartial = host.partialName(address)
        val existingLen = withContext(host.parts.io) { if (part.isFile) part.length() else 0L }
        val matchesStored = storedPartial != null && storedPartial.equals(activeName, ignoreCase = true)
        val resumeOffset = if (matchesStored && existingLen >= MIN_VERIFY_OFFSET) {
            val verifyEnd = minOf(existingLen, MAX_VERIFY_OFFSET)
            if (verifyLivePrefix(session, activeName, part, verifyEnd)) existingLen else 0L
        } else {
            0L
        }
        if (resumeOffset == 0L) {
            withContext(host.parts.io) { host.prepare(part, 0L) }
            host.updatePartial(address, PartialDownload(activeName, flushed, 0L))
        }
        return resumeOffset
    }

    @Suppress("ReturnCount") // Validates length, fetches remote slice, compares bytes.
    private suspend fun verifyLivePrefix(
        session: RecorderSync.Session,
        activeName: String,
        part: File,
        verifyEnd: Long,
    ): Boolean {
        val length = verifyEnd - WAV_HEADER_BYTES
        if (length <= 0L) return false
        val remote = ByteArrayOutputStream(length.toInt())
        val fetched = try {
            session.call {
                download(activeName, verifyEnd, remote, resumeOffset = WAV_HEADER_BYTES) { session.touch() }
            }
            true
        } catch (error: XrecException) {
            if (error.failure in LINK_FAILURES) throw error
            false
        }
        if (!fetched || remote.size().toLong() != length) return false
        val local = withContext(host.parts.io) {
            ByteArray(length.toInt()).also { dst ->
                RandomAccessFile(part, "r").use { raf ->
                    raf.seek(WAV_HEADER_BYTES)
                    raf.readFully(dst)
                }
            }
        }
        return remote.toByteArray().contentEquals(local)
    }

    private suspend fun pullFlushedRange(session: RecorderSync.Session, req: RangeRequest): PullResult = try {
        pullRange(session, req)
        PullResult.Appended(withContext(host.parts.io) { req.part.length() })
    } catch (error: XrecException) {
        when (error.failure) {
            XrecFailure.LATER -> PullResult.Deferred
            XrecFailure.BUSY, XrecFailure.SIZE_CHANGED -> PullResult.Retry
            else -> throw error
        }
    }

    private suspend fun pullRange(session: RecorderSync.Session, req: RangeRequest) {
        host.progress(SyncStep.DOWNLOADING, req.name, req.offset, req.endOffset)
        session.call {
            FileOutputStream(req.part, true).use { out ->
                val buffered = BufferedOutputStream(out, PART_BUFFER_BYTES)
                thenFlushing(buffered) {
                    download(req.name, req.endOffset, buffered, req.offset, req.window, req.paceMillis) { done ->
                        session.touch()
                        host.progress(SyncStep.DOWNLOADING, req.name, done, req.endOffset)
                    }
                }
                out.fd.sync()
            }
        }
    }

    private suspend fun refreshWavHeader(session: RecorderSync.Session, name: String, part: File, finalSize: Long) {
        val headerSize = minOf(WAV_HEADER_BYTES, finalSize)
        val headerOut = ByteArrayOutputStream(headerSize.toInt())
        session.call {
            download(name, headerSize, headerOut, resumeOffset = 0L) { session.touch() }
        }
        val bytes = headerOut.toByteArray()
        require(bytes.size.toLong() == headerSize) { "Truncated finalized WAV header for $name: ${bytes.size}" }
        withContext(host.parts.io) {
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(0L)
                raf.write(bytes)
                raf.fd.sync()
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

    private sealed interface PullResult {
        data class Appended(val newOffset: Long) : PullResult
        data object Retry : PullResult
        data object Deferred : PullResult
    }

    companion object {
        private const val TAG = "RecorderLiveTailer"
        private val REC_NAME_REGEX = Regex("REC_([0-9]+)\\.wav", RegexOption.IGNORE_CASE)
        private val LINK_FAILURES = setOf(XrecFailure.DISCONNECTED, XrecFailure.TIMEOUT, XrecFailure.PROTOCOL)
        private const val WAV_HEADER_BYTES = 44L
        private const val MIN_VERIFY_OFFSET = 108L
        private const val MAX_VERIFY_OFFSET = 512L
        private const val PART_BUFFER_BYTES = 64 * 1024
        private const val MAX_SEGMENTS = 8
        private const val MAX_LOCATE_ATTEMPTS = 4
        private const val KEEPALIVE_INTERVAL_MS = 1_500L
        private const val KEEPALIVE_ZERO_STEP_INTERVAL = 5
        private const val MAX_SIDECAR_POLLS = 256
    }
}
