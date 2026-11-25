package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.XrecLimits
import io.codemyriad.cassini.core.recorder.XrecNames
import io.codemyriad.cassini.core.recorder.XrecReply

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

/** A download that stopped before its end; [bytes] are on disk. */
data class PartialDownload(val name: String, val size: Long, val bytes: Long)

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
    /** The recorder records now: all downloads wait for the next sync. */
    val deviceRecording: Boolean,
)

/**
 * Decides what one sync with a recorder does. Pure: the inputs are the recorder's `LS` listing and `STAT`, the
 * remembered [RecorderSyncState], and the transcript version of each note that has a finished transcript.
 *
 * - Download each `.wav` larger than its header that the state does not have with the same name and size. While the
 *   recorder records (STAT media 33), no download starts. A stopped download of the same name and size resumes.
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
    ): SyncPlan {
        val known = state.recordings.map { it.name to it.size }.toSet()
        val downloads = mutableListOf<PlannedDownload>()
        val skipped = mutableListOf<SkippedFile>()
        for (entry in listing.filterNot { (it.name to it.size) in known }) {
            val reason = skipReason(entry, status)
            when {
                reason == null -> downloads += download(entry, state)
                // The sidecars are the transcripts the app sent: no need to report them.
                !XrecNames.isSidecar(entry.name) -> skipped += SkippedFile(entry.name, reason)
            }
        }
        val uploads = state.recordings.mapNotNull { upload(it, listing, transcriptVersions) }
        return SyncPlan(downloads, uploads, skipped, status.recording)
    }

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

    private fun List<XrecReply.Entry>.has(name: String, size: Long) = any { it.name == name && it.size == size }

    private fun skipReason(entry: XrecReply.Entry, status: XrecReply.Status): SkipReason? = when {
        !XrecNames.isWav(entry.name) -> SkipReason.NOT_WAV
        entry.size <= XrecLimits.EMPTY_WAV_BYTES -> SkipReason.EMPTY
        !XrecNames.isSafe(entry.name) -> SkipReason.UNSAFE_NAME
        status.recording -> SkipReason.RECORDING
        else -> null
    }

    private fun download(entry: XrecReply.Entry, state: RecorderSyncState): PlannedDownload {
        val partial = state.partial?.takeIf { it.name == entry.name && it.size == entry.size }
            ?.takeIf { it.bytes in 0..entry.size }
        return PlannedDownload(entry.name, entry.size, partial)
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
