package org.cassini.data.db

import androidx.room.TypeConverter
import org.cassini.core.format.DocumentState
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.library.Origin
import org.cassini.core.library.PauseReason
import org.cassini.core.library.VariantKind
import org.cassini.core.speakers.Match
import org.json.JSONArray

/**
 * Column conversions. Enums are stored as TEXT by their name, never by ordinal, so reordering a declaration never
 * changes stored data; renaming a constant needs a migration.
 */
@Suppress("TooManyFunctions") // two converters per stored type
class Converters {
    @TypeConverter fun fromOrigin(value: Origin): String = value.name

    @TypeConverter fun toOrigin(value: String): Origin = Origin.valueOf(value)

    @TypeConverter fun fromAudioKind(value: AudioKind): String = value.name

    @TypeConverter fun toAudioKind(value: String): AudioKind = AudioKind.valueOf(value)

    @TypeConverter fun fromJobState(value: JobState): String = value.name

    @TypeConverter fun toJobState(value: String): JobState = JobState.valueOf(value)

    @TypeConverter fun fromJobKind(value: JobKind): String = value.name

    @TypeConverter fun toJobKind(value: String): JobKind = JobKind.valueOf(value)

    @TypeConverter fun fromPauseReason(value: PauseReason): String = value.name

    @TypeConverter fun toPauseReason(value: String): PauseReason = PauseReason.valueOf(value)

    @TypeConverter fun fromVariantKind(value: VariantKind): String = value.name

    @TypeConverter fun toVariantKind(value: String): VariantKind = VariantKind.valueOf(value)

    @TypeConverter fun fromDocumentState(value: DocumentState): String = value.name

    @TypeConverter fun toDocumentState(value: String): DocumentState = DocumentState.valueOf(value)

    @TypeConverter fun fromMatchState(value: Match.State): String = value.name

    @TypeConverter fun toMatchState(value: String): Match.State = Match.State.valueOf(value)

    /** `<STATE>/<KIND>`, for example `QUEUED/TRANSCRIBE`. */
    @TypeConverter fun fromTrashedJob(value: TrashedJob): String = "${value.state.name}/${value.kind.name}"

    @TypeConverter fun toTrashedJob(value: String): TrashedJob {
        val (state, kind) = value.split('/', limit = 2)
        return TrashedJob(JobState.valueOf(state), JobKind.valueOf(kind))
    }

    /** A JSON array of strings. */
    @TypeConverter fun fromStrings(value: List<String>): String = JSONArray(value).toString()

    @TypeConverter fun toStrings(value: String): List<String> {
        val array = JSONArray(value)
        return List(array.length()) { array.getString(it) }
    }
}
