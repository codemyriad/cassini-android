package org.cassini.android.note

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.cassini.android.library.daysAgo
import org.cassini.android.notices.NoticeFacts
import org.cassini.android.notices.TrashedNote
import org.cassini.android.playback.PlaybackState
import org.cassini.android.processing.NoteJobState
import org.cassini.android.processing.ProcessingState
import org.cassini.android.processing.TranscribeOutcome
import org.cassini.android.recording.RecordingState
import org.cassini.core.checkpoint.CheckpointNames
import org.cassini.core.format.DocumentState
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.library.Origin
import org.cassini.core.models.ModelCatalog
import org.cassini.core.speakers.Match
import org.cassini.core.speakers.SpeakerPrint
import org.cassini.core.speakers.Voice
import org.cassini.data.checkpoint.DraftTranscript
import org.cassini.data.db.JobEntity
import org.cassini.data.db.NoteDetails
import org.cassini.data.db.NoteEntity
import org.cassini.data.db.SpeakerEntity
import org.cassini.data.db.VariantEntity
import org.cassini.data.db.WordEntity
import org.cassini.data.notes.EditOutcome
import org.cassini.data.notes.ExportOutcome
import org.cassini.data.notes.RefreshOutcome
import org.cassini.data.notes.RenameOutcome
import org.cassini.data.notes.TranscriptExport
import org.cassini.data.voices.SavedVoice
import org.cassini.data.voices.SavedVoices
import org.cassini.data.voices.UnenrolOutcome
import org.cassini.data.voices.VoiceEdit
import org.cassini.engine.job.JobPhase

internal const val NOTE = "11111111-2222-4333-8444-555555555555"

/** A document note "Weekly sync" with one variant, three speakers (Speaker 2 a placeholder) and six words. */
internal fun documentNote(
    state: DocumentState = DocumentState.OK,
    job: JobEntity? = null,
    audioKind: AudioKind = AudioKind.DOCUMENT,
    variants: Int = 1,
) = NoteDetails(
    note = NoteEntity(
        id = NOTE,
        title = "Weekly sync",
        createdAt = daysAgo(0),
        addedAt = daysAgo(0),
        origin = Origin.RECORDED,
        audioFile = if (audioKind == AudioKind.DOCUMENT) "document.opus" else "capture.opus",
        audioKind = audioKind,
        durationMs = 4_000,
        documentState = if (audioKind == AudioKind.DOCUMENT) state else null,
        documentSignature = "1:2:abc",
        selectedVariant = if (audioKind == AudioKind.DOCUMENT) "v1" else null,
        positionMs = 0,
        updatedAt = 1,
    ),
    variants = if (audioKind == AudioKind.DOCUMENT) {
        (0 until variants).map { i ->
            VariantEntity(
                noteId = NOTE,
                variantId = "v${i + 1}",
                ordinal = i,
                isDefault = i == 0,
                createdAtUtc = "2026-10-08T07:00:00Z",
                wordCount = WORDS.size,
                speakerCount = 3,
                speakersToldApart = true,
                inferenceMs = 400,
            )
        }
    } else {
        emptyList()
    },
    speakers = if (audioKind == AudioKind.DOCUMENT) SPEAKERS else emptyList(),
    job = job,
)

internal val SPEAKERS = listOf(
    SpeakerEntity(NOTE, "v1", "a", 0, "Giulia", isPlaceholder = false, speakingMs = 1_000, wordCount = 3),
    SpeakerEntity(NOTE, "v1", "b", 1, "Speaker 2", isPlaceholder = true, placeholderNumber = 2, speakingMs = 400, wordCount = 1),
    SpeakerEntity(
        NOTE, "v1", "c", 2, "Paolo", isPlaceholder = false, speakingMs = 6_000, wordCount = 2,
        sampleStartMs = 2_000, sampleEndMs = 3_000,
    ),
)

internal val WORDS = listOf(
    WordEntity(NOTE, "v1", 0, 0, 400, "a", "Ciao"),
    WordEntity(NOTE, "v1", 1, 500, 900, "a", "giovedì"),
    WordEntity(NOTE, "v1", 2, 1_000, 1_400, "b", "Buongiorno"),
    WordEntity(NOTE, "v1", 3, 2_000, 2_300, "c", "Eccomi,"),
    WordEntity(NOTE, "v1", 4, 2_400, 2_900, "c", "giovedi"),
    WordEntity(NOTE, "v1", 5, 3_000, 3_900, "a", "bene."),
)

internal fun job(state: JobState, kind: JobKind = JobKind.TRANSCRIBE, doneMs: Long = 0, failure: String? = null) =
    JobEntity(
        NOTE,
        seq = 1,
        kind = kind,
        state = state,
        doneMs = doneMs,
        totalMs = 4_000,
        failure = failure,
        enqueuedAt = 1,
        updatedAt = 1,
    )

