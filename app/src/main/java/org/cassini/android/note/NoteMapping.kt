package org.cassini.android.note

import kotlinx.collections.immutable.toImmutableList
import org.cassini.android.library.LibraryMapping
import org.cassini.android.notices.Notices
import org.cassini.android.processing.NoteJobState
import org.cassini.core.format.DocumentState
import org.cassini.core.format.Transcript
import org.cassini.core.library.AudioKind
import org.cassini.core.library.DayGroups
import org.cassini.core.library.JobState
import org.cassini.core.library.VariantKind
import org.cassini.core.models.ModelCatalog
import org.cassini.data.db.NoteDetails
import org.cassini.data.db.NoteEntity
import org.cassini.data.db.VariantEntity
import org.cassini.engine.job.BatteryLine
import org.cassini.engine.job.DownloadProgress
import org.cassini.engine.job.JobPhase
import org.cassini.engine.job.RunningProgress
import org.cassini.engine.job.Step
import org.cassini.ui.components.DownloadUi
import org.cassini.ui.components.ProcessingCardUi
import org.cassini.ui.components.TrackAnchor
import org.cassini.ui.format.BatteryUi
import org.cassini.ui.format.ProgressHeadline
import org.cassini.ui.format.StageUi
import org.cassini.ui.format.TimeLeftUi
import org.cassini.ui.note.NoteChipsUi
import org.cassini.ui.note.NoteMode
import org.cassini.ui.note.NoteSubtitleUi
import org.cassini.ui.note.ReadOnlyReason
import org.cassini.ui.note.TrustUi
import org.cassini.ui.note.VersionChipUi
import org.cassini.ui.note.VersionKindUi
import org.cassini.ui.note.VersionRowUi
import org.cassini.ui.note.VersionsUi
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException
import org.cassini.engine.job.ProgressHeadline as EngineHeadline

/**
 * The pure mapping from the catalogue and the coordinator to the Note screen's UiState values (features.md section
 * 3; ui.md section 7). The ViewModel calls it; tests call it directly.
 */
@Suppress("TooManyFunctions") // one mapping per UiState part of the Note screen
object NoteMapping {
    /**
     * The name that Document info shows for a provenance [model] id (features.md section 6): "Parakeet Ultra, INT8"
     * for the speech model of this app, and the id as it is for a model that this app does not know.
     */
    fun modelName(model: String): String {
        val (id, name) = ModelCatalog.SPEECH_MODEL_NAMES.entries
            .firstOrNull { (known, _) -> model == known || model.startsWith("$known ") } ?: return model
        val precision = model.removePrefix(id).trim()
        return if (precision.isEmpty()) name else "$name, $precision"
    }

    /** The variant on screen: the selected one, else the default, else the first. */
    fun selected(details: NoteDetails): VariantEntity? {
        val variants = details.variants
        return variants.firstOrNull { it.variantId == details.note.selectedVariant && it.readable }
            ?: variants.firstOrNull { it.isDefault && it.readable }
            ?: variants.filter { it.readable }.minByOrNull { it.ordinal }
    }

    /** The mode of the Note screen (features.md section 3, table). */
    fun mode(details: NoteDetails, job: NoteJobState?, card: ProcessingCardUi?): NoteMode {
        val note = details.note
        val document = note.audioKind == AudioKind.DOCUMENT
        val row = details.job
        return when {
            note.audioKind == AudioKind.UNPLAYABLE -> NoteMode.Unplayable
            row?.state == JobState.QUEUED -> NoteMode.Queued(job?.ahead ?: 0, overDocument = document)
            row?.state == JobState.RUNNING -> NoteMode.Processing(card ?: STARTING_CARD)
            row?.state == JobState.PAUSED -> NoteMode.Paused(row.doneMs, row.totalMs, hasCheckpoint = row.doneMs > 0)
            row?.state == JobState.FAILED -> NoteMode.Failed(overDocument = document)
            document -> NoteMode.Reading
            else -> NoteMode.AudioOnly
        }
    }

    /** Whether the mode shows the draft (the job's words), not the sealed transcript. */
    fun showsDraft(mode: NoteMode): Boolean =
        mode is NoteMode.Processing || mode is NoteMode.Paused || (mode is NoteMode.Failed && !mode.overDocument)

    /** Whether the mode has a player (all but Transcribing and Unplayable). */
    fun hasPlayer(mode: NoteMode): Boolean =
        mode !is NoteMode.Processing && mode != NoteMode.Unplayable && mode != NoteMode.Loading

