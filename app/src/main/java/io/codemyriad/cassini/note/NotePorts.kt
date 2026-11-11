package io.codemyriad.cassini.note

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.R
import io.codemyriad.cassini.adapters.InstalledEngines
import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.checkpoint.CheckpointNames
import io.codemyriad.cassini.core.export.MarkdownOptions
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.library.JobKind
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.core.speakers.Match
import io.codemyriad.cassini.core.speakers.SpeakerPrint
import io.codemyriad.cassini.core.speakers.Voice
import io.codemyriad.cassini.data.checkpoint.DraftTranscript
import io.codemyriad.cassini.data.db.NoteDetails
import io.codemyriad.cassini.data.db.WordEntity
import io.codemyriad.cassini.data.models.ModelStatus
import io.codemyriad.cassini.data.notes.EditOutcome
import io.codemyriad.cassini.data.notes.ExportOutcome
import io.codemyriad.cassini.data.notes.ImportSource
import io.codemyriad.cassini.data.notes.RefreshOutcome
import io.codemyriad.cassini.data.notes.RenameOutcome
import io.codemyriad.cassini.data.notes.TranscriptExport
import io.codemyriad.cassini.data.voices.SavedVoices
import io.codemyriad.cassini.data.voices.UnenrolOutcome
import io.codemyriad.cassini.data.voices.VoiceEdit
import io.codemyriad.cassini.engine.job.JobFailure
import io.codemyriad.cassini.inAppLanguage
import io.codemyriad.cassini.library.GraphLibraryPorts
import io.codemyriad.cassini.notices.NoticeFacts
import io.codemyriad.cassini.notices.TrashedNote
import io.codemyriad.cassini.playback.PlaybackState
import io.codemyriad.cassini.processing.DraftOutcome
import io.codemyriad.cassini.processing.ProcessingState
import io.codemyriad.cassini.processing.TranscribeOutcome
import io.codemyriad.cassini.recording.RecordingState
import io.codemyriad.cassini.stringRes
import io.codemyriad.cassini.ui.banner.BannerKind
import io.codemyriad.cassini.ui.format.Counts
import io.codemyriad.cassini.ui.format.Dates
import io.codemyriad.cassini.ui.format.Durations
import io.codemyriad.cassini.ui.format.FormatSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/** The draft-name actions of one note (`ProcessingCoordinator.draft`): true when the change was applied. */
interface DraftNaming {
    suspend fun confirm(speakerId: String, voiceId: String, name: String, score: Float): Boolean

    suspend fun reject(speakerId: String, voiceId: String, voiceName: String?): Boolean

    suspend fun rename(speakerId: String, name: String): Boolean

    suspend fun merge(source: String, target: String, targetLabel: String?): Boolean

    suspend fun remember(speakerId: String, voiceId: String?): Boolean
}

/**
 * The facts of Document info that only the document has (features.md section 6): the file size, the speech model,
 * where a resumed transcription started, and how many names came from saved voices.
 */
data class DocumentFacts(
    val fileBytes: Long,
    val model: String? = null,
    val resumedFromMs: Long? = null,
    val namesFromVoices: Int = 0,
)

/**
 * What the Note ViewModel reads and changes (features.md sections 3, 4 and 6). [GraphNotePorts] binds it to
 * [AppGraph]; the ViewModel tests pass a fake. Nothing here reads the `.opus` file on open: the transcript comes from
 * the Room word cache ([words]), and [refreshIfStale] reads the document only when its signature changed.
 */
@Suppress("TooManyFunctions") // one function per Note screen action
interface NotePorts {
    // Reads.
    fun note(id: String): Flow<NoteDetails?>

    suspend fun refreshIfStale(id: String): RefreshOutcome

    suspend fun words(id: String, variantId: String, from: Int = 0, limit: Int = Int.MAX_VALUE): List<WordEntity>

    suspend fun prints(id: String, variantId: String): Map<String, SpeakerPrint>

    fun people(): Flow<SavedVoices>

    val voiceModelReady: Flow<Boolean>
    val processing: Flow<ProcessingState>
    val recording: StateFlow<RecordingState>
    val notices: Flow<NoticeFacts>

