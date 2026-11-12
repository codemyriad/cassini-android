package io.codemyriad.cassini.adapters

import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.data.models.ModelRepository
import io.codemyriad.cassini.data.models.ModelStatus
import io.codemyriad.cassini.engine.job.DownloadProgress
import io.codemyriad.cassini.engine.job.InstallOutcome
import io.codemyriad.cassini.engine.job.ModelInstaller
import io.codemyriad.cassini.processing.ModelInstallBackend
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File

/** Connects the JVM install owner to persisted consent, verified files and Android connectivity. */
class RepositoryModelInstallBackend(
    private val models: ModelRepository,
    private val modelRoot: File,
    private val network: NetworkCheck,
    private val io: CoroutineDispatcher,
) : ModelInstallBackend {
    override val status = models.status
    override suspend fun consented(part: ModelPart): Boolean = models.consented(part)
    override fun hasValidatedNetwork(): Boolean = network.validated()
    override fun publishProgress(part: ModelPart, progress: ModelStatus) = models.publishProgress(part, progress)
    override fun clearProgress(part: ModelPart) = models.clearProgress(part)
    override suspend fun refresh() { models.refresh() }

    override suspend fun install(parts: Set<ModelPart>, onProgress: (DownloadProgress) -> Unit): InstallOutcome {
        val consented = parts.all { models.consented(it) }
        val store = RepositoryModelStore(models, modelRoot, network, consented)
        return ModelInstaller(store, io).install(parts, onProgress)
    }
}
