package io.codemyriad.cassini.library

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.core.net.toUri
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.BuildConfig
import io.codemyriad.cassini.core.library.JobKind
import io.codemyriad.cassini.core.library.SearchFilters
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.data.db.LibraryRow
import io.codemyriad.cassini.data.db.NoteDetails
import io.codemyriad.cassini.data.db.SearchRow
import io.codemyriad.cassini.data.models.ModelStatus
import io.codemyriad.cassini.data.notes.RenameOutcome
import io.codemyriad.cassini.notices.NoticeFacts
import io.codemyriad.cassini.notices.TrashedNote
import io.codemyriad.cassini.processing.ProcessingState
import io.codemyriad.cassini.processing.TranscribeOutcome
import io.codemyriad.cassini.recording.RecordingState
import io.codemyriad.cassini.ui.banner.BannerKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the Library ViewModel reads and changes (features.md section 1). [GraphLibraryPorts] binds it to [AppGraph];
 * the ViewModel tests pass a fake.
 */
@Suppress("TooManyFunctions") // one function per Library action
interface LibraryPorts {
    fun library(): Flow<List<LibraryRow>>

    fun search(query: String, filters: SearchFilters, zone: ZoneId, today: LocalDate): Flow<List<SearchRow>>

    fun namedSpeakers(): Flow<List<String>>

    /** Calendar/time-zone broadcasts; repeated zone values still refresh the local day. */
    val calendarZone: Flow<ZoneId>

    /** One note with its variants, speakers and job, for the Note actions sheet. */
    fun details(id: String): Flow<NoteDetails?>

    val processing: Flow<ProcessingState>
    val recording: StateFlow<RecordingState>
    val notices: Flow<NoticeFacts>

    /** The voiceprint model is installed: speaker names can carry over to a new version. */
    val voiceModelReady: Flow<Boolean>

    suspend fun transcribe(id: String, kind: JobKind): TranscribeOutcome

    suspend fun startOver(id: String): TranscribeOutcome

    fun stop(id: String)

    suspend fun dequeue(id: String)

    suspend fun continueQueue()

    fun downloadModels()

    /** Move to trash, stopping a running job first (architecture 5.2 rule 9). */
    suspend fun moveToTrash(id: String): Boolean

    suspend fun restore(id: String): Boolean

    suspend fun rename(id: String, title: String): RenameOutcome

    /** Stars or unstars a note on this device (L-34); never written into its Cassini file (D53.1). */
    suspend fun setStarred(id: String, starred: Boolean)

    /** The Library's `All` / `Starred` choice at rest (L-34): true for `Starred`. */
    val libraryStarredOnly: Flow<Boolean>

    suspend fun setLibraryStarredOnly(starredOnly: Boolean)

    /** Imports the picked [uris] as one background batch with a shared summary. */
    fun import(uris: List<String>)

    fun dismissNotice(kind: BannerKind)

    /** Dismiss on the card of the failed import [id] (L-25, W4.6). */
    fun dismissImport(id: Long)

    /** Reads the notification permission again (ON_RESUME). */
    fun refreshNotices()

    /** The snackbar time: 10 s, or longer when accessibility settings ask for it. */
    fun snackbarTimeoutMs(): Long

    /** A note that the Note screen moved to trash: the Library shows its Undo snackbar, then [takeTrashed]. */
    val trashed: StateFlow<TrashedNote?>

    fun takeTrashed(note: TrashedNote)

    /** Which automatic copies can leave the phone now (D97/D98 choices, the cloud build flag, the API level). */
    val backupReach: Flow<BackupReach>

    /** "Keep on this phone only" (D98): cloud backup and device-to-device transfer off, the card answered. */
    suspend fun keepOnPhoneOnly() { error("Backup choices are unavailable") }

    /** "Keep backup on" or the card's close button: the choices stay, the card does not show again. */
    suspend fun dismissBackupCard() { error("Backup choices are unavailable") }
}

/**
 * The automatic channels that can copy the library off the phone: encrypted cloud Auto Backup ([cloud]) and
 * device-to-device transfer ([transfer]). The first-run hero and privacy line follow them (PARITY Wave 6 contract
 * sections 1-3, D98). [card] shows the once-only "Keep them on this phone only" card while cloud backup is on.
 */
