package org.cassini.android.notices

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.cassini.android.recording.RecordingOutcome
import org.cassini.android.recording.RecordingPause
import org.cassini.android.recording.RecordingState
import org.cassini.android.recording.RecordingWarning
import org.cassini.core.Failure
import org.cassini.core.audio.Limits
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.library.PauseReason
import org.cassini.core.models.ModelPart
import org.cassini.data.db.JobEntity
import org.cassini.data.voices.VoiceProblem
import org.cassini.engine.job.DownloadError
import org.cassini.engine.job.DownloadProgress
import org.cassini.engine.job.JobFailure
import org.cassini.engine.job.JobPhase
import org.cassini.ui.banner.BannerAction
import org.cassini.ui.banner.BannerArgs
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.banner.BannerSpec
import org.cassini.ui.format.ModelPartUi
import org.cassini.ui.theme.Tone

/** The recordings that recovery repaired and the user has not seen yet (features.md 13.2 RECOVERED). */
data class RecoveredNotice(val count: Int, val latestNoteId: String, val latestDurationMs: Long, val latestAt: Long)

/** A failed import, shown at the top until it is dismissed or the next import starts (features.md 13.5). */
data class ImportFailure(val failure: Failure, val neededBytes: Long = 0, val at: Long = 0)

/** The imports of the app now: the display names of the running ones and the last failure. */
data class ImportState(val running: List<String> = emptyList(), val failure: ImportFailure? = null)

/** A required model part that the last standalone download could not install (features.md 13.4). */
data class DownloadFailure(val error: DownloadError, val requiredFreeBytes: Long = 0, val at: Long = 0)

/**
 * Every app-wide fact that a banner depends on, as one immutable value: [NoticeCenter] builds it from the
 * coordinator, the recorder, the startup report, the models, the settings, the imports and the notification
 * permission. The screens add their own facts ([LibraryFacts]) and call [Notices].
 *
 * [hold] and [queued] are the queue hold and the QUEUED notes; [jobRunning] is true while a job runs or a standalone
 * download runs; [consented] is the model download consent; [encoderMissing] is true when `libcassini_opus` failed to
 * load and the user did not dismiss the notice ([m4a]: the capture format is M4A); [notificationsOff] is true when
 * the app may not post notifications; [downloadFailure] is the failure of the last standalone download.
 */
data class NoticeFacts(
    val hold: Boolean = false,
    val queued: Int = 0,
    val jobRunning: Boolean = false,
    val recovered: RecoveredNotice? = null,
    val consented: Boolean = true,
    val encoderMissing: Boolean = false,
    val m4a: Boolean = false,
    val notificationsOff: Boolean = false,
    val imports: ImportState = ImportState(),
    val downloadFailure: DownloadFailure? = null,
    val voicesProblem: VoiceProblem? = null,
)

/**
 * What only the Library knows: [loading] until the first rows arrive, [liveNotes] (the live notes), [query] (the
 * search text, blank when not searching), [results] (the number of search results; null while the search has not
 * answered) and [audioOnly] (an "Audio only" note exists).
 */
data class LibraryFacts(
    val loading: Boolean = false,
    val liveNotes: Int = 0,
    val query: String = "",
    val results: Int? = null,
    val audioOnly: Boolean = false,
)

/**
 * The banner lists of each screen (features.md section 13; ui.md section 7), as pure functions of [NoticeFacts] and
 * the screen's own facts. Each list is ordered failed, attention, info, then fine and plain; within one tone the
 * newest notice comes first, and notices without a time keep the order of the catalogue. The Note screen's trust
 * banners (13.1) are built by the Note ViewModel; [job] gives its transcription and download banners.
 *
 * The banner [BannerSpec.id] is the kind's name; a list never holds two banners of one kind.
 */
@Suppress("TooManyFunctions") // one function per screen, plus the shared builders of the download banners
object Notices {
    /** A banner and the time of its event (0: none), for the order inside a tone. */
    private data class Notice(val spec: BannerSpec, val at: Long = 0)

    // Library (features.md section 1, item 4, and 13.5). ------------------------------------------------------------

