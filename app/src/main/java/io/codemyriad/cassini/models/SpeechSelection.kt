package io.codemyriad.cassini.models

import android.content.Context
import android.util.Log
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.core.models.WhistleCapability
import io.codemyriad.cassini.data.models.ModelRepository
import io.codemyriad.cassini.data.settings.SettingsRepository
import io.codemyriad.cassini.engine.asr.WhistleDeviceCapability
import io.codemyriad.cassini.engine.whistle.WhistleAssets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

data class SpeechChoice(val selected: SpeechModel, val includedAvailable: Boolean)

/** Capability and read-only APK integrity are checked without loading either neural runtime in the app process. */
class SpeechSelection(
    context: Context,
    private val settings: SettingsRepository,
    scope: CoroutineScope,
    io: CoroutineDispatcher,
) {
    val included = scope.async(io) {
        if (WhistleDeviceCapability.current() != WhistleCapability.ELIGIBLE) {
            false
        } else {
            try {
                WhistleAssets.verified(context)
                true
            } catch (error: CancellationException) {
                throw error
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Log.w("WhistleAssets", "Included assets unavailable", error)
                false
            }
        }
    }

    val state: Flow<SpeechChoice> = flow {
        val available = included.await()
        emitAll(settings.speechModel.map { SpeechChoice(SpeechModel.chosen(it, available), available) })
    }

    suspend fun selected(): SpeechModel = state.first().selected

    suspend fun choose(model: SpeechModel) {
        require(model != SpeechModel.INCLUDED || included.await()) { "Included unavailable on this device" }
        settings.setSpeechModel(model)
    }

    suspend fun consented(models: ModelRepository, identity: String? = null): Boolean {
        // Unknown persisted pins may enter validation, but the factory refuses them before any engine/download.
        val model = if (identity == null) {
            selected()
        } else {
            SpeechModel.entries.firstOrNull { it.identity == identity } ?: return true
        }
        return model == SpeechModel.INCLUDED || models.speechConsented()
    }
}
