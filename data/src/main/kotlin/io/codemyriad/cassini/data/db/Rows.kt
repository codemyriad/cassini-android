package io.codemyriad.cassini.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Relation
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.JobKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.core.library.Origin
import io.codemyriad.cassini.core.library.PauseReason

// Query results of the DAOs. They hold domain values; ViewModels in :app turn them into UiState.

/** SQL fragments shared by the Library and search queries. Aliases: n = notes, t = note_text, j = jobs, v = variants. */
internal object Sql {
    /** The readable variant shown for note `n`: explicit selection, else readable default, else first readable. */
    // Older Android SQLite resolves outer columns in WHERE, but not in a subquery ORDER BY.
    const val SELECTED_VARIANT =
        "COALESCE((SELECT s.variant_id FROM variants s WHERE s.note_id = n.id " +
            "AND s.variant_id = n.selected_variant AND s.readable = 1), " +
            "(SELECT d.variant_id FROM variants d WHERE d.note_id = n.id AND d.readable = 1 " +
            "ORDER BY d.is_default DESC, d.ordinal LIMIT 1))"

    /** The [LibraryRow] columns. */
    const val LIBRARY_COLUMNS =
        "n.id, n.title, n.created_at, n.duration_ms, n.audio_kind, n.document_state, " +
            "n.origin, n.recorded_at, n.capture_started_at, " +
            "COALESCE(n.preview, NULLIF(substr(t.body, 1, 160), '')) AS preview, " +
            "j.state AS job_state, j.kind AS job_kind, j.pause_reason, j.failure, j.done_ms, j.total_ms, " +
            "v.variant_id AS selected_variant, v.word_count, v.speaker_count, v.word_ends_bounded_by_audio"

    /** The joins [LIBRARY_COLUMNS] needs, after `FROM notes n` or a join that names `n`. */
    const val LIBRARY_JOINS =
        "LEFT JOIN note_text t ON t.note_id = n.id " +
            "LEFT JOIN jobs j ON j.note_id = n.id " +
            "LEFT JOIN variants v ON v.note_id = n.id AND v.variant_id = $SELECTED_VARIANT"
}

/**
 * One Library row: a live note with its job (null columns without one) and its selected variant (null columns
 * without a transcript). [preview] is `notes.preview`, else the first 160 characters of the transcript (the caller
 * cuts that at a word), else null.
 */
data class LibraryRow(
    val id: String,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "audio_kind") val audioKind: AudioKind,
    @ColumnInfo(name = "document_state") val documentState: DocumentState?,
    val preview: String?,
    @ColumnInfo(name = "job_state") val jobState: JobState?,
    @ColumnInfo(name = "job_kind") val jobKind: JobKind?,
    @ColumnInfo(name = "pause_reason") val pauseReason: PauseReason?,
    val failure: String?,
    @ColumnInfo(name = "done_ms") val doneMs: Long?,
    @ColumnInfo(name = "total_ms") val totalMs: Long?,
    @ColumnInfo(name = "selected_variant") val selectedVariant: String?,
    @ColumnInfo(name = "word_count") val wordCount: Int?,
    @ColumnInfo(name = "speaker_count") val speakerCount: Int?,
    val origin: Origin = Origin.RECORDED,
    @ColumnInfo(name = "recorded_at") val recordedAt: String? = null,
    @ColumnInfo(name = "capture_started_at") val captureStartedAt: Long? = null,
    @ColumnInfo(name = "word_ends_bounded_by_audio") val wordEndsBoundedByAudio: Boolean? = null,
    @Relation(parentColumn = "id", entityColumn = "note_id", entity = SpeakerEntity::class)
    val rowSpeakers: List<LibrarySpeakerLabel> = emptyList(),
)

/** Only label facts needed by the Library; the selected variant is chosen by LibraryRow.selectedVariant. */
data class LibrarySpeakerLabel(
    @ColumnInfo(name = "variant_id") val variantId: String,
    val ordinal: Int,
    val label: String,
    @ColumnInfo(name = "speaker_id") val speakerId: String,
    @ColumnInfo(name = "placeholder_number") val placeholderNumber: Int? = null,
)

/**
 * One search result. [snippet] is the FTS snippet of the transcript and [titleSnippet] that of the title, both with
 * `char(2)`/`char(3)` around the matches (`SearchQuery.highlights` turns them into ranges). A title-only match has a
 * [snippet] without markers.
 */
data class SearchRow(
    @Embedded val row: LibraryRow,
    val snippet: String,
    @ColumnInfo(name = "title_snippet") val titleSnippet: String,
    @ColumnInfo(name = "document_signature") val documentSignature: String? = null,
    @Relation(parentColumn = "id", entityColumn = "note_id")
    val speakers: List<SpeakerEntity> = emptyList(),
)

/** One Trash card. */
data class TrashRow(
    val id: String,
    val title: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "trashed_at") val trashedAt: Long,
)

/** One queue entry: a job of a live note and the note's title. */
data class QueueRow(
    @Embedded val job: JobEntity,
    val title: String,
)

/** How many live notes link a speaker of their selected variant to [voiceId] as AUTO or CONFIRMED. */
data class VoiceNoteCount(
    @ColumnInfo(name = "voice_id") val voiceId: String,
    val notes: Int,
)

/** Note and job totals (Settings, Trash, the queue suffix). */
data class Counts(
    val live: Int,
    val trashed: Int,
    val queued: Int,
)

/** A note with every cache row the Note screen shows, except the words (loaded by page from [WordEntity]). */
data class NoteDetails(
    @Embedded val note: NoteEntity,
    @Relation(parentColumn = "id", entityColumn = "note_id")
    val variants: List<VariantEntity>,
    @Relation(parentColumn = "id", entityColumn = "note_id")
    val speakers: List<SpeakerEntity>,
    @Relation(parentColumn = "id", entityColumn = "note_id")
    val job: JobEntity?,
)

/** Every cache row derived from one document, written in one transaction by [CacheDao.replaceCache]. */
data class NoteCache(
    /** The note row with the derived columns (signature, verification result, meeting id, duration) already set. */
    val note: NoteEntity,
    val variants: List<VariantEntity>,
    val speakers: List<SpeakerEntity>,
    val words: List<WordEntity>,
    /** The selected variant's words joined by spaces; "" without a transcript. */
    val body: String,
)
