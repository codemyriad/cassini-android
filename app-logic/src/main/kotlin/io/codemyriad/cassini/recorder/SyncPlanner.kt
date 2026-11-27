package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.XrecLimits
import io.codemyriad.cassini.core.recorder.XrecNames
import io.codemyriad.cassini.core.recorder.XrecReply
import java.time.DateTimeException
import java.time.LocalDateTime
import java.util.Locale

/**
 * One recording of one recorder that the app has imported. The sync identity is ([name], [size]): the recorder can
 * number its files from 1 again after the card is wiped, so the same name with another size is a new recording.
 * [headSha256] is the SHA-256 of the first 4 KiB: a file with the same name and size whose sidecar is missing is
 * compared with it ([SyncPlanner.suspects]), so a new recording that reuses both after a wipe is found.
 */
data class SyncedRecording(
    val name: String,
    val size: Long,
    val noteId: String?,
    val headSha256: String? = null,
    /** The transcript version that is on the recorder as the sidecar, or null when the app has not sent one. */
    val transcriptUploadedVersion: String? = null,
    /** The transcript version that the recorder refused for good (a bad name): it is not sent again. */
    val transcriptRefusedVersion: String? = null,
)

/**
 * A download that stopped before its end; [bytes] are on disk. [liveHeaderPending] marks a `.part` populated during
 * active recording whose initial 44-byte WAV header must still be refreshed after stop even if the file size didn't grow.
 */
data class PartialDownload(
    val name: String,
    val size: Long,
    val bytes: Long,
    val liveHeaderPending: Boolean = false,
)

/** What the app remembers about one recorder. */
data class RecorderSyncState(
    val recordings: List<SyncedRecording> = emptyList(),
    val partial: PartialDownload? = null,
)

/** Why a file of the listing is not downloaded. */
enum class SkipReason {
    /** At most a WAV header: no audio. */
    EMPTY,

    /** Not a `.wav` file (sidecars and other files). */
    NOT_WAV,

    /** The recorder records now; its files in /record may grow. */
    RECORDING,

    /**
     * The file that the recorder records now (STAT `rec_idx`): it grows. The finished recordings go during the
     * recording.
     */
    ACTIVE,

    /** The file name cannot be read back with GETS. */
    UNSAFE_NAME,
}

data class PlannedDownload(
    val name: String,
    val size: Long,
    /**
     * The stopped download of this same file (name and size), or null: start from 0. The sync continues from the
     * length of its `.part` file, which can be ahead of the stored bytes after process death.
     */
    val resume: PartialDownload? = null,
    val recordedAt: LocalDateTime? = null,
)

data class PlannedUpload(
    val recording: SyncedRecording,
    val sidecarName: String,
    val transcriptVersion: String,
)

data class SkippedFile(val name: String, val reason: SkipReason)

data class SyncPlan(
    val downloads: List<PlannedDownload>,
    val uploads: List<PlannedUpload>,
    val skipped: List<SkippedFile>,
    /** The recorder records now: all downloads wait for the next sync, unless [whileRecording]. */
    val deviceRecording: Boolean,
    /**
     * The recorder records, names the file it records, and allows transfers (a mono recording), and the user turned
     * "Sync while the recorder records" on: the finished recordings are downloaded now at the recorder's pace, only the
     * active file ([SkipReason.ACTIVE]) waits.
     */
    val whileRecording: Boolean = false,
)

/**
 * Decides what one sync with a recorder does. Pure: the inputs are the recorder's `LS` listing and `STAT`, the
 * remembered [RecorderSyncState], and the transcript version of each note that has a finished transcript.
 *
 * - Download each `.wav` larger than its header that the state does not have with the same name and size. While the
 *   recorder records (STAT media 33), no download starts, unless [syncWhileRecording][plan] is on and the recorder
 *   names the file it records (STAT `rec_idx`, docs/XREC_PROTOCOL.md 5.5) with a usable pace: then only that file
 *   waits. Only that one: the firmware numbers a new recording after the highest number, but after `REC_9999.wav` it
 *   starts again from the lowest free one, so finished recordings can have higher numbers. The caller lists between two
 *   STATs that show the same recording (a split or a stop during LS changes it), so no other file of the listing grows.
 *   A stopped download of the same name and size resumes.
 * - Upload the transcript of each imported recording that is still on the recorder, when the note has a transcript and
 *   the recorder has no sidecar for it or an older one, unless the recorder refused that version for good. Uploads
 *   also run while the recorder records: they go to a temporary file that PUTEND commits.
 */
object SyncPlanner {
    fun plan(
        listing: List<XrecReply.Entry>,
        status: XrecReply.Status,
        state: RecorderSyncState,
        transcriptVersions: Map<String, String>,
        syncWhileRecording: Boolean = false,
    ): SyncPlan {
        val known = state.recordings.map { fileKey(it.name, it.size) }.toSet()
        val downloads = mutableListOf<PlannedDownload>()
        val skipped = mutableListOf<SkippedFile>()
        val whileRecording = syncWhileRecording && downloadsWhileRecording(status)
        for (entry in listing.filterNot { fileKey(it.name, it.size) in known }) {
            val reason = skipReason(entry, status, whileRecording)
            when {
                reason == null -> downloads += download(entry, state)
                // The sidecars are the transcripts the app sent: no need to report them.
                !XrecNames.isSidecar(entry.name) -> skipped += SkippedFile(entry.name, reason)
            }
        }
        val uploads = state.recordings.mapNotNull { upload(it, listing, transcriptVersions) }
        return SyncPlan(downloads, uploads, skipped, status.recording, whileRecording)
    }