    /** The Library banners: the empty and no-match states, then the app-wide notices. */
    fun library(facts: NoticeFacts, library: LibraryFacts): ImmutableList<BannerSpec> {
        val notices = mutableListOf<Notice>()
        if (facts.hold && facts.queued > 0) {
            notices += Notice(BannerSpec(BannerKind.TX_QUEUE_HELD, BannerArgs(count = facts.queued)))
        }
        facts.recovered?.let { recovered ->
            val args = BannerArgs(count = recovered.count, ms1 = recovered.latestDurationMs)
            notices += Notice(BannerSpec(BannerKind.RECOVERED, args, announce = true), recovered.latestAt)
        }
        if (!facts.consented && library.audioOnly) notices += Notice(BannerSpec(BannerKind.DL_RECORD_NOW))
        facts.downloadFailure?.let { failure ->
            notices += Notice(download(failure.error, failure.requiredFreeBytes, announce = true), failure.at)
        }
        if (facts.notificationsOff && facts.jobRunning) notices += Notice(BannerSpec(BannerKind.NOTIFICATIONS_OFF))
        if (facts.encoderMissing) notices += Notice(BannerSpec(BannerKind.NO_ENCODER, BannerArgs(m4a = facts.m4a)))
        notices += imports(facts.imports)
        if (!library.loading) notices += listState(library)
        return ordered(notices)
    }

    private fun imports(imports: ImportState): List<Notice> {
        val notices = mutableListOf<Notice>()
        imports.running.lastOrNull()?.let { name ->
            notices += Notice(BannerSpec(BannerKind.IMPORTING, BannerArgs(text = name), announce = true))
        }
        imports.failure?.let { failure ->
            val kind = when (failure.failure) {
                Failure.LARGE -> BannerKind.IMPORT_TOO_LARGE
                Failure.SPACE -> BannerKind.IMPORT_NO_SPACE
                Failure.WAV -> BannerKind.IMPORT_WAV
                else -> BannerKind.IMPORT_OPEN
            }
            // Shown until dismissed or the next import (features.md section 1), so every import error has a close.
            val spec = BannerSpec(kind, BannerArgs(bytes = failure.neededBytes), announce = true, dismissible = true)
            notices += Notice(spec, failure.at)
        }
        return notices
    }

    private fun listState(library: LibraryFacts): List<Notice> = when {
        library.query.isNotBlank() && library.results == 0 ->
            listOf(Notice(BannerSpec(BannerKind.LIB_NO_MATCH, BannerArgs(text = library.query.trim()))))
        library.query.isBlank() && library.liveNotes == 0 -> listOf(Notice(BannerSpec(BannerKind.LIB_EMPTY)))
        else -> emptyList()
    }

    // Recording (13.2). --------------------------------------------------------------------------------------------

    /**
     * The Recording screen's banner list for the recorder [state]. [micOff] is true when RECORD_AUDIO is denied;
     * [ended] is the outcome the screen still shows after the capture ended (the controller may be Idle again).
     */
    fun recording(state: RecordingState, micOff: Boolean, ended: RecordingOutcome? = null): ImmutableList<BannerSpec> {
        val notices = mutableListOf<Notice>()
        if (micOff) notices += Notice(BannerSpec(BannerKind.REC_MIC_OFF))
        val outcome = ended ?: (state as? RecordingState.Ended)?.outcome
        when (outcome) {
            RecordingOutcome.StoppedForSpace -> notices += Notice(
                BannerSpec(
                    BannerKind.REC_STOPPED_STORAGE,
                    BannerArgs(bytes = Limits.MIN_RECORDING_FREE_BYTES),
                    announce = true,
                ),
            )
            RecordingOutcome.MicrophoneFailed ->
                notices += Notice(BannerSpec(BannerKind.REC_MIC_FAILED, announce = true))
            RecordingOutcome.CouldNotStart ->
                notices += Notice(BannerSpec(BannerKind.REC_COULD_NOT_START, announce = true))
            is RecordingOutcome.Saved, null -> Unit
        }
        if (state is RecordingState.Active) {
            if (state.pause == RecordingPause.OTHER_APP) {
                notices += Notice(BannerSpec(BannerKind.REC_PAUSED_OTHER_APP, announce = true))
            }
            when (val warning = state.warning) {
                is RecordingWarning.StorageLow -> {
                    val args = BannerArgs(ms1 = warning.minutes * MINUTE_MS)
                    notices += Notice(BannerSpec(BannerKind.REC_STORAGE_LOW, args, announce = true))
                }
                is RecordingWarning.NearTimeLimit -> notices += Notice(
                    BannerSpec(BannerKind.REC_NEAR_LIMIT, BannerArgs(ms1 = warning.msLeft), announce = true),
                )
                null -> Unit
            }
        }
        return ordered(notices)
    }

