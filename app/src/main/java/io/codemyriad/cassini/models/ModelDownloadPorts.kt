package io.codemyriad.cassini.models

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.library.JobKind
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.ModelPlan
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.data.jobs.EnqueueRefusal
import io.codemyriad.cassini.processing.TranscribeOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * What the Model download ViewModel reads and changes (features.md section 5). [GraphModelDownloadPorts] binds it to
 * [AppGraph]; the ViewModel tests pass a fake.
 */
interface ModelDownloadPorts {
    /** The missing parts with their sizes and the free space the install needs. */
    suspend fun plan(): ModelPlan

    /** Free bytes where the models go. */
    suspend fun usableBytes(): Long

    suspend fun recordConsent(parts: Set<ModelPart>)

    suspend fun recordDeclined()

    /** Puts the note in the queue again with its job's kind (a NEEDS_CONSENT job keeps the kind it was asked with). */
    suspend fun transcribe(noteId: String): TranscribeOutcome

    /** The standalone download through `ModelInstallOwner`, in the foreground service. */
    fun downloadModels(parts: Set<ModelPart>)

    /** POST_NOTIFICATIONS should be asked now: API 33+, not granted, and never asked before. */
    suspend fun shouldAskNotifications(): Boolean

    suspend fun markNotificationsAsked()
}

/** [ModelDownloadPorts] over the app's graph. */
class GraphModelDownloadPorts(
    private val graph: AppGraph,
    context: Context,
    private val noteId: String? = null,
    private val parakeetOnly: Boolean = false,
    private val part: ModelPart? = null,
) : ModelDownloadPorts {
    private val app = context.applicationContext
    private val speakerRemediation: Boolean get() = noteId != null && part == ModelPart.SPEAKERS

    override suspend fun plan(): ModelPlan = when {
        speakerRemediation -> {
            val model = targetModel()
            ModelPlan(graph.models.plan().missing.filter {
                it.part == ModelPart.SPEAKERS || (it.part == ModelPart.SPEECH && model == SpeechModel.PARAKEET)
            })
        }
        part != null || parakeetOnly -> {
            val requested = part ?: ModelPart.SPEECH
            ModelPlan(graph.models.plan().missing.filter { it.part == requested })
        }
        else -> graph.downloadPlan(targetModel())
    }

    private suspend fun targetModel(): SpeechModel {
        val identity = noteId?.let { graph.awaitCatalogue().jobs.job(it)?.speechIdentity }
        return identity?.let(SpeechModel::identity) ?: graph.speech.selected()
    }

    /** The model root's file system; a root not created yet counts as its nearest existing parent. */
    override suspend fun usableBytes(): Long = withContext(graph.dispatchers.io) {
        generateSequence(graph.paths.modelRoot.absoluteFile) { it.parentFile }
            .firstOrNull { it.exists() }
            ?.usableSpace ?: 0L
    }

    override suspend fun recordConsent(parts: Set<ModelPart>) = graph.models.recordConsent(parts)

    override suspend fun recordDeclined() = graph.models.recordDeclined()

    override suspend fun transcribe(noteId: String): TranscribeOutcome {
        if (speakerRemediation && graph.awaitCatalogue().database.notes().note(noteId)?.documentState !=
            DocumentState.OK
        ) {
            return TranscribeOutcome.Refused(EnqueueRefusal.DOCUMENT_NOT_VERIFIED)
        }
        val kind = if (speakerRemediation) {
            JobKind.TRANSCRIBE_AGAIN
        } else {
            graph.awaitCatalogue().jobs.job(noteId)?.kind ?: JobKind.TRANSCRIBE
        }
        return graph.processing.transcribe(noteId, kind)
    }

    override fun downloadModels(parts: Set<ModelPart>) {
        graph.processing.downloadModels(parts)
    }

    override suspend fun shouldAskNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val granted = ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return !granted && !graph.settings.notificationsAsked.first()
    }

    override suspend fun markNotificationsAsked() = graph.settings.markNotificationsAsked()
}
