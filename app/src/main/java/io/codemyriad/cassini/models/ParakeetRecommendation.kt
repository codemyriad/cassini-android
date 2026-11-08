package io.codemyriad.cassini.models

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.ModelPlan
import io.codemyriad.cassini.core.models.ParakeetSuggestion
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.models.ModelRepository
import io.codemyriad.cassini.data.models.ModelStatus
import io.codemyriad.cassini.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class ParakeetRecommendationState(
    val visible: Boolean = false,
    val downloadBytes: Long = 0,
    val firstWhistleNote: String? = null,
)

/** No network, model load or unsolicited install. Recheck disk/memory on model changes and screen resume. */
class ParakeetRecommendation(
    private val context: Context,
    private val paths: AppPaths,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val included: Deferred<Boolean>,
    private val io: CoroutineDispatcher,
) {
    private val refreshes = MutableStateFlow(0L)

    val state = combine(
        models.status,
        settings.parakeetSuggestionDismissed,
        settings.firstWhistleNote,
        refreshes,
    ) { status, dismissed, first, _ ->
        val speech = status[ModelPart.SPEECH]
        if (dismissed || speech !is ModelStatus.Missing && speech !is ModelStatus.Failed) {
            ParakeetRecommendationState()
        } else {
            withContext(io) {
                if (included.await()) inspect(first) else ParakeetRecommendationState()
            }
        }
    }

    private suspend fun inspect(first: String?): ParakeetRecommendationState = try {
        val plan = ModelPlan(models.plan().missing.filter { it.part == ModelPart.SPEECH })
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = manager?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo) }
        val free = generateSequence(paths.modelRoot.absoluteFile) { it.parentFile }
            .firstOrNull { it.exists() }?.usableSpace ?: 0L
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Process.is64Bit() &&
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
        val eligible = ParakeetSuggestion.eligible(
            supported && memory != null,
            manager?.isLowRamDevice != false,
            memory?.lowMemory != false,
            memory?.totalMem ?: 0L,
            free,
            plan.requiredFreeBytes,
        )
        ParakeetRecommendationState(eligible, plan.totalBytes, first)
    } catch (error: CancellationException) {
        throw error
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        // Unknown capability or filesystem readiness must never become a recommendation or break other notices.
        Log.w("ParakeetSuggestion", "Cannot check Parakeet capability and installation space", error)
        ParakeetRecommendationState()
    }

    fun refresh() = refreshes.update { it + 1 }
}