    /**
     * The longest STAT `pace_ms` at which the app downloads during a recording. A stereo recording reports 30000: it
     * allows no transfer (decision 5 of docs/BT_SYNC_SPEED_AND_RECORDING.md), so its downloads wait for its end.
     */
    const val MAX_PACE_MS = 10_000L

    /**
     * True when [status] lets finished recordings go while the recorder records: it records, names the active file
     * (`rec_idx` > 0) and gives a pace up to [MAX_PACE_MS]. Older firmware without the field: never.
     */
    fun downloadsWhileRecording(status: XrecReply.Status): Boolean =
        status.recording && (status.recordingIndex ?: 0) > 0 && status.paceMillis?.let { it in 0..MAX_PACE_MS } == true

    /** The number of a recorder's `REC_NNNN.wav`, or null for another name. */
    fun recordingNumber(name: String): Long? =
        REC_NAME.matchEntire(name)?.groupValues?.get(1)?.takeIf { it.length <= MAX_DIGITS }?.toLong()

    private val REC_NAME = Regex("REC_([0-9]+)\\.wav", RegexOption.IGNORE_CASE)
    private const val MAX_DIGITS = 9

    /**
     * The imported recordings to compare by their first 4 KiB before the sync trusts (name, size): the listing has the
     * file but not its sidecar, although the app sent one or is about to send one. After a card wipe, a new recording
     * can have the name and the size of an old one; the old note's transcript must not go to it.
     */
    fun suspects(
        listing: List<XrecReply.Entry>,
        state: RecorderSyncState,
        transcriptVersions: Map<String, String>,
    ): List<SyncedRecording> = state.recordings.filter { recording ->
        val sidecar = XrecNames.sidecarFor(recording.name)
        val version = recording.noteId?.let(transcriptVersions::get)
        val sendsOrSent = recording.transcriptUploadedVersion != null ||
            (version != null && version != recording.transcriptRefusedVersion)
        recording.headSha256 != null && sidecar != null && sendsOrSent && listing.has(recording.name, recording.size) &&
            listing.none { it.name.equals(sidecar, ignoreCase = true) }
    }

    private fun fileKey(name: String, size: Long): Pair<String, Long> = name.lowercase(Locale.ROOT) to size

    private fun List<XrecReply.Entry>.has(name: String, size: Long) =
        any { it.name.equals(name, ignoreCase = true) && it.size == size }

    private fun skipReason(entry: XrecReply.Entry, status: XrecReply.Status, whileRecording: Boolean): SkipReason? =
        when {
            !XrecNames.isWav(entry.name) -> SkipReason.NOT_WAV
            // The active file can still be a bare header when the recording has just started.
            whileRecording && isActive(entry.name, checkNotNull(status.recordingIndex)) -> SkipReason.ACTIVE
            entry.size <= XrecLimits.EMPTY_WAV_BYTES -> SkipReason.EMPTY
            !XrecNames.isSafe(entry.name) -> SkipReason.UNSAFE_NAME
            status.recording && !whileRecording -> SkipReason.RECORDING
            else -> null
        }

    /** The file of the recording [index]: it can still grow. */
    private fun isActive(name: String, index: Long): Boolean = recordingNumber(name) == index

    private fun download(entry: XrecReply.Entry, state: RecorderSyncState): PlannedDownload {
        val partial = state.partial?.takeIf { it.name.equals(entry.name, ignoreCase = true) && it.size == entry.size }
            ?.takeIf { it.bytes in 0..entry.size }
        return PlannedDownload(entry.name, entry.size, partial, RecorderTimestamps.resolve(entry))
    }

    private fun upload(
        recording: SyncedRecording,
        listing: List<XrecReply.Entry>,
        transcriptVersions: Map<String, String>,
    ): PlannedUpload? {
        val version = recording.noteId?.let(transcriptVersions::get)
        val sidecar = XrecNames.sidecarFor(recording.name)
        val wanted = version != null && sidecar != null && listing.has(recording.name, recording.size) &&
            recording.transcriptRefusedVersion != version
        if (!wanted) return null
        // The card's names are case-insensitive.
        val current = recording.transcriptUploadedVersion == version &&
            listing.any { it.name.equals(sidecar, ignoreCase = true) }
        return if (current) null else PlannedUpload(recording, checkNotNull(sidecar), checkNotNull(version))
    }
}

/** Resolves a recorder file's local start timestamp from `+XREC:LS,T` or legacy `YYYYMMDD-HHMMSS` filenames. */
object RecorderTimestamps {
    private val filenameDatePrefix = Regex("^(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})(\\d{2})(?:\\D|$)")
    private const val MIN_YEAR = 2000
    private const val MAX_YEAR = 2099

    @Suppress("MagicNumber") // Matches YYYYMMDD-HHMMSS regex capture groups 1..6 cleanly.
    fun filenameStart(name: String): LocalDateTime? {
        val match = filenameDatePrefix.find(name)
        val year = match?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in MIN_YEAR..MAX_YEAR }
        return if (year == null) {
            null
        } else {
            try {
                LocalDateTime.of(
                    year,
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt(),
                    match.groupValues[4].toInt(),
                    match.groupValues[5].toInt(),
                    match.groupValues[6].toInt(),
                )
            } catch (_: DateTimeException) {
                null
            }
        }
    }

    fun resolve(entry: XrecReply.Entry): LocalDateTime? = entry.recordedAt ?: filenameStart(entry.name)
}