    fun draftNames(id: String): Flow<CheckpointNames>

    suspend fun loadDraft(id: String): DraftTranscript?

    suspend fun documentFacts(id: String, variantId: String?): DocumentFacts

    // The player.
    val playback: StateFlow<PlaybackState>

    suspend fun openPlayer(id: String, audioFile: String, positionMs: Long): Boolean

    suspend fun reloadPlayer(id: String, audioFile: String): Boolean

    fun togglePlay()

    fun play()

    fun seekTo(positionMs: Long)

    fun skip(deltaMs: Long)

    /** The playback speed for this note and the next ones (D53). */
    fun setSpeed(speed: Float)

    fun playRange(id: String, startMs: Long, endMs: Long): Boolean

    /**
     * Leaves the player of note [id] when this screen still owns it (its last [openPlayer]): pause, save the final
     * position and the preview, release. An open of this screen that still prepares then ends without a player.
     */
    fun leavePlayer(id: String)

    // The queue.
    suspend fun transcribe(id: String, kind: JobKind): TranscribeOutcome

    suspend fun startOver(id: String): TranscribeOutcome

    fun stop(id: String)

    suspend fun dequeue(id: String)

    /** Removes a FAILED job row (a dismissed final failure). */
    suspend fun clearFailed(id: String)

    suspend fun continueQueue()

    fun cancelDownload()

    /** Move to trash (the coordinator stops a running job first: `prepareTrash`, then `moveToTrash`). */
    suspend fun moveToTrash(id: String): Boolean

    /** Hands the trashed note to the Library, which shows the Undo snackbar. */
    fun offerUndo(note: TrashedNote)

    // Edits.
    suspend fun rename(id: String, title: String): RenameOutcome

    suspend fun selectVariant(id: String, variantId: String): Boolean

    suspend fun acknowledgeTrust(id: String)

    suspend fun relabel(id: String, labels: Map<String, String>): EditOutcome

    suspend fun mergeSpeakers(id: String, variantId: String, source: String, target: String): EditOutcome

    suspend fun copyPrints(id: String, fromVariant: String, toVariant: String, without: String?): Boolean

    suspend fun enrol(id: String, variantId: String, speakerId: String, voiceId: String?, name: String): Voice?

    suspend fun unenrol(id: String, variantId: String, speakerId: String): UnenrolOutcome

    suspend fun setMatch(id: String, variantId: String, speakerId: String, match: Match?): Boolean

    suspend fun renameVoice(voiceId: String, name: String): VoiceEdit

    fun draft(id: String): DraftNaming

    // Exports. [dest] is the content Uri that the system picker returned.
    suspend fun exportCopy(id: String, dest: String): ExportOutcome

    suspend fun exportTranscript(id: String, request: TranscriptExport, dest: String): ExportOutcome

    /** The name the picker saved [dest] under, or null when the provider does not say. */
    suspend fun displayName(dest: String): String?

    /** A fresh share file named [name] (`ShareFiles`), or null when it cannot be made (a full cache). */
    suspend fun shareTarget(name: String): ShareTarget?

    // Text in the app language, for exports and the documents (the ViewModel has no Resources).
    fun placeholderLabel(number: Int): String

    /** The TXT subtitle line: "8 Oct 2026, 15:10 · 23:50 · 3 speakers" (the speakers part from 2 on). */
    fun exportSubtitle(
        recordedAt: io.codemyriad.cassini.core.format.MeetingTime?,
        durationMs: Long,
        speakers: Int,
    ): String

    /** The TXT body of a transcript without words. */
    fun exportEmptyText(): String

    /** S-5 export copy and the D50.3 absolute date. */
    fun markdownOptions(recordedAt: io.codemyriad.cassini.core.format.MeetingTime?, durationMs: Long): MarkdownOptions

    /** The text of a failed job's stored failure code, or null for a code without its own text. */
    fun failureText(code: String): String?

    // Notices and permissions.
    suspend fun requiredFreeBytes(noteId: String): Long

    /** POST_NOTIFICATIONS should be asked now: API 33+, not granted, and never asked before. */
    suspend fun shouldAskNotifications(): Boolean

    suspend fun markNotificationsAsked()

