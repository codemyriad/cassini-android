package org.cassini.android.models

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.cassini.android.AppGraph
import org.cassini.android.processing.TranscribeOutcome
import org.cassini.core.library.JobKind
import org.cassini.core.models.ModelPart
import org.cassini.core.models.ModelPlan

/**
 * What the Model download ViewModel reads and changes (features.md section 5). [GraphModelDownloadPorts] binds it to
 * [AppGraph]; the ViewModel tests pass a fake.
 */
interface ModelDownloadPorts {
    /** The missing parts with their sizes and the free space the install needs. */
    suspend fun plan(): ModelPlan

    /** Free bytes where the models go. */
    suspend fun usableBytes(): Long

    suspend fun recordConsent()

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
class GraphModelDownloadPorts(private val graph: AppGraph, context: Context) : ModelDownloadPorts {
    private val app = context.applicationContext

    override suspend fun plan(): ModelPlan = graph.models.plan()

    /** The model root's file system; a root not created yet counts as its nearest existing parent. */
    override suspend fun usableBytes(): Long = withContext(graph.dispatchers.io) {
        generateSequence(graph.paths.modelRoot.absoluteFile) { it.parentFile }
            .firstOrNull { it.exists() }
            ?.usableSpace ?: 0L
    }

    override suspend fun recordConsent() = graph.models.recordConsent()

    override suspend fun recordDeclined() = graph.models.recordDeclined()

    override suspend fun transcribe(noteId: String): TranscribeOutcome {
        val kind = graph.awaitCatalogue().jobs.job(noteId)?.kind ?: JobKind.TRANSCRIBE
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