    /** "Today, 10:02 · 48:13"; null in Reading mode (board 03). */
    fun subtitle(note: NoteEntity, mode: NoteMode, now: Instant, zone: ZoneId): NoteSubtitleUi? {
        if (mode == NoteMode.Reading) return null
        val time = Instant.ofEpochMilli(note.createdAt).atZone(zone).toLocalTime()
        return NoteSubtitleUi(LibraryMapping.dayLabel(DayGroups.key(note.createdAt, now, zone)), time, note.durationMs)
    }

    /** The trust chip of a document state. */
    fun trust(state: DocumentState?): TrustUi = when (state) {
        DocumentState.OK -> TrustUi.VERIFIED
        DocumentState.UNVERIFIED, null, DocumentState.PLAIN_AUDIO -> TrustUi.NOT_VERIFIED
        DocumentState.STALE_AUDIO -> TrustUi.AUDIO_CHANGED
        DocumentState.UNREADABLE, DocumentState.INVALID_METADATA -> TrustUi.UNREADABLE
        DocumentState.UNKNOWN_FORMAT -> TrustUi.NEWER_FORMAT
    }

    /** The chips row of a document: trust, the version chip (2 or more readable variants) and the speakers. */
    fun chips(details: NoteDetails): NoteChipsUi? {
        if (details.note.audioKind != AudioKind.DOCUMENT) return null
        val selected = selected(details)
        val readable = details.variants.count { it.readable }
        val version = selected?.takeIf { readable >= 2 }?.let {
            VersionChipUi(index = it.ordinal + 1, count = details.variants.size, kind = kind(it))
        }
        return NoteChipsUi(trust(details.note.documentState), version, selected?.speakerCount ?: 0)
    }

    /** How a variant came to be (the version chip suffix and the Versions rows). */
    fun kind(variant: VariantEntity): VersionKindUi = when {
        variant.kind == VariantKind.MERGED -> VersionKindUi.MERGED
        variant.ordinal > 0 -> VersionKindUi.TRANSCRIBED_AGAIN
        else -> VersionKindUi.ORIGINAL
    }

    /**
     * Why the sealed transcript cannot be renamed, merged or switched now: a job row on a document (JOB_PENDING),
     * the job's saving step (SAVING, for the draft), or a trust state other than OK (TRUST). Null when it can.
     */
    fun readOnly(details: NoteDetails, mode: NoteMode, namingOpen: Boolean): ReadOnlyReason? {
        val document = details.note.audioKind == AudioKind.DOCUMENT
        return when {
            showsDraft(mode) && !namingOpen -> ReadOnlyReason.SAVING
            showsDraft(mode) -> null
            document && details.job != null && details.job?.state != JobState.NEEDS_CONSENT -> ReadOnlyReason.JOB_PENDING
            document && details.note.documentState != DocumentState.OK -> ReadOnlyReason.TRUST
            else -> null
        }
    }

    /**
     * "All words shown as one voice" (features.md section 3.3): the selected variant has words, no diarization
     * record, and one speaker.
     */
    fun oneVoice(details: NoteDetails): Boolean {
        val selected = selected(details) ?: return false
        return details.job == null && selected.wordCount > 0 && !selected.speakersToldApart && selected.speakerCount == 1
    }

    /** The Versions sheet: one row per variant in manifest order. */
    fun versions(details: NoteDetails, now: Instant, zone: ZoneId): VersionsUi {
        val variants = details.variants.sortedBy { it.ordinal }
        val selected = selected(details)
        val ordinals = variants.associate { it.variantId to it.ordinal }
        val rows = variants.mapIndexed { index, variant ->
            val at = createdAt(variant, details.note).atZone(zone)
            VersionRowUi(
                index = index,
                number = variant.ordinal + 1,
                kind = kind(variant),
                date = at.toLocalDate(),
                time = at.toLocalTime(),
                words = variant.wordCount,
                fromVersion = variant.derivedFrom?.let(ordinals::get)?.plus(1),
                language = Transcript.knownLanguage(variant.language),
                readable = variant.readable,
                withYear = at.year != now.atZone(zone).year,
            )
        }
        return VersionsUi(rows.toImmutableList(), variants.indexOfFirst { it.variantId == selected?.variantId })
    }

