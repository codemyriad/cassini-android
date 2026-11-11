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
    /** The selected variant of note `n`: `selected_variant`, else the default variant, else the first one. */
    const val SELECTED_VARIANT =
        "COALESCE(n.selected_variant, (SELECT d.variant_id FROM variants d WHERE d.note_id = n.id " +
            "ORDER BY d.is_default DESC, d.ordinal LIMIT 1))"

    /** The [LibraryRow] columns. */
    const val LIBRARY_COLUMNS =
        "n.id, n.title, n.created_at, n.duration_ms, n.audio_kind, n.document_state, " +
            "n.origin, n.recorded_at, n.capture_started_at, " +