internal fun voice(id: String, name: String) = Voice(
    id = id,
    name = name,
    model = ModelCatalog.voiceprintModel.sha256,
    mean = FloatArray(ModelCatalog.VOICEPRINT_DIM).also { it[0] = 1f },
    count = 1,
    seconds = 30.0,
    createdAt = 0,
    updatedAt = 0,
)

/** A [NotePorts] over flows the test sets; it records every action. */
@Suppress("TooManyFunctions") // one fake per port function
internal class FakeNotePorts : NotePorts {
    val details = MutableStateFlow<NoteDetails?>(documentNote())
    val wordRows = MutableStateFlow(WORDS)
    val printRows = MutableStateFlow<Map<String, SpeakerPrint>>(
        mapOf("b" to SpeakerPrint(FloatArray(ModelCatalog.VOICEPRINT_DIM).also { it[0] = 1f }, 8.0, null)),
    )
    val saved = MutableStateFlow(SavedVoices(listOf(SavedVoice(voice("anna", "Anna Ricci"), 1, false)), null))
    override val voiceModelReady = MutableStateFlow(true)
    override val processing = MutableStateFlow(ProcessingState(loading = false))
    override val recording: MutableStateFlow<RecordingState> = MutableStateFlow(RecordingState.Idle)
    override val notices = MutableStateFlow(NoticeFacts())
    override val playback = MutableStateFlow(PlaybackState())
    val names = MutableStateFlow(CheckpointNames(emptyMap(), emptyMap()))
    var draft: DraftTranscript? = null

    val calls = mutableListOf<String>()

    /** When set, openPlayer waits for it (a long file that prepares). */
    var openGate: CompletableDeferred<Unit>? = null
    val relabels = mutableListOf<Map<String, String>>()
    val exports = mutableListOf<TranscriptExport>()
    val undo = mutableListOf<TrashedNote>()
    var transcribeOutcome: TranscribeOutcome = TranscribeOutcome.Queued(changed = true)
    var askNotifications = false

    /** Sets the job row of the note and its coordinator state. */
    fun setJob(job: JobEntity?, ahead: Int = 0, phase: JobPhase? = null) {
        details.value = details.value?.copy(job = job)
        processing.value = ProcessingState(
            loading = false,
            notes = if (job == null) emptyMap() else mapOf(NOTE to NoteJobState(job, ahead, phase)),
        )
    }

    override fun note(id: String): Flow<NoteDetails?> = details

    override suspend fun refreshIfStale(id: String): RefreshOutcome {
        calls += "refreshIfStale"
        return RefreshOutcome.Fresh
    }

    override suspend fun words(id: String, variantId: String, from: Int, limit: Int): List<WordEntity> {
        calls += "words:$from"
        return wordRows.value.drop(from).take(limit)
    }

    override suspend fun prints(id: String, variantId: String): Map<String, SpeakerPrint> = printRows.value

    override fun people(): Flow<SavedVoices> = saved

    override fun draftNames(id: String): Flow<CheckpointNames> = names

    override suspend fun loadDraft(id: String): DraftTranscript? = draft

    override suspend fun documentFacts(id: String, variantId: String?): DocumentFacts {
        calls += "documentFacts"
        return DocumentFacts(fileBytes = 1_234, model = "Parakeet")
    }

    override suspend fun openPlayer(id: String, audioFile: String, positionMs: Long): Boolean {
        calls += "openPlayer:$audioFile"
        openGate?.await()
        playback.value = PlaybackState(noteId = id, durationMs = 4_000, positionMs = positionMs)
        return true
    }

    override suspend fun reloadPlayer(id: String, audioFile: String): Boolean {
        calls += "reloadPlayer"
        return true
    }

    override fun togglePlay() {
        calls += "toggle"
    }

    override fun play() {
        calls += "play"
    }

    override fun seekTo(positionMs: Long) {
        calls += "seek:$positionMs"
        playback.value = playback.value.copy(positionMs = positionMs)
    }

    override fun skip(deltaMs: Long) {
        calls += "skip:$deltaMs"
    }

    override fun setSpeed(speed: Float) {
        calls += "speed:$speed"
        playback.value = playback.value.copy(speed = speed)
    }

    override fun playRange(id: String, startMs: Long, endMs: Long): Boolean {
        calls += "playRange:$startMs-$endMs"
        return true
    }

    override fun leavePlayer(id: String) {
        calls += "leavePlayer"
    }

    override suspend fun transcribe(id: String, kind: JobKind): TranscribeOutcome {
        calls += "transcribe:$kind"
        return transcribeOutcome
    }

    override suspend fun startOver(id: String): TranscribeOutcome {
        calls += "startOver"
        return transcribeOutcome
    }

