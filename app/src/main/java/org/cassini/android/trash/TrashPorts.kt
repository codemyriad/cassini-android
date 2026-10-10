package org.cassini.android.trash

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.cassini.android.AppGraph
import org.cassini.data.db.TrashRow
import java.time.Instant
import java.time.ZoneId

/**
 * What the Trash ViewModel reads and changes (features.md section 8, architecture 4.10). [GraphTrashPorts] binds it
 * to `NoteRepository`; the ViewModel tests pass a fake.
 */
interface TrashPorts {
    /** The trashed notes, oldest deletion first. */
    fun trash(): Flow<List<TrashRow>>

    /** Deletes the notes whose 30 days are over at [now] in [zone]; returns how many went. */
    suspend fun purgeExpired(now: Instant, zone: ZoneId): Int

    suspend fun restore(id: String): Boolean

    suspend fun deleteNow(id: String): Boolean

    suspend fun emptyTrash(): Int
}

/** [TrashPorts] over the app's graph. */
class GraphTrashPorts(private val graph: AppGraph) : TrashPorts {
    override fun trash(): Flow<List<TrashRow>> = flow { emitAll(graph.awaitCatalogue().notes.trash()) }

    override suspend fun purgeExpired(now: Instant, zone: ZoneId): Int =
        graph.awaitCatalogue().notes.purgeExpired(now, zone).also { graph.settings.recordPurge(now.toEpochMilli()) }

    override suspend fun restore(id: String): Boolean = graph.awaitCatalogue().notes.restore(id)

    override suspend fun deleteNow(id: String): Boolean = graph.awaitCatalogue().notes.deleteNow(id)

    override suspend fun emptyTrash(): Int = graph.awaitCatalogue().notes.emptyTrash()
}
