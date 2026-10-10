package org.cassini.android.adapters

import org.cassini.android.processing.ModelInstallOwner
import org.cassini.core.models.ModelCatalog
import org.cassini.core.models.ModelDescriptor
import org.cassini.core.models.ModelPart
import org.cassini.core.models.ModelPlan
import org.cassini.data.models.ModelRepository
import org.cassini.engine.job.DownloadProgress
import org.cassini.engine.job.InstallOutcome
import org.cassini.engine.job.ModelAccess
import org.cassini.engine.job.ModelStore
import java.io.File

/**
 * [ModelAccess] for the job: `ModelRepository` for status and consent, [owner] for every install (architecture
 * 5.6).
 */
class RepositoryModelAccess(
    private val models: ModelRepository,
    private val modelRoot: File,
    private val owner: ModelInstallOwner,
    private val network: NetworkCheck,
) : ModelAccess {
    override suspend fun consented(): Boolean = models.consented()

    override fun ready(part: ModelPart): Boolean = models.readyBlocking(part)

    override fun plan(): ModelPlan = ModelPlan.of(modelRoot)

    override fun detectorReady(): Boolean = ModelCatalog.VAD.ready(modelRoot)

    override fun hasValidatedNetwork(): Boolean = network.validated()

    override suspend fun install(parts: Set<ModelPart>, onProgress: (DownloadProgress) -> Unit): InstallOutcome =
        owner.install(parts, onProgress)
}

/**
 * [ModelStore] for `ModelInstaller` over `ModelRepository` (architecture 4.6). [consented] is read by the install
 * owner before the install starts, because DataStore is read with a suspend call and the store's functions block.
 */
class RepositoryModelStore(
    private val models: ModelRepository,
    private val modelRoot: File,
    private val network: NetworkCheck,
    private val consented: Boolean,
) : ModelStore {
    override fun consented(): Boolean = consented

    override fun hasValidatedNetwork(): Boolean = network.validated()

    override fun ready(model: ModelDescriptor): Boolean = model.ready(modelRoot)

    /** Free bytes on the model root's file system; a root not created yet counts as its nearest existing parent. */
    override fun usableBytes(): Long =
        generateSequence(modelRoot.absoluteFile) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0L

    override fun installBlocking(model: ModelDescriptor, progress: (Long, Boolean) -> Unit) =
        models.installModelBlocking(model, progress)
}