    override fun stop(id: String) {
        calls += "stop"
    }

    override suspend fun dequeue(id: String) {
        calls += "dequeue"
    }

    override suspend fun clearFailed(id: String) {
        calls += "clearFailed"
    }

    override suspend fun continueQueue() {
        calls += "continue"
    }

    override fun cancelDownload() {
        calls += "cancelDownload"
    }

    override suspend fun moveToTrash(id: String): Boolean {
        calls += "moveToTrash"
        details.value = details.value?.let { it.copy(note = it.note.copy(trashedAt = 5)) }
        return true
    }

    override fun offerUndo(note: TrashedNote) {
        undo += note
    }

    override suspend fun rename(id: String, title: String): RenameOutcome {
        calls += "rename:$title"
        return RenameOutcome.Renamed
    }

    override suspend fun selectVariant(id: String, variantId: String): Boolean {
        calls += "select:$variantId"
        return true
    }

    override suspend fun acknowledgeTrust(id: String) {
        calls += "acknowledge"
    }

    override suspend fun relabel(id: String, labels: Map<String, String>): EditOutcome {
        relabels += labels
        return if (details.value?.job != null) EditOutcome.JobPending else EditOutcome.Done()
    }

    override suspend fun mergeSpeakers(id: String, variantId: String, source: String, target: String): EditOutcome {
        calls += "merge:$source>$target"
        return EditOutcome.Done("v2")
    }

    override suspend fun copyPrints(id: String, fromVariant: String, toVariant: String, without: String?): Boolean {
        calls += "copyPrints:$fromVariant>$toVariant-$without"
        return true
    }

    override suspend fun enrol(id: String, variantId: String, speakerId: String, voiceId: String?, name: String): Voice? {
        calls += "enrol:$speakerId:$voiceId:$name"
        return voice(voiceId ?: "new", name)
    }

    override suspend fun unenrol(id: String, variantId: String, speakerId: String): UnenrolOutcome {
        calls += "unenrol:$speakerId"
        return UnenrolOutcome.Unenrolled
    }

    override suspend fun setMatch(id: String, variantId: String, speakerId: String, match: Match?): Boolean {
        calls += "setMatch:$speakerId:${match?.state}"
        return true
    }

    override suspend fun renameVoice(voiceId: String, name: String): VoiceEdit {
        calls += "renameVoice:$voiceId:$name"
        return VoiceEdit.Done
    }

    override fun draft(id: String): DraftNaming = object : DraftNaming {
        override suspend fun confirm(speakerId: String, voiceId: String, name: String, score: Float): Boolean {
            calls += "draft.confirm:$speakerId:$voiceId"
            return true
        }

        override suspend fun reject(speakerId: String, voiceId: String, voiceName: String?): Boolean {
            calls += "draft.reject:$speakerId:$voiceId"
            return true
        }

        override suspend fun rename(speakerId: String, name: String): Boolean {
            calls += "draft.rename:$speakerId:$name"
            return true
        }

        override suspend fun merge(source: String, target: String, targetLabel: String?): Boolean {
            calls += "draft.merge:$source>$target"
            return true
        }

        override suspend fun remember(speakerId: String, voiceId: String?): Boolean {
            calls += "draft.remember:$speakerId:$voiceId"
            return true
        }
    }

    override suspend fun exportCopy(id: String, dest: String): ExportOutcome {
        calls += "exportCopy:$dest"
        return ExportOutcome.Saved(10)
    }

    override suspend fun exportTranscript(id: String, request: TranscriptExport, dest: String): ExportOutcome {
        exports += request
        return ExportOutcome.Saved(10)
    }

    override suspend fun displayName(dest: String): String? = "Weekly sync (1).txt"

    /** The next share file; null makes it fail. Each one is `file:///share/<n>/<name>`. */
    var shareRoot: String? = "file:///share"

    override suspend fun shareTarget(name: String): ShareTarget? = shareRoot?.let { root ->
        val n = calls.count { it.startsWith("shareTarget") }
        calls += "shareTarget:$name"
        ShareTarget("$root/$n/$name", "content://org.cassini.android.share/share/$n/$name")
    }

    override fun placeholderLabel(number: Int): String = "Speaker $number"

    override fun exportSubtitle(createdAt: Long, durationMs: Long, speakers: Int): String = "8 Oct 2026 · $speakers"

    override fun exportEmptyText(): String = "No words"

    override fun failureText(code: String): String? = "failed: $code"

    override suspend fun requiredFreeBytes(): Long = 900

    override suspend fun shouldAskNotifications(): Boolean = askNotifications

    override suspend fun markNotificationsAsked() {
        askNotifications = false
        calls += "markAsked"
    }

    override fun refreshNotices() {
        calls += "refreshNotices"
    }

    override fun snackbarTimeoutMs(): Long = 12_000
}
