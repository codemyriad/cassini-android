package org.cassini.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import org.cassini.core.format.DocumentState
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.library.Origin
import org.cassini.core.library.PauseReason
import org.cassini.core.library.VariantKind
import org.cassini.core.speakers.Match

// The Room catalogue of docs/architecture.md, section 3.3. Room is the authority for the title, trash state, playback
// state and the queue; everything else is a cache of the files under notes/<id>/ and voices/, rebuilt from them.

/** The job a note had when it was trashed, so Restore can bring it back (features.md section 8). */
data class TrashedJob(val state: JobState, val kind: JobKind)

/** One note: a directory `notes/<id>/` and its current audio file. */
@Entity(
    tableName = "notes",
    indices = [Index("created_at"), Index("trashed_at"), Index("source_sha256")],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    /** 1-200 characters, trimmed, never blank. Authoritative; the document holds a copy. */
    val title: String,
    /** The document title differs from [title] and must be rewritten when that is allowed. */
    @ColumnInfo(name = "title_dirty") val titleDirty: Boolean = false,
    /** When the audio was recorded (epoch ms): capture start, the document's `meeting.createdAtUtc`, else import time. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    val origin: Origin,
    /** The file name of the note's current audio inside `notes/<id>/`. */
    @ColumnInfo(name = "audio_file") val audioFile: String,
    @ColumnInfo(name = "audio_kind") val audioKind: AudioKind,
    /** 0 = unknown. */
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0,
    /** Hex SHA-256 of imported bytes, for dedupe; null for recordings. */
    @ColumnInfo(name = "source_sha256") val sourceSha256: String? = null,
    /**
     * The verification result of the document with [documentSignature] (`readChecked` with the audio digest): null
     * unless [audioKind] is DOCUMENT. Stored so that opening a note with an unchanged document never verifies it again.
     */
    @ColumnInfo(name = "document_state") val documentState: DocumentState? = null,
    @ColumnInfo(name = "document_error") val documentError: String? = null,
    /** The reader's warnings for the document with [documentSignature]. */
    @ColumnInfo(name = "document_warnings") val documentWarnings: List<String> = emptyList(),
    /**
     * `org.cassini.data.DocumentSignature` of the file the cache rows (variants, speakers, words, note_text, the
     * verification result) were derived from; null before the first derivation.
     */
    @ColumnInfo(name = "document_signature") val documentSignature: String? = null,
    @ColumnInfo(name = "meeting_id") val meetingId: String? = null,
    /** null = the document's default variant. */
    @ColumnInfo(name = "selected_variant") val selectedVariant: String? = null,
    @ColumnInfo(name = "position_ms") val positionMs: Long = 0,
    /** The Library preview excerpt (`PreviewText` display form); null = the start of the transcript. */
    val preview: String? = null,
    /** "Play anyway" was pressed for a document that is not OK. */
    @ColumnInfo(name = "trust_acknowledged") val trustAcknowledged: Boolean = false,
    /** null = live. */
    @ColumnInfo(name = "trashed_at") val trashedAt: Long? = null,
    @ColumnInfo(name = "trashed_job") val trashedJob: TrashedJob? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** One transcript variant of a note's document, in manifest order. */
@Entity(
    tableName = "variants",
    primaryKeys = ["note_id", "variant_id"],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class VariantEntity(
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "variant_id") val variantId: String,
    val ordinal: Int,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,
    @ColumnInfo(name = "created_at_utc") val createdAtUtc: String? = null,
    /** "" when not identified. */
    val language: String = "",
    val kind: VariantKind = VariantKind.TRANSCRIBED,
    /** `x-derivedFromTranscript` of a merged variant. */
    @ColumnInfo(name = "derived_from") val derivedFrom: String? = null,
    @ColumnInfo(name = "word_count") val wordCount: Int = 0,
    @ColumnInfo(name = "speaker_count") val speakerCount: Int = 0,
    /** The provenance has `x-speakerDiarization`. */
    @ColumnInfo(name = "speakers_told_apart") val speakersToldApart: Boolean = false,
    @ColumnInfo(name = "inference_ms") val inferenceMs: Long? = null,
    val readable: Boolean = true,
    val error: String? = null,
)

/** One speaker of one variant, ordered by first appearance in the words. */
@Entity(
    tableName = "speakers",
    primaryKeys = ["note_id", "variant_id", "speaker_id"],
    foreignKeys = [
        ForeignKey(
            entity = VariantEntity::class,
            parentColumns = ["note_id", "variant_id"],
            childColumns = ["note_id", "variant_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SpeakerEntity(
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "variant_id") val variantId: String,
    @ColumnInfo(name = "speaker_id") val speakerId: String,
    val ordinal: Int,
    /** The manifest label, verbatim. */
    val label: String,
    /** `DefaultLabels.isDefault`. */
    @ColumnInfo(name = "is_placeholder") val isPlaceholder: Boolean,
    @ColumnInfo(name = "placeholder_number") val placeholderNumber: Int? = null,
    @ColumnInfo(name = "speaking_ms") val speakingMs: Long = 0,
    @ColumnInfo(name = "word_count") val wordCount: Int = 0,
    @ColumnInfo(name = "turn_count") val turnCount: Int = 0,
    @ColumnInfo(name = "first_ms") val firstMs: Long = 0,
    /** The "Play a sample" window; null when no turn reaches 1 s. */
    @ColumnInfo(name = "sample_start_ms") val sampleStartMs: Long? = null,
    @ColumnInfo(name = "sample_end_ms") val sampleEndMs: Long? = null,
)

/**
 * The parsed-transcript cache: the words of one variant in canonical order ([index] 0, 1, ...), derived from the
 * document with `notes.document_signature` in the same transaction as the other cache rows. The Note screen renders
 * from these rows, so opening a note with an unchanged document never reads or verifies the `.opus` file. The rows are
 * safe to delete: a variant whose cached word count differs from `variants.word_count` is derived again.
 *
 * A Room table was chosen over a per-note cache file by measurement (WordCacheDeviceTest, 9 500 words = one hour, debug
 * test APK on the API 34 x86_64 emulator, 2026-10-10): the first 200 words load in 7-9 ms and all rows in about 210 ms,
 * against about 160 ms for a DataOutputStream file and about 2.9 s for parsing the cassini.words.v1 JSON. The file is
 * not much faster to load in full, while the table is written in the same transaction that moves the signature (no
 * window where cache and signature disagree), cascades with its variant, and pages with a LIMIT query, so the Note
 * screen shows its first turns without loading the rest.
 */
@Entity(
    tableName = "words",
    primaryKeys = ["note_id", "variant_id", "idx"],
    foreignKeys = [
        ForeignKey(
            entity = VariantEntity::class,
            parentColumns = ["note_id", "variant_id"],
            childColumns = ["note_id", "variant_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class WordEntity(
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "variant_id") val variantId: String,
    @ColumnInfo(name = "idx") val index: Int,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    val speaker: String,
    val text: String,
)

/**
 * The searchable text of a note: its title and the selected variant's words joined by spaces ("" without a
 * transcript). The INTEGER [rowId] primary key is the FTS docid; AUTOINCREMENT keeps it stable and never reused.
 */
@Entity(
    tableName = "note_text",
    indices = [Index(value = ["note_id"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class NoteTextEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowId: Long = 0,
    @ColumnInfo(name = "note_id") val noteId: String,
    val title: String,
    val body: String,
)

/**
 * The external-content FTS4 index over [NoteTextEntity]. unicode61 with `remove_diacritics=1` makes search case- and
 * diacritic-insensitive ("giovedi" finds "giovedì"). Room writes the triggers that keep it in step with note_text.
 */
@Fts4(
    contentEntity = NoteTextEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=1"],
)
@Entity(tableName = "note_fts")
data class NoteFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val title: String,
    val body: String,
)

/** The transcription job of a note (at most one). The queue is FIFO by [seq] with one RUNNING job. */
@Entity(
    tableName = "jobs",
    indices = [Index("state"), Index("seq")],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class JobEntity(
    @PrimaryKey @ColumnInfo(name = "note_id") val noteId: String,
    /** FIFO order, `max(seq) + 1` at enqueue. */
    val seq: Long,
    val kind: JobKind,
    val state: JobState,
    @ColumnInfo(name = "pause_reason") val pauseReason: PauseReason? = null,
    /** A core `Failure` name, a download error name, or `CHECKPOINT_MISMATCH`. */
    val failure: String? = null,
    @ColumnInfo(name = "done_ms") val doneMs: Long = 0,
    @ColumnInfo(name = "total_ms") val totalMs: Long = 0,
    /** Words in the last checkpoint. */
    val words: Int = 0,
    /** Automatic restarts since the last user action; bumped only by `reconcileOnStart`. */
    val attempts: Int = 0,
    @ColumnInfo(name = "enqueued_at") val enqueuedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * A mirror of one speaker's match in `voices/notes/<noteId>.json` (never an embedding). Rewritten with every write of
 * that file and re-synced from the files at every start.
 */
@Entity(
    tableName = "note_voice_links",
    primaryKeys = ["note_id", "variant_id", "speaker_id"],
    indices = [Index("voice_id")],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class NoteVoiceLinkEntity(
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "variant_id") val variantId: String,
    @ColumnInfo(name = "speaker_id") val speakerId: String,
    /** null = no match. */
    @ColumnInfo(name = "voice_id") val voiceId: String?,
    val state: Match.State,
    val score: Float,
    val seconds: Double,
)
