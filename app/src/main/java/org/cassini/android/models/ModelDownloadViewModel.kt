package org.cassini.android.models

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.cassini.android.notices.Notices
import org.cassini.core.models.ModelPlan
import org.cassini.engine.job.DownloadError
import org.cassini.ui.banner.BannerAction
import org.cassini.ui.models.ModelDownloadEvent
import org.cassini.ui.models.ModelDownloadUiState
import org.cassini.ui.models.ModelPartSizeUi

/** What the Model download route does for the ViewModel. */
sealed interface ModelDownloadEffect {
    /** Back: the screen pops. */
    data object Close : ModelDownloadEffect

    /** "Where the models come from · Privacy". */
    data object OpenLicenses : ModelDownloadEffect

    /** POST_NOTIFICATIONS, once before the first download; the route answers with `onNotificationsAnswered`. */
    data object RequestNotifications : ModelDownloadEffect
}

/**
 * Model download, "Transcribe on this phone" (board 05, features.md section 5; ui.md section 7). [noteId] is the note
 * that waits for the models ("Download and transcribe"); without one the screen offers a standalone "Download".
 *
 * - The plan is read once on entry. When nothing is missing (a stale entry) the screen pops itself, putting its note
 *   in the queue first.
 * - Download checks the free space first; when it is short the screen shows "Not enough storage" with Retry and
 *   records no consent. Then POST_NOTIFICATIONS is asked once (API 33+), then the consent is recorded and the note is
 *   queued (or the standalone download starts), and the screen pops.
 * - "Not now — keep the audio" records the refusal and pops. Close only pops.
 */
class ModelDownloadViewModel(
    private val noteId: String?,
    private val ports: ModelDownloadPorts,
) : ViewModel() {
    private val effectChannel = Channel<ModelDownloadEffect>(Channel.BUFFERED)

    /** One-shot navigation and permission requests; the route collects them. */
    val effects: Flow<ModelDownloadEffect> = effectChannel.receiveAsFlow()

    private data class Local(
        val plan: ModelPlan? = null,
        val busy: Boolean = false,
        /** The bytes the no-space banner asks for; null when there is no such banner. */
        val noSpace: Long? = null,
        val awaitingNotifications: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private var closing = false

    /** Null until the plan is read, and when nothing is missing (the screen is never shown with no parts). */
    val state: StateFlow<ModelDownloadUiState?> = local.map(::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        viewModelScope.launch {
            val plan = guarded("read the model plan") { ports.plan() } ?: return@launch
            if (plan.isEmpty) closeStale() else local.update { it.copy(plan = plan) }
        }
    }

    fun onEvent(event: ModelDownloadEvent) {
        when (event) {
            ModelDownloadEvent.Close -> close()
            ModelDownloadEvent.Download -> download()
            ModelDownloadEvent.NotNow -> notNow()
            ModelDownloadEvent.OpenSources -> effectChannel.trySend(ModelDownloadEffect.OpenLicenses)
            is ModelDownloadEvent.BannerAction -> if (event.action == BannerAction.RETRY) download()
        }
    }

    /** The POST_NOTIFICATIONS dialog closed, granted or not: the download goes on. A denial blocks nothing. */
    fun onNotificationsAnswered() {
        if (!local.value.awaitingNotifications) return
        local.update { it.copy(awaitingNotifications = false) }
        viewModelScope.launch { start() }
    }

    private fun build(local: Local): ModelDownloadUiState? {
        val plan = local.plan?.takeUnless { it.isEmpty } ?: return null
        return ModelDownloadUiState(
            parts = plan.missing.map { ModelPartSizeUi(Notices.partUi(it.part), it.downloadBytes) }.toImmutableList(),
            requiredFreeBytes = plan.requiredFreeBytes,
            withNote = noteId != null,
            busy = local.busy,
            banner = local.noSpace?.let { Notices.download(DownloadError.NOT_ENOUGH_STORAGE, it, announce = true) },
        )
    }

    private fun download() {
        if (local.value.busy || closing) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val plan = guarded("read the model plan") { ports.plan() }
            when {
                plan == null -> local.update { it.copy(busy = false) }
                plan.isEmpty -> closeStale()
                (guarded("read the free space") { ports.usableBytes() } ?: 0L) < plan.requiredFreeBytes ->
                    local.update { it.copy(plan = plan, busy = false, noSpace = plan.requiredFreeBytes) }
                else -> {
                    local.update { it.copy(plan = plan, noSpace = null) }
                    askNotificationsOrStart()
                }
            }
        }
    }

    private suspend fun askNotificationsOrStart() {
        if (guarded("read the notification permission") { ports.shouldAskNotifications() } == true) {
            guarded("record the notification request") { ports.markNotificationsAsked() }
            local.update { it.copy(awaitingNotifications = true) }
            effectChannel.trySend(ModelDownloadEffect.RequestNotifications)
        } else {
            start()
        }
    }

    /** Consent, then the note's job or the standalone download, then back. */
    private suspend fun start() {
        val plan = local.value.plan ?: return
        if (guarded("record the consent") { ports.recordConsent() } == null) {
            local.update { it.copy(busy = false) }
            return
        }
        val note = noteId
        if (note != null) {
            guarded("queue the note") { ports.transcribe(note) }
        } else {
            ports.downloadModels(plan.parts.toSet())
        }
        close()
    }

    private fun notNow() {
        if (closing) return
        viewModelScope.launch {
            guarded("record the refusal") { ports.recordDeclined() }
            close()
        }
    }

    /** Nothing is missing: the note (when there is one) goes in the queue, and the screen goes. */
    private suspend fun closeStale() {
        noteId?.let { note -> guarded("queue the note") { ports.transcribe(note) } }
        close()
    }

    private fun close() {
        if (closing) return
        closing = true
        effectChannel.trySend(ModelDownloadEffect.Close)
    }

    @Suppress("TooGenericExceptionCaught") // a failed read or write is logged; the screen stays usable
    private suspend fun <T> guarded(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.e(TAG, "Cannot $what", error)
        null
    }

    private companion object {
        const val TAG = "CassiniModels"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
