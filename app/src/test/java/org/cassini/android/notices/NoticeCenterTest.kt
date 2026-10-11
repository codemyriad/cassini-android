package org.cassini.android.notices

import org.cassini.android.recording.AfterFinish
import org.cassini.android.recording.CaptureFormat
import org.cassini.android.recording.RecordingOutcome
import org.cassini.android.recording.RecordingPause
import org.cassini.android.recording.RecordingState
import org.cassini.android.recording.RecordingWarning
import org.cassini.core.Failure
import org.cassini.core.audio.Limits
import org.cassini.core.audio.RecordingSpace
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
import org.cassini.ui.banner.BannerGroup
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.banner.BannerSpec
import org.cassini.ui.format.ModelPartUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every app-emitted banner row of features.md 13.2-13.5 comes out of [Notices] from a constructed state, with the
 * catalogue's arguments and actions; the lists are ordered failed, attention, info, plain, newest first within a
 * tone. The trust rows of 13.1 are the Note ViewModel's (P10 step 4).
 */
class NoticeCenterTest {
    private val active = RecordingState.Active(
        noteId = "rec",
        title = "Standup",
        defaultTitle = "Voice note",
        format = CaptureFormat.OPUS,
        sampleRateHz = 48_000,
        elapsedMs = 60_000,
        pause = null,
        space = RecordingSpace(spaceMs = 3_600_000, capMs = 7_140_000),
        warning = null,
    )

    private fun job(state: JobState, doneMs: Long = 0, reason: PauseReason? = null, failure: String? = null) =
        JobEntity(
            noteId = "n1",
            seq = 1,
            kind = JobKind.TRANSCRIBE,
            state = state,
            pauseReason = reason,
            failure = failure,
            doneMs = doneMs,
            totalMs = 2_052_000,
            enqueuedAt = 1,
            updatedAt = 2,
        )

    private fun only(list: List<BannerSpec>): BannerSpec {
        assertEquals(list.toString(), 1, list.size)
        return list.single()
    }

    // 13.2 Recording.

    @Test
    fun recordingRows() {
        assertEquals(
            BannerSpec(BannerKind.REC_PAUSED_OTHER_APP, announce = true),
            only(Notices.recording(active.copy(pause = RecordingPause.OTHER_APP), micOff = false)),
        )
        assertTrue(Notices.recording(active.copy(pause = RecordingPause.USER), micOff = false).isEmpty())
        assertEquals(
            BannerSpec(BannerKind.REC_STORAGE_LOW, BannerArgs(ms1 = 600_000), announce = true),
            only(Notices.recording(active.copy(warning = RecordingWarning.StorageLow(10)), micOff = false)),
        )
        assertEquals(
            BannerSpec(BannerKind.REC_NEAR_LIMIT, BannerArgs(ms1 = 300_000), announce = true),
            only(Notices.recording(active.copy(warning = RecordingWarning.NearTimeLimit(300_000)), micOff = false)),
        )
        val stopped = only(Notices.recording(RecordingState.Ended("n", RecordingOutcome.StoppedForSpace), false))
        assertEquals(BannerKind.REC_STOPPED_STORAGE, stopped.kind)
        assertEquals(Limits.MIN_RECORDING_FREE_BYTES, stopped.args.bytes)
        assertEquals(listOf(BannerAction.OPEN_NOTE), stopped.actions)
        assertEquals(
            BannerKind.REC_MIC_OFF,
            only(Notices.recording(RecordingState.Idle, micOff = true)).kind,
        )
        assertEquals(listOf(BannerAction.OPEN_SETTINGS), only(Notices.recording(RecordingState.Idle, true)).actions)
        assertEquals(
            BannerKind.REC_MIC_FAILED,
            only(Notices.recording(RecordingState.Idle, false, ended = RecordingOutcome.MicrophoneFailed)).kind,
        )
        val start = only(Notices.recording(RecordingState.Ended(null, RecordingOutcome.CouldNotStart), false))
        assertEquals(BannerKind.REC_COULD_NOT_START, start.kind)
        assertEquals(listOf(BannerAction.TRY_AGAIN), start.actions)
        val saved = RecordingState.Ended("n", RecordingOutcome.Saved(AfterFinish.TRANSCRIBE))
        assertTrue(Notices.recording(saved, false).isEmpty())
    }