    fun refreshNotices()

    fun dismissParakeetSuggestion()

    fun snackbarTimeoutMs(): Long
}

/** A share file: [file] is where the export writes (`file://`), [shared] the FileProvider Uri other apps read. */
data class ShareTarget(val file: String, val shared: String)

/** [NotePorts] over the app's graph. */
@Suppress("TooManyFunctions") // one binding per port function
class GraphNotePorts(private val graph: AppGraph, context: Context) : NotePorts {
    private val app = context.applicationContext
    private val library = GraphLibraryPorts(graph, app)

    override fun note(id: String): Flow<NoteDetails?> = flow { emitAll(graph.awaitCatalogue().notes.note(id)) }

    override suspend fun refreshIfStale(id: String): RefreshOutcome = graph.awaitCatalogue().notes.refreshIfStale(id)

    override suspend fun words(id: String, variantId: String, from: Int, limit: Int): List<WordEntity> =
        graph.awaitCatalogue().notes.words(id, variantId, from, limit)

    override suspend fun prints(id: String, variantId: String): Map<String, SpeakerPrint> =
        graph.awaitCatalogue().voices.prints(id, variantId)

    override fun people(): Flow<SavedVoices> = flow { emitAll(graph.awaitCatalogue().voices.people()) }

    override val voiceModelReady: Flow<Boolean>
        get() = graph.models.status.map { it[ModelPart.VOICES] == ModelStatus.Ready }.distinctUntilChanged()
    override val processing: Flow<ProcessingState> get() = graph.processing.state
    override val recording: StateFlow<RecordingState> get() = graph.recording.state
    override val notices: Flow<NoticeFacts> get() = graph.notices.facts

    override fun draftNames(id: String): Flow<CheckpointNames> =
        flow { emitAll(graph.awaitCatalogue().checkpoints.names.observe(id)) }

    override suspend fun loadDraft(id: String): DraftTranscript? = graph.awaitCatalogue().checkpoints.loadDraft(id)

    override suspend fun documentFacts(id: String, variantId: String?): DocumentFacts {
        val catalogue = graph.awaitCatalogue()
        val row = catalogue.database.notes().note(id)
        val file = row?.let { File(graph.paths.note(id), it.audioFile) }
        val bytes = withContext(graph.dispatchers.io) { file?.length() ?: 0L }
        // A user action, not the open: the read comes from the document LRU when the file was read before.
        val document = catalogue.documents.read(id)
        val processing = variantId?.let { variant ->
            document?.manifest?.optJSONObject("provenance")?.optJSONObject("speechToText")?.optJSONObject(variant)
        }
        return DocumentFacts(
            fileBytes = bytes,
            model = processing?.optString("model")?.ifEmpty { null }?.let(NoteMapping::modelName),
            resumedFromMs = processing?.optLong(RESUMED_FROM, 0)?.takeIf { it > 0 },
            namesFromVoices = processing?.optJSONObject(IDENTIFICATION)?.optInt("autoApplied", 0) ?: 0,
        )
    }

    // The player. --------------------------------------------------------------------------------------------------

    override val playback: StateFlow<PlaybackState> get() = graph.playback.state

    // Each Note screen has its own ports object, so this object is the owner of the player it opens: a late leave
    // of a screen that is gone does not close the player of a newer screen of the same note.
    override suspend fun openPlayer(id: String, audioFile: String, positionMs: Long): Boolean =
        withContext(graph.dispatchers.main) {
            graph.playback.open(id, File(graph.paths.note(id), audioFile), positionMs, owner = this@GraphNotePorts)
        }

    override suspend fun reloadPlayer(id: String, audioFile: String): Boolean = withContext(graph.dispatchers.main) {
        graph.playback.state.value.noteId == id &&
            graph.playback.reload(File(graph.paths.note(id), audioFile), owner = this@GraphNotePorts)
    }

    override fun togglePlay() = graph.playback.toggle()

    override fun play() = graph.playback.play()

    override fun seekTo(positionMs: Long) = graph.playback.seekTo(positionMs)

    override fun skip(deltaMs: Long) = graph.playback.skip(deltaMs)

    override fun setSpeed(speed: Float) = graph.playback.setSpeed(speed)

