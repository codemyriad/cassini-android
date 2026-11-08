package io.codemyriad.cassini.models

import android.content.Context
import android.util.Log
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.core.models.WhistleCapability
import io.codemyriad.cassini.data.models.ModelRepository
import io.codemyriad.cassini.data.models.ModelStatus
import io.codemyriad.cassini.data.settings.SettingsRepository
import io.codemyriad.cassini.engine.asr.WhistleDeviceCapability
import io.codemyriad.cassini.engine.whistle.WhistleAssets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SpeechChoice(val selected: SpeechModel, val includedAvailable: Boolean)

/** Capability and read-only APK integrity are checked without loading either neural runtime in the app process. */
class SpeechSelection(
    context: Context,
    private val settings: SettingsRepository,
    private val models: ModelRepository,
    scope: CoroutineScope,
    io: CoroutineDispatcher,
) {
    private val selectionLock = Mutex()
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
        emitAll(combine(settings.speechModel, models.status) { _, _ -> resolveChoice() })
    }.distinctUntilChanged().shareIn(scope, SharingStarted.Eagerly, replay = 1)

    private suspend fun resolveChoice(): SpeechChoice = selectionLock.withLock {
        val available = included.await()
        val status = models.status.value
        val saved = if (ModelPart.SPEECH in status) {
            settings.resolveSpeechModel(status[ModelPart.SPEECH] == ModelStatus.Ready)
        } else {
            settings.speechModel.first()
        }
        SpeechChoice(SpeechModel.chosen(saved, available), available)
    }

    suspend fun selected(): SpeechModel {
        // A newly queued job must see an already installed model even before a Settings collector exists.
        models.refresh()
        return resolveChoice().selected
    }

    suspend fun choose(model: SpeechModel) {
        require(model != SpeechModel.INCLUDED || included.await()) { "Included unavailable on this device" }
        models.refresh()
        resolveChoice()
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