    @Test
    fun recoveredRow() {
        val facts = NoticeFacts(recovered = RecoveredNotice(2, "n9", 312_000, 50))
        val spec = only(Notices.library(facts, LibraryFacts(loading = true)))
        assertEquals(BannerKind.RECOVERED, spec.kind)
        assertEquals(BannerArgs(count = 2, ms1 = 312_000), spec.args)
        assertEquals(listOf(BannerAction.OPEN), spec.actions)
        assertTrue(spec.dismissible)
    }

    // 13.3 Transcription.

    @Test
    fun transcriptionRows() {
        val paused = only(Notices.job(job(JobState.PAUSED, doneMs = 760_000, reason = PauseReason.USER)))
        assertEquals(BannerKind.TX_PAUSED, paused.kind)
        assertEquals(BannerArgs(ms1 = 760_000, ms2 = 2_052_000), paused.args)
        assertEquals(listOf(BannerAction.RESUME, BannerAction.START_OVER), paused.actions)
        assertEquals(
            BannerKind.TX_PAUSED,
            only(Notices.job(job(JobState.PAUSED, doneMs = 1, reason = PauseReason.SYSTEM))).kind,
        )
        assertEquals(
            BannerKind.TX_PAUSED_EARLY,
            only(Notices.job(job(JobState.PAUSED, reason = PauseReason.INTERRUPTED))).kind,
        )
        assertEquals(
            BannerKind.TX_MEMORY,
            only(Notices.job(job(JobState.PAUSED, doneMs = 5, reason = PauseReason.MEMORY))).kind,
        )
        assertEquals(BannerKind.TX_MEMORY, only(Notices.job(job(JobState.FAILED, failure = "MEMORY"))).kind)
        assertEquals(
            BannerKind.TX_CHECKPOINT_MISMATCH,
            only(Notices.job(job(JobState.FAILED, failure = JobFailure.CheckpointMismatch.code))).kind,
        )
        val failed = only(Notices.job(job(JobState.FAILED, failure = "AUDIO"), failureText = "No audio."))
        assertEquals(BannerKind.TX_FAILED, failed.kind)
        assertEquals("No audio.", failed.args.text)
        assertEquals(listOf(BannerAction.TRY_AGAIN), failed.actions)
        assertEquals(
            listOf(BannerAction.TRY_AGAIN, BannerAction.START_OVER),
            only(Notices.job(job(JobState.FAILED, doneMs = 9, failure = "AUDIO"))).actions,
        )
        assertEquals(BannerKind.TX_ONE_VOICE, only(Notices.job(null, oneVoice = true)).kind)

        // A refused document fails the same way on every try: no Try again, and the banner can be dismissed.
        val refused = job(JobState.FAILED, doneMs = 9, failure = "DOCUMENT_UNVERIFIED")
        val final = only(Notices.job(refused, failureText = "Not checked."))
        assertEquals(BannerKind.TX_FAILED, final.kind)
        assertTrue(final.actions.isEmpty())
        assertTrue(final.dismissible)
        assertTrue(Notices.finalFailure(refused, noNewVersion = false))
        val again = job(JobState.FAILED, failure = "AUDIO").copy(kind = JobKind.TRANSCRIBE_AGAIN)
        assertTrue(Notices.finalFailure(again, noNewVersion = true))
        assertFalse(Notices.finalFailure(again, noNewVersion = false))
        assertTrue(only(Notices.job(again, noNewVersion = true)).actions.isEmpty())
        assertTrue(only(Notices.job(null, oneVoice = true, noNewVersion = true)).actions.isEmpty())
        assertTrue(Notices.job(job(JobState.QUEUED)).isEmpty())
        assertTrue(Notices.job(job(JobState.RUNNING)).isEmpty())

        val held = only(Notices.library(NoticeFacts(hold = true, queued = 3), LibraryFacts(loading = true)))
        assertEquals(BannerSpec(BannerKind.TX_QUEUE_HELD, BannerArgs(count = 3)), held)
        assertTrue(Notices.library(NoticeFacts(hold = true, queued = 0), LibraryFacts(loading = true)).isEmpty())
    }