    override fun playRange(id: String, startMs: Long, endMs: Long): Boolean =
        graph.playback.playRange(id, startMs, endMs)

    override fun leavePlayer(id: String) {
        graph.appScope.launch(graph.dispatchers.main) { graph.playback.leave(owner = this@GraphNotePorts) }
    }

    // The queue. ---------------------------------------------------------------------------------------------------

    override suspend fun transcribe(id: String, kind: JobKind): TranscribeOutcome = library.transcribe(id, kind)

    override suspend fun startOver(id: String): TranscribeOutcome = library.startOver(id)

    override fun stop(id: String) = library.stop(id)

    override suspend fun dequeue(id: String) = library.dequeue(id)

    override suspend fun clearFailed(id: String) = graph.processing.clearFailed(id)

    override suspend fun continueQueue() = library.continueQueue()

    override fun cancelDownload() = graph.processing.cancelDownload()

    override suspend fun moveToTrash(id: String): Boolean = graph.processing.moveToTrash(id)

    override fun offerUndo(note: TrashedNote) = graph.trashUndo.offer(note)

    // Edits. -------------------------------------------------------------------------------------------------------

    override suspend fun rename(id: String, title: String): RenameOutcome = library.rename(id, title)

    override suspend fun selectVariant(id: String, variantId: String): Boolean =
        graph.awaitCatalogue().notes.selectVariant(id, variantId)

    override suspend fun acknowledgeTrust(id: String) {
        graph.awaitCatalogue().notes.acknowledgeTrust(id)
    }

    override suspend fun relabel(id: String, labels: Map<String, String>): EditOutcome =
        graph.awaitCatalogue().documents.relabel(id, labels)

    override suspend fun mergeSpeakers(id: String, variantId: String, source: String, target: String): EditOutcome =
        graph.awaitCatalogue().documents.mergeSpeakers(id, variantId, source, target)

    override suspend fun copyPrints(id: String, fromVariant: String, toVariant: String, without: String?): Boolean =
        graph.awaitCatalogue().voices.copyPrints(id, fromVariant, toVariant, without)

    override suspend fun enrol(id: String, variantId: String, speakerId: String, voiceId: String?, name: String) =
        graph.awaitCatalogue().voices.enrol(id, variantId, speakerId, voiceId, name)

    override suspend fun unenrol(id: String, variantId: String, speakerId: String): UnenrolOutcome =
        graph.awaitCatalogue().voices.unenrol(id, variantId, speakerId)

    override suspend fun setMatch(id: String, variantId: String, speakerId: String, match: Match?): Boolean =
        graph.awaitCatalogue().voices.setMatch(id, variantId, speakerId, match)

    override suspend fun renameVoice(voiceId: String, name: String): VoiceEdit =
        graph.awaitCatalogue().voices.rename(voiceId, name)

    override fun draft(id: String): DraftNaming = object : DraftNaming {
        private val actions = graph.processing.draft(id)

        override suspend fun confirm(speakerId: String, voiceId: String, name: String, score: Float) =
            actions.confirm(speakerId, voiceId, name, score) is DraftOutcome.Changed

        override suspend fun reject(speakerId: String, voiceId: String, voiceName: String?) =
            actions.reject(speakerId, voiceId, voiceName) is DraftOutcome.Changed

        override suspend fun rename(speakerId: String, name: String) =
            actions.rename(speakerId, name) is DraftOutcome.Changed

        override suspend fun merge(source: String, target: String, targetLabel: String?) =
            actions.merge(source, target, targetLabel) is DraftOutcome.Changed

        override suspend fun remember(speakerId: String, voiceId: String?) =
            actions.remember(speakerId, voiceId) is DraftOutcome.Changed
    }

    // Exports. -----------------------------------------------------------------------------------------------------

    override suspend fun exportCopy(id: String, dest: String): ExportOutcome =
        graph.awaitCatalogue().documents.exportCopy(id, dest.toUri())

    override suspend fun exportTranscript(id: String, request: TranscriptExport, dest: String): ExportOutcome =
        graph.awaitCatalogue().documents.exportTranscript(id, request, dest.toUri())