    /** When a variant was made: its `createdAtUtc`, else the note's recording time. */
    fun createdAt(variant: VariantEntity, note: NoteEntity): Instant = variant.createdAtUtc?.let {
        try {
            Instant.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    } ?: Instant.ofEpochMilli(note.createdAt)

    // The progress card. ------------------------------------------------------------------------------------------

    /** The progress card of a running job's [phase]; null when nothing runs in this process. */
    fun card(phase: JobPhase?): ProcessingCardUi? = when (phase) {
        null -> null
        JobPhase.Starting -> STARTING_CARD
        JobPhase.WaitingForPrevious -> STARTING_CARD.copy(headline = ProgressHeadline.WAITING_FOR_PREVIOUS)
        JobPhase.Stopping -> STARTING_CARD
        is JobPhase.Downloading -> STARTING_CARD.copy(download = phase.progress?.let(::download))
        is JobPhase.Transcribing -> card(phase.progress)
    }

    /** The card of one tick of the tracker. */
    fun card(progress: RunningProgress): ProcessingCardUi = ProcessingCardUi(
        headline = headline(progress.headline),
        rangeStartMs = progress.rangeStartMs,
        rangeEndMs = progress.rangeEndMs,
        percent = (progress.percent ?: 0).coerceIn(0, MAX_PERCENT),
        stage = stage(progress.step),
        skippedMatching = progress.matchingSkipped,
        timeLeft = LibraryMapping.timeLeft(progress.timeLeft),
        doneMs = progress.doneMs,
        totalMs = progress.totalMs,
        battery = battery(progress.battery),
        words = progress.words,
        lowMemory = progress.lowMemory,
        anchor = progress.anchor?.let {
            TrackAnchor(it.doneFraction, it.windowEndFraction, it.msPerFraction, it.completedAtMs)
        },
    )

    private fun download(progress: DownloadProgress) = DownloadUi(
        part = Notices.partUi(progress.part),
        percent = progress.percent,
        bytes = progress.doneBytes,
        totalBytes = progress.totalBytes,
        checking = progress.verifying,
    )

    private fun headline(headline: EngineHeadline): ProgressHeadline = when (headline) {
        EngineHeadline.STARTING -> ProgressHeadline.STARTING
        EngineHeadline.WAITING_FOR_PREVIOUS -> ProgressHeadline.WAITING_FOR_PREVIOUS
        EngineHeadline.READING_AUDIO -> ProgressHeadline.READING_AUDIO
        EngineHeadline.PREPARING -> ProgressHeadline.PREPARING
        EngineHeadline.FINDING_WORDS_AND_SPEAKERS -> ProgressHeadline.FINDING_WORDS_AND_SPEAKERS
        EngineHeadline.FINDING_WORDS -> ProgressHeadline.FINDING_WORDS
        EngineHeadline.MATCHING_VOICES -> ProgressHeadline.MATCHING_VOICES
        EngineHeadline.SAVING -> ProgressHeadline.SAVING
    }

    private fun stage(step: Step): StageUi = when (step) {
        Step.READING -> StageUi.READING
        Step.PREPARING -> StageUi.PREPARING
        Step.WORDS_AND_VOICES -> StageUi.WORDS_AND_VOICES
        Step.MATCHING -> StageUi.MATCHING
        Step.SAVING -> StageUi.SAVING
    }

    private fun battery(line: BatteryLine): BatteryUi = when (line) {
        BatteryLine.Unavailable -> BatteryUi.Hidden
        is BatteryLine.Measuring -> BatteryUi.Measuring(line.percentNow)
        is BatteryLine.PluggedIn -> BatteryUi.PluggedIn(line.percentNow)
        is BatteryLine.Projected -> BatteryUi.Projected(line.percentNow, line.percentAtFinish)
    }

    /** The card before the tracker's first tick: "Starting…", an indeterminate look at 0 %. */
    val STARTING_CARD = ProcessingCardUi(
        headline = ProgressHeadline.STARTING,
        rangeStartMs = 0,
        rangeEndMs = 0,
        percent = 0,
        stage = StageUi.READING,
        skippedMatching = false,
        timeLeft = TimeLeftUi.Calculating,
        doneMs = 0,
        totalMs = 0,
        battery = BatteryUi.Hidden,
        words = 0,
        lowMemory = false,
    )

    /** The MIME type of "Save a copy" for an audio file extension. */
    fun audioMime(ext: String): String = when (ext.lowercase()) {
        "opus", "ogg", "oga" -> "audio/ogg"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }

    private const val MAX_PERCENT = 99
}