    // 13.4 Model download.

    @Test
    fun downloadRows() {
        val progress = DownloadProgress(ModelPart.SPEAKERS, doneBytes = 296, totalBytes = 640, verifying = false)
        val downloading = Notices.modelDownload(progress, null)!!
        assertEquals(BannerKind.DL_DOWNLOADING, downloading.kind)
        assertEquals(BannerArgs(percent = 46, bytes = 296, bytes2 = 640, part = ModelPartUi.SPEAKERS), downloading.args)
        assertEquals(listOf(BannerAction.CANCEL), downloading.actions)
        val checking = Notices.modelDownload(progress.copy(doneBytes = 512, verifying = true), null)!!
        assertEquals(BannerSpec(BannerKind.DL_CHECKING, BannerArgs(percent = 80)), checking)
        assertEquals(
            BannerKind.DL_DOWNLOADING,
            only(Notices.job(job(JobState.RUNNING), phase = JobPhase.Downloading(progress))).kind,
        )

        val kinds = mapOf(
            DownloadError.NO_CONNECTION to BannerKind.DL_NO_CONNECTION,
            DownloadError.INTERRUPTED to BannerKind.DL_STOPPED,
            DownloadError.HOST_NOT_ALLOWED to BannerKind.DL_STOPPED,
            DownloadError.STOPPED_BY_SYSTEM to BannerKind.DL_STOPPED,
            DownloadError.NOT_ENOUGH_STORAGE to BannerKind.DL_NO_SPACE,
            DownloadError.FILE_CHECK_FAILED to BannerKind.DL_FILE_CHECK,
            DownloadError.INSTALL to BannerKind.DL_INSTALL_FAILED,
        )
        for ((error, kind) in kinds) {
            assertEquals(kind, Notices.modelDownload(null, DownloadFailure(error, 901_000_000))!!.kind)
            val code = JobFailure.DOWNLOAD_PREFIX + error.name
            assertEquals(kind, only(Notices.job(job(JobState.FAILED, failure = code), requiredFreeBytes = 5)).kind)
        }
        assertEquals(901_000_000L, Notices.download(DownloadError.NOT_ENOUGH_STORAGE, 901_000_000).args.bytes)
        // Android stopped it: the body says so, not that the connection dropped.
        assertTrue(Notices.download(DownloadError.STOPPED_BY_SYSTEM).args.bySystem)
        assertFalse(Notices.download(DownloadError.INTERRUPTED).args.bySystem)
        assertEquals(listOf(BannerAction.DOWNLOAD_AGAIN), Notices.download(DownloadError.FILE_CHECK_FAILED).actions)

        val library = Notices.library(
            NoticeFacts(downloadFailure = DownloadFailure(DownloadError.NO_CONNECTION)),
            LibraryFacts(loading = true),
        )
        assertEquals(BannerKind.DL_NO_CONNECTION, only(library).kind)

        val recordNow = Notices.library(NoticeFacts(consented = false), LibraryFacts(liveNotes = 1, audioOnly = true))
        assertEquals(BannerSpec(BannerKind.DL_RECORD_NOW), only(recordNow))
        val audioOnly = LibraryFacts(liveNotes = 1, audioOnly = true)
        assertTrue(Notices.library(NoticeFacts(consented = true), audioOnly).isEmpty())
        assertTrue(Notices.library(NoticeFacts(consented = false), LibraryFacts(liveNotes = 1)).isEmpty())
    }

