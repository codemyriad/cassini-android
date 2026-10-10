package org.cassini.android.voices

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.cassini.android.AppGraph
import org.cassini.data.voices.SavedVoices
import org.cassini.data.voices.VoiceEdit

/**
 * What the Saved voices ViewModel reads and changes (features.md section 10). [GraphSavedVoicesPorts] binds it to
 * `VoiceRepository`; the ViewModel tests pass a fake.
 */
interface SavedVoicesPorts {
    /** The saved people, sorted, with note counts, the stale flag and the read-only problem. */
    fun people(): Flow<SavedVoices>

    suspend fun rename(id: String, name: String): VoiceEdit

    suspend fun forget(id: String): VoiceEdit

    suspend fun forgetEveryone(): VoiceEdit
}

/** [SavedVoicesPorts] over the app's graph. */
class GraphSavedVoicesPorts(private val graph: AppGraph) : SavedVoicesPorts {
    override fun people(): Flow<SavedVoices> = flow { emitAll(graph.awaitCatalogue().voices.people()) }

    override suspend fun rename(id: String, name: String): VoiceEdit = graph.awaitCatalogue().voices.rename(id, name)

    override suspend fun forget(id: String): VoiceEdit = graph.awaitCatalogue().voices.forget(id)

    override suspend fun forgetEveryone(): VoiceEdit = graph.awaitCatalogue().voices.forgetEveryone()
}
