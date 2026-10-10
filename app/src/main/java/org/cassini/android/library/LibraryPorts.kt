package org.cassini.android.library

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.cassini.android.AppGraph
import org.cassini.android.notices.NoticeFacts
import org.cassini.android.notices.TrashedNote
import org.cassini.android.processing.ProcessingState
import org.cassini.android.processing.TranscribeOutcome
import org.cassini.android.recording.RecordingState
import org.cassini.core.library.JobKind
import org.cassini.data.db.LibraryRow
import org.cassini.data.db.NoteDetails
import org.cassini.data.db.SearchRow
import org.cassini.data.notes.RenameOutcome
import org.cassini.ui.banner.BannerKind

/**
 * What the Library ViewModel reads and changes (features.md section 1). [GraphLibraryPorts] binds it to [AppGraph];
 * the ViewModel tests pass a fake.
 */
@Suppress("TooManyFunctions") // one function per Library action
interface LibraryPorts {
    fun library(): Flow<List<LibraryRow>>

    fun search(query: String): Flow<List<SearchRow>>

    /** One note with its variants, speakers and job, for the Note actions sheet. */
    fun details(id: String): Flow<NoteDetails?>

    val processing: Flow<ProcessingState>
    val recording: StateFlow<RecordingState>
    val notices: Flow<NoticeFacts>

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

    /** Imports the picked [uri] in the background; the notices show its progress and errors. */
    fun import(uri: String)

    fun dismissNotice(kind: BannerKind)

    /** Reads the notification permission again (ON_RESUME). */
    fun refreshNotices()

    /** The snackbar time: 10 s, or longer when accessibility settings ask for it. */
    fun snackbarTimeoutMs(): Long

    /** A note that the Note screen moved to trash: the Library shows its Undo snackbar, then [takeTrashed]. */
    val trashed: StateFlow<TrashedNote?>

    fun takeTrashed(note: TrashedNote)
}

/** [LibraryPorts] over the app's graph. */
@Suppress("TooManyFunctions") // one binding per port function
class GraphLibraryPorts(private val graph: AppGraph, context: Context) : LibraryPorts {
    private val app = context.applicationContext

    override fun library(): Flow<List<LibraryRow>> = flow { emitAll(graph.awaitCatalogue().notes.library()) }

    override fun search(query: String): Flow<List<SearchRow>> =
        flow { emitAll(graph.awaitCatalogue().notes.search(query)) }

    override fun details(id: String): Flow<NoteDetails?> = flow { emitAll(graph.awaitCatalogue().notes.note(id)) }

    override val processing: Flow<ProcessingState> get() = graph.processing.state
    override val recording: StateFlow<RecordingState> get() = graph.recording.state
    override val notices: Flow<NoticeFacts> get() = graph.notices.facts

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

    override fun import(uri: String) {
        graph.notices.imports.start(uri)
    }

    override fun dismissNotice(kind: BannerKind) = graph.notices.dismiss(kind)

    override fun refreshNotices() = graph.notices.refreshNotifications()

    override val trashed: StateFlow<TrashedNote?> get() = graph.trashUndo.pending

    override fun takeTrashed(note: TrashedNote) = graph.trashUndo.take(note)

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