    // 13.5 Library and import.

    @Test
    fun libraryAndImportRows() {
        assertEquals(
            BannerSpec(BannerKind.LIB_EMPTY),
            only(Notices.library(NoticeFacts(), LibraryFacts(liveNotes = 0))),
        )
        assertEquals(listOf(BannerAction.RECORD, BannerAction.IMPORT), BannerKind.LIB_EMPTY.actions)
        val loading = LibraryFacts(loading = true)
        assertTrue("no empty banner while loading", Notices.library(NoticeFacts(), loading).isEmpty())
        assertEquals(
            BannerSpec(BannerKind.LIB_NO_MATCH, BannerArgs(text = "fattura")),
            only(Notices.library(NoticeFacts(), LibraryFacts(liveNotes = 0, query = " fattura ", results = 0))),
        )
        assertTrue(Notices.library(NoticeFacts(), LibraryFacts(liveNotes = 3, query = "x", results = null)).isEmpty())

        val importing = only(
            Notices.library(NoticeFacts(imports = ImportState(running = listOf("call.m4a"))), loading),
        )
        assertEquals(BannerSpec(BannerKind.IMPORTING, BannerArgs(text = "call.m4a"), announce = true), importing)

        val failures = mapOf(
            Failure.LARGE to BannerKind.IMPORT_TOO_LARGE,
            Failure.SPACE to BannerKind.IMPORT_NO_SPACE,
            Failure.WAV to BannerKind.IMPORT_WAV,
            Failure.OPEN to BannerKind.IMPORT_OPEN,
            Failure.SAVE to BannerKind.IMPORT_OPEN,
        )
        for ((failure, kind) in failures) {
            val facts = NoticeFacts(imports = ImportState(failure = ImportFailure(failure, 1_400_000_000)))
            val spec = only(Notices.library(facts, LibraryFacts(loading = true)))
            assertEquals(kind, spec.kind)
            assertTrue("import errors close until the next import", spec.dismissible)
        }
        val noSpace = ImportState(failure = ImportFailure(Failure.SPACE, 1_400_000_000))
        assertEquals(
            1_400_000_000L,
            only(Notices.library(NoticeFacts(imports = noSpace), LibraryFacts(loading = true))).args.bytes,
        )

        val encoder = only(Notices.library(NoticeFacts(encoderMissing = true, m4a = true), loading))
        assertEquals(BannerSpec(BannerKind.NO_ENCODER, BannerArgs(m4a = true)), encoder)
        assertTrue(encoder.dismissible)

        assertEquals(
            BannerKind.NOTIFICATIONS_OFF,
            only(Notices.library(NoticeFacts(notificationsOff = true, jobRunning = true), loading)).kind,
        )
        assertTrue(
            "the Library tells only while a job runs",
            Notices.library(NoticeFacts(notificationsOff = true), LibraryFacts(loading = true)).isEmpty(),
        )
        assertEquals(BannerKind.NOTIFICATIONS_OFF, only(Notices.settings(NoticeFacts(notificationsOff = true))).kind)
        assertEquals(
            BannerKind.VOICES_UNREADABLE,
            only(Notices.settings(NoticeFacts(voicesProblem = VoiceProblem.NEWER))).kind,
        )
        assertEquals(
            BannerKind.VOICES_UNREADABLE,
            only(Notices.settings(NoticeFacts(voicesProblem = VoiceProblem.UNREADABLE))).kind,
        )
    }