    override suspend fun displayName(dest: String): String? = withContext(graph.dispatchers.io) {
        runCatching { ImportSource.of(app.contentResolver).describe(dest.toUri()).displayName }.getOrNull()
    }

    override suspend fun shareTarget(name: String): ShareTarget? = withContext(graph.dispatchers.io) {
        try {
            val file = ShareFiles(File(app.cacheDir, ShareFiles.FOLDER)).target(name)
            val shared = FileProvider.getUriForFile(app, "${app.packageName}.share", file)
            ShareTarget(Uri.fromFile(file).toString(), shared.toString())
        } catch (error: IllegalStateException) {
            Log.w("CassiniNote", "No share file for $name", error)
            null
        }
    }

    // Text. --------------------------------------------------------------------------------------------------------

    private fun localized(): Context = app.inAppLanguage()

    private fun format(context: Context): FormatSettings = FormatSettings(
        locale = context.resources.configuration.locales[0],
        is24Hour = android.text.format.DateFormat.is24HourFormat(app),
        zone = ZoneId.systemDefault(),
    )

    override fun placeholderLabel(number: Int): String = InstalledEngines.speakerLabel(app, number)

    override fun exportSubtitle(
        recordedAt: io.codemyriad.cassini.core.format.MeetingTime?,
        durationMs: Long,
        speakers: Int,
    ): String {
        val context = localized()
        val res = context.resources
        val format = format(context)
        val at = recordedAt?.instant?.atZone(format.zone)
        val parts = listOfNotNull(
            recordedAt?.label ?: at?.let { Dates.longDateTime(res, it.toLocalDate(), it.toLocalTime(), format) },
            Durations.clock(durationMs),
            Counts.speakers(res, speakers, format).takeIf { speakers >= 2 },
        )
        return parts.joinToString(SEPARATOR)
    }

    override fun exportEmptyText(): String = localized().getString(R.string.export_no_words)

    override fun markdownOptions(recordedAt: io.codemyriad.cassini.core.format.MeetingTime?, durationMs: Long): MarkdownOptions {
        val numericLocale = android.content.res.Resources.getSystem().configuration.locales[0]
        val clockPreference = android.provider.Settings.System.getString(
            app.contentResolver,
            android.provider.Settings.System.TIME_12_24,
        )
        val settings = FormatSettings(
            numericLocale,
            NoteMarkdown.is24Hour(numericLocale, clockPreference),
            ZoneId.systemDefault(),
        )
        return NoteMarkdown.options(localized().resources, settings, recordedAt, durationMs)
    }

    override fun failureText(code: String): String? {
        val res = Failure.entries.firstOrNull { it.name == code }?.stringRes ?: when (code) {
            JobFailure.DocumentRefused(DocumentState.UNVERIFIED).code -> R.string.error_document_unverified
            JobFailure.DocumentRefused(DocumentState.UNKNOWN_FORMAT).code -> R.string.error_document_unknown_format
            else -> return null
        }
        return localized().getString(res)
    }

    // Notices. -----------------------------------------------------------------------------------------------------

    override suspend fun requiredFreeBytes(noteId: String): Long {
        val identity = graph.awaitCatalogue().jobs.job(noteId)?.speechIdentity
        val model = identity?.let(SpeechModel::identity) ?: graph.speech.selected()
        return if (model == SpeechModel.INCLUDED) {
            0
        } else {
            withContext(graph.dispatchers.io) { graph.modelAccess.plan().requiredFreeBytes }
        }
    }

    override suspend fun shouldAskNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val granted = ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return !granted && !graph.settings.notificationsAsked.first()
    }

    override suspend fun markNotificationsAsked() = graph.settings.markNotificationsAsked()

    override fun refreshNotices() = graph.notices.refreshNotifications()

    override fun dismissParakeetSuggestion() = graph.notices.dismiss(BannerKind.PARAKEET_RECOMMENDED)

    override fun snackbarTimeoutMs(): Long = library.snackbarTimeoutMs()

    private companion object {
        const val SEPARATOR = " · "

        const val RESUMED_FROM = "x-resumedFromMs"
        const val IDENTIFICATION = "x-speakerIdentification"
    }
}
