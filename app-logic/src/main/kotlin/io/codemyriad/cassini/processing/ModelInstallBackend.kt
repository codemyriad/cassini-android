package io.codemyriad.cassini.processing

import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.data.models.ModelStatus
import io.codemyriad.cassini.engine.job.DownloadProgress
import io.codemyriad.cassini.engine.job.InstallOutcome
import kotlinx.coroutines.flow.StateFlow

/** Repository and platform effects used by the shared install owner. File work runs on the owner's IO dispatcher. */
interface ModelInstallBackend {
    val status: StateFlow<Map<ModelPart, ModelStatus>>
    suspend fun consented(part: ModelPart): Boolean
    fun hasValidatedNetwork(): Boolean
    fun publishProgress(part: ModelPart, progress: ModelStatus)
    fun clearProgress(part: ModelPart)
    suspend fun refresh()
    suspend fun install(parts: Set<ModelPart>, onProgress: (DownloadProgress) -> Unit): InstallOutcome
}