    // Transcription and download of one note (13.3 and 13.4; the Note screen). -----------------------------------

    /**
     * The banners of one note's [job]: paused, failed and download banners, and the download progress while [phase]
     * downloads. [oneVoice] is the rule of features.md section 3.3 (the Note ViewModel decides it). [failureText] is
     * the FailureMessages text of a failed job; [requiredFreeBytes] the free space the model plan needs.
     * [noNewVersion] is true when the document cannot take a new version (`LibraryMapping.transcribeAgainRefused`):
     * then no banner offers Transcribe again, and a failed Transcribe again offers no Try again.
     */
    @Suppress("LongParameterList") // the facts of one note's job banners
    fun job(
        job: JobEntity?,
        phase: JobPhase? = null,
        oneVoice: Boolean = false,
        failureText: String? = null,
        requiredFreeBytes: Long = 0,
        noNewVersion: Boolean = false,
    ): ImmutableList<BannerSpec> {
        val notices = mutableListOf<Notice>()
        (phase as? JobPhase.Downloading)?.progress?.let { notices += Notice(downloading(it)) }
        if (job != null) notices += listOfNotNull(jobNotice(job, failureText, requiredFreeBytes, noNewVersion))
        if (oneVoice && job == null) {
            val actions = if (noNewVersion) persistentListOf() else BannerKind.TX_ONE_VOICE.actions
            notices += Notice(BannerSpec(BannerKind.TX_ONE_VOICE, actions = actions))
        }
        return ordered(notices)
    }

    private fun jobNotice(
        job: JobEntity,
        failureText: String?,
        requiredFreeBytes: Long,
        noNewVersion: Boolean,
    ): Notice? {
        val checkpoint = job.doneMs > 0
        val spec = when (job.state) {
            JobState.PAUSED -> when {
                job.pauseReason == PauseReason.MEMORY -> BannerSpec(BannerKind.TX_MEMORY)
                checkpoint -> BannerSpec(BannerKind.TX_PAUSED, BannerArgs(ms1 = job.doneMs, ms2 = job.totalMs))
                else -> BannerSpec(BannerKind.TX_PAUSED_EARLY)
            }
            JobState.FAILED -> {
                val final = finalFailure(job, noNewVersion)
                failed(job.failure.orEmpty(), checkpoint, failureText, requiredFreeBytes, final)
            }
            JobState.QUEUED, JobState.RUNNING, JobState.NEEDS_CONSENT -> null
        } ?: return null
        return Notice(spec, job.updatedAt)
    }

    /**
     * Whether [job] failed in a way that every new try repeats: the document was refused, or it cannot take a new
     * version (architecture 12). Its TX_FAILED banner offers no Try again, and its dismissal removes the job row.
     */
    fun finalFailure(job: JobEntity, noNewVersion: Boolean): Boolean {
        if (job.state != JobState.FAILED) return false
        val refused = job.failure.orEmpty().startsWith(JobFailure.DOCUMENT_PREFIX)
        return refused || (noNewVersion && job.kind == JobKind.TRANSCRIBE_AGAIN)
    }

    private fun failed(
        code: String,
        checkpoint: Boolean,
        failureText: String?,
        requiredFreeBytes: Long,
        final: Boolean,
    ): BannerSpec {
        JobFailure.downloadError(code)?.let { return download(it, requiredFreeBytes) }
        return when (code) {
            JobFailure.CheckpointMismatch.code -> BannerSpec(BannerKind.TX_CHECKPOINT_MISMATCH)
            Failure.MEMORY.name -> BannerSpec(BannerKind.TX_MEMORY)
            else -> BannerSpec(
                BannerKind.TX_FAILED,
                BannerArgs(text = failureText),
                // A final failure has nothing to try, so the user can dismiss it.
                dismissible = final,
                actions = when {
                    final -> persistentListOf()
                    checkpoint -> persistentListOf(BannerAction.TRY_AGAIN, BannerAction.START_OVER)
                    else -> BannerKind.TX_FAILED.actions
                },
            )
        }
    }