data class BackupReach(val cloud: Boolean, val transfer: Boolean, val card: Boolean = false)

/** [LibraryPorts] over the app's graph. */
@Suppress("TooManyFunctions") // one binding per port function
class GraphLibraryPorts(private val graph: AppGraph, context: Context) : LibraryPorts {
    private val app = context.applicationContext

    override fun library(): Flow<List<LibraryRow>> = flow { emitAll(graph.awaitCatalogue().notes.library()) }

    override fun search(query: String, filters: SearchFilters, zone: ZoneId, today: LocalDate): Flow<List<SearchRow>> =
        flow { emitAll(graph.awaitCatalogue().notes.search(query, filters, zone, today)) }

    override fun namedSpeakers(): Flow<List<String>> = flow { emitAll(graph.awaitCatalogue().notes.namedSpeakers()) }

    override val calendarZone: Flow<ZoneId> = searchCalendar(app, graph.config.zone)

    override fun details(id: String): Flow<NoteDetails?> = flow { emitAll(graph.awaitCatalogue().notes.note(id)) }

    override val processing: Flow<ProcessingState> get() = graph.processing.state
    override val recording: StateFlow<RecordingState> get() = graph.recording.state
    override val notices: Flow<NoticeFacts> get() = graph.notices.facts

    override val voiceModelReady: Flow<Boolean>
        get() = graph.models.status.map { it[ModelPart.VOICES] == ModelStatus.Ready }.distinctUntilChanged()

    override suspend fun transcribe(id: String, kind: JobKind): TranscribeOutcome =
        graph.processing.transcribe(id, kind)

    override suspend fun startOver(id: String): TranscribeOutcome = graph.processing.startOver(id)

    override fun stop(id: String) {
        graph.processing.stop(id)
    }

    override suspend fun dequeue(id: String) {
        graph.processing.dequeue(id)
    }

    override suspend fun continueQueue() = graph.processing.continueQueue()

    override fun downloadModels() {
        graph.processing.downloadModels()
    }

    override suspend fun moveToTrash(id: String): Boolean = graph.processing.moveToTrash(id)

    override suspend fun restore(id: String): Boolean = graph.processing.restore(id)

    override suspend fun rename(id: String, title: String): RenameOutcome =
        graph.awaitCatalogue().notes.rename(id, title)

    override suspend fun setStarred(id: String, starred: Boolean) =
        graph.awaitCatalogue().notes.setStarred(id, starred)

    override val libraryStarredOnly: Flow<Boolean> get() = graph.settings.libraryStarredOnly

    override suspend fun setLibraryStarredOnly(starredOnly: Boolean) = graph.settings.setLibraryStarredOnly(starredOnly)

    override fun import(uris: List<String>) {
        graph.transfers.import(uris.map { it.toUri() })
    }

    override fun dismissNotice(kind: BannerKind) = graph.notices.dismiss(kind)

    override fun dismissImport(id: Long) = graph.notices.imports.dismissCard(id)

    override fun refreshNotices() = graph.notices.refreshNotifications()

    override val trashed: StateFlow<TrashedNote?> get() = graph.trashUndo.pending

    override fun takeTrashed(note: TrashedNote) = graph.trashUndo.take(note)

    override val backupReach: Flow<BackupReach>
        get() = graph.backupChoices.choices.map { choices ->
            BackupReach(
                cloud = choices.cloudActive(Build.VERSION.SDK_INT, BuildConfig.CLOUD_BACKUP_ENABLED),
                transfer = choices.transferActive(Build.VERSION.SDK_INT),
                card = choices.showBackupCard(Build.VERSION.SDK_INT, BuildConfig.CLOUD_BACKUP_ENABLED),
            )
        }.distinctUntilChanged()

    override suspend fun keepOnPhoneOnly() = graph.backupChoices.keepOnPhoneOnly()

    override suspend fun dismissBackupCard() = graph.backupChoices.acknowledgeNotice()

    override fun snackbarTimeoutMs(): Long {
        val manager = app.getSystemService(AccessibilityManager::class.java)
        return if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            manager.getRecommendedTimeoutMillis(
                SNACKBAR_MS,
                AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS,
            ).toLong()
        } else {
            SNACKBAR_MS.toLong()
        }
    }

    private companion object {
        const val SNACKBAR_MS = 10_000
    }
}