    /** Every app-emitted kind outside the trust rows is produced above; the catalogue-only kinds never are. */
    @Test
    fun everyAppEmittedRowIsProduced() {
        val produced = produceAll().map { it.kind }.toSet()
        val expected = BannerKind.entries.filter { it.appEmitted && it.group != BannerGroup.TRUST }.toSet()
        assertEquals(expected - produced, emptySet<BannerKind>())
        assertTrue(BannerKind.TX_CALCULATING !in produced && BannerKind.TX_BATTERY !in produced)
    }

    @Test
    fun ordersFailedAttentionInfoPlainNewestFirst() {
        val facts = NoticeFacts(
            hold = true,
            queued = 1,
            recovered = RecoveredNotice(1, "n", 1, latestAt = 10),
            consented = false,
            encoderMissing = true,
            imports = ImportState(running = listOf("a.mp3"), failure = ImportFailure(Failure.WAV, at = 5)),
            downloadFailure = DownloadFailure(DownloadError.NO_CONNECTION, at = 9),
        )
        val kinds = Notices.library(facts, LibraryFacts(liveNotes = 0, audioOnly = true)).map { it.kind }
        assertEquals(
            listOf(
                BannerKind.DL_NO_CONNECTION, // error, at 9
                BannerKind.IMPORT_WAV, // error, at 5
                BannerKind.TX_QUEUE_HELD, // warn, catalogue order
                BannerKind.NO_ENCODER,
                BannerKind.RECOVERED, // info, at 10
                BannerKind.DL_RECORD_NOW,
                BannerKind.IMPORTING,
                BannerKind.LIB_EMPTY, // plain
            ),
            kinds,
        )
    }

    private fun produceAll(): List<BannerSpec> = buildList {
        addAll(Notices.recording(active.copy(pause = RecordingPause.OTHER_APP), micOff = true))
        addAll(Notices.recording(active.copy(warning = RecordingWarning.StorageLow(10)), micOff = false))
        addAll(Notices.recording(active.copy(warning = RecordingWarning.NearTimeLimit(1)), micOff = false))
        listOf(RecordingOutcome.StoppedForSpace, RecordingOutcome.MicrophoneFailed, RecordingOutcome.CouldNotStart)
            .forEach { addAll(Notices.recording(RecordingState.Idle, false, ended = it)) }
        addAll(
            Notices.library(
                NoticeFacts(
                    hold = true,
                    queued = 2,
                    recovered = RecoveredNotice(1, "n", 1, 1),
                    consented = false,
                    encoderMissing = true,
                    notificationsOff = true,
                    jobRunning = true,
                    imports = ImportState(running = listOf("x")),
                ),
                LibraryFacts(liveNotes = 0, audioOnly = true),
            ),
        )
        addAll(Notices.library(NoticeFacts(), LibraryFacts(liveNotes = 1, query = "q", results = 0)))
        addAll(Notices.library(NoticeFacts(), LibraryFacts(unavailable = true)))
        listOf(Failure.LARGE, Failure.SPACE, Failure.WAV, Failure.OPEN).forEach { failure ->
            val facts = NoticeFacts(imports = ImportState(failure = ImportFailure(failure)))
            addAll(Notices.library(facts, LibraryFacts()))
        }
        addAll(Notices.settings(NoticeFacts(voicesProblem = VoiceProblem.NEWER)))
        addAll(Notices.job(job(JobState.PAUSED, doneMs = 1)))
        addAll(Notices.job(job(JobState.PAUSED)))
        addAll(Notices.job(job(JobState.PAUSED, reason = PauseReason.MEMORY)))
        addAll(Notices.job(job(JobState.FAILED, failure = JobFailure.CheckpointMismatch.code)))
        addAll(Notices.job(job(JobState.FAILED, failure = "AUDIO")))
        addAll(Notices.job(null, oneVoice = true))
        val progress = DownloadProgress(ModelPart.SPEECH, 1, 2, verifying = false)
        add(Notices.downloading(progress))
        add(Notices.downloading(progress.copy(verifying = true)))
        DownloadError.entries.forEach { add(Notices.download(it)) }
    }
}