    // Model download (13.4; the Model download screen and the Library). ------------------------------------------

    /** The Model download screen's banner: the progress of the running install, else the last failure. */
    fun modelDownload(progress: DownloadProgress?, failure: DownloadFailure?): BannerSpec? = when {
        progress != null -> downloading(progress)
        failure != null -> download(failure.error, failure.requiredFreeBytes)
        else -> null
    }

    /** DL_DOWNLOADING or, while the files are verified, DL_CHECKING. */
    fun downloading(progress: DownloadProgress): BannerSpec = if (progress.verifying) {
        BannerSpec(BannerKind.DL_CHECKING, BannerArgs(percent = progress.percent))
    } else {
        BannerSpec(
            BannerKind.DL_DOWNLOADING,
            BannerArgs(
                percent = progress.percent,
                bytes = progress.doneBytes,
                bytes2 = progress.totalBytes,
                part = partUi(progress.part),
            ),
        )
    }

    /** The failure banner of a required part's [error]. */
    fun download(error: DownloadError, requiredFreeBytes: Long = 0, announce: Boolean = false): BannerSpec =
        when (error) {
            DownloadError.NO_CONNECTION -> BannerSpec(BannerKind.DL_NO_CONNECTION, announce = announce)
            DownloadError.INTERRUPTED, DownloadError.HOST_NOT_ALLOWED ->
                BannerSpec(BannerKind.DL_STOPPED, announce = announce)
            DownloadError.NOT_ENOUGH_STORAGE ->
                BannerSpec(BannerKind.DL_NO_SPACE, BannerArgs(bytes = requiredFreeBytes), announce = announce)
            DownloadError.FILE_CHECK_FAILED -> BannerSpec(BannerKind.DL_FILE_CHECK, announce = announce)
            DownloadError.INSTALL -> BannerSpec(BannerKind.DL_INSTALL_FAILED, announce = announce)
        }

    fun partUi(part: ModelPart): ModelPartUi = when (part) {
        ModelPart.SPEECH -> ModelPartUi.SPEECH
        ModelPart.SPEAKERS -> ModelPartUi.SPEAKERS
        ModelPart.VOICES -> ModelPartUi.VOICES
    }

    // Settings and Saved voices (13.5). ----------------------------------------------------------------------------

    /** Settings: "Notifications are off" whenever they are; "Saved voices can't be read" for a newer or bad file. */
    fun settings(facts: NoticeFacts): ImmutableList<BannerSpec> {
        val notices = mutableListOf<Notice>()
        if (facts.notificationsOff) notices += Notice(BannerSpec(BannerKind.NOTIFICATIONS_OFF))
        if (facts.voicesProblem == VoiceProblem.NEWER || facts.voicesProblem == VoiceProblem.UNREADABLE) {
            notices += Notice(BannerSpec(BannerKind.VOICES_UNREADABLE))
        }
        return ordered(notices)
    }

    // Order. ---------------------------------------------------------------------------------------------------

    /** Failed, attention, info, then fine and plain; newest first within a tone (a stable sort keeps the rest). */
    private fun ordered(notices: List<Notice>): ImmutableList<BannerSpec> =
        notices.sortedWith(compareBy<Notice> { rank(it.spec.kind.tone) }.thenByDescending { it.at })
            .distinctBy { it.spec.id }
            .map { it.spec }
            .toImmutableList()

    /** The place of [tone] in a banner list: failed, attention, info, then the fine and plain tones together. */
    fun rank(tone: Tone): Int = TONE_ORDER.indexOf(tone).takeIf { it >= 0 } ?: TONE_ORDER.size

    private val TONE_ORDER = listOf(Tone.Error, Tone.Warn, Tone.Info)

    private const val MINUTE_MS = 60_000L
}
