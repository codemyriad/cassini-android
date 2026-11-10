package io.codemyriad.cassini.models

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.ModelPlan
import io.codemyriad.cassini.engine.job.DownloadError
import io.codemyriad.cassini.notices.Notices
import io.codemyriad.cassini.processing.TranscribeOutcome
import io.codemyriad.cassini.ui.banner.BannerAction
import io.codemyriad.cassini.ui.banner.BannerKind
import io.codemyriad.cassini.ui.banner.BannerSpec
import io.codemyriad.cassini.ui.models.ModelDownloadEvent
import io.codemyriad.cassini.ui.models.ModelDownloadUiState
import io.codemyriad.cassini.ui.models.ModelPartSizeUi
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
 * - The plan is read on entry. A stale entry queues its note before closing. Failed enqueue outcomes stay visible.
 * - Download checks the free space first; when it is short the screen shows "Not enough storage" with Retry and
 *   records no consent. Then POST_NOTIFICATIONS is asked once (API 33+), then the consent is recorded and the note is
 *   queued (or the standalone download starts), and the screen pops.
 * - "Not now — keep the audio" records the refusal and pops. Close only pops.
 */
class ModelDownloadViewModel(
    private val noteId: String?,
    private val ports: ModelDownloadPorts,
    /** Holds the open POST_NOTIFICATIONS request, so that a ViewModel after process death still starts the download. */
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val effectChannel = Channel<ModelDownloadEffect>(Channel.BUFFERED)

    /** One-shot navigation and permission requests; the route collects them. */
    val effects: Flow<ModelDownloadEffect> = effectChannel.receiveAsFlow()

    private data class Local(
        val plan: ModelPlan? = null,
        val busy: Boolean = false,
        val banner: BannerSpec? = null,
    )

    private val local = MutableStateFlow(Local(busy = saved.get<Boolean>(KEY_AWAITING) == true))
    private var closing = false

    /** Null while loading or closing a stale entry. An enqueue failure can show a banner with an empty plan. */
    val state: StateFlow<ModelDownloadUiState?> = local.map(::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        viewModelScope.launch {
            val plan = guarded("read the model plan") { ports.plan() } ?: return@launch
            local.update { it.copy(plan = plan) }
            if (plan.isEmpty && !local.value.busy && local.value.banner == null) queueNote(noteId)
        }
    }

    fun onEvent(event: ModelDownloadEvent) {
        when (event) {
            ModelDownloadEvent.Close -> close()
            ModelDownloadEvent.Download -> download()
            ModelDownloadEvent.NotNow -> notNow()
            ModelDownloadEvent.OpenSources -> effectChannel.trySend(ModelDownloadEffect.OpenLicenses)
            is ModelDownloadEvent.BannerAction ->
                if (event.action == BannerAction.RETRY || event.action == BannerAction.TRY_AGAIN) download()
        }
    }

    /** The POST_NOTIFICATIONS dialog closed, granted or not: the download goes on. A denial blocks nothing. */
    fun onNotificationsAnswered() {
        if (saved.get<Boolean>(KEY_AWAITING) != true) return
        saved[KEY_AWAITING] = false
        viewModelScope.launch { start() }
    }

    private fun build(local: Local): ModelDownloadUiState? {
        val plan = local.plan ?: return null
        if (plan.isEmpty && local.banner == null) return null
        return ModelDownloadUiState(
            parts = plan.missing.map { ModelPartSizeUi(Notices.partUi(it.part), it.downloadBytes) }.toImmutableList(),
            requiredFreeBytes = plan.requiredFreeBytes,
            withNote = noteId != null,
            busy = local.busy,
            banner = local.banner,
        )
    }

    private fun download() {
        if (local.value.busy || closing) return
        val shownParts = local.value.plan?.parts?.toSet() ?: return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val available = guarded("read the model plan") { ports.plan() }
            if (available == null) {
                local.update { it.copy(busy = false) }
                return@launch
            }
            if (hasUnshownSpeech(available, shownParts)) {
                local.value = Local(plan = available)
                return@launch
            }
            val plan = ModelPlan(available.missing.filter { it.part in shownParts })
            when {
                plan.isEmpty -> {
                    local.update { it.copy(plan = plan) }
                    queueNote(noteId)
                }
                (guarded("read the free space") { ports.usableBytes() } ?: 0L) < plan.requiredFreeBytes -> {
                    val banner = Notices.download(DownloadError.NOT_ENOUGH_STORAGE, plan.requiredFreeBytes, announce = true)
                    local.value = Local(plan = plan, banner = banner)
                }
                else -> {
                    local.update { it.copy(plan = plan, banner = null) }
                    saved[KEY_PARTS] = ArrayList(plan.parts.map { it.name })
                    askNotificationsOrStart()
                }
            }
        }
    }

    private fun hasUnshownSpeech(plan: ModelPlan, approved: Set<ModelPart>): Boolean =
        noteId != null && ModelPart.SPEECH in plan.parts && ModelPart.SPEECH !in approved

    private suspend fun askNotificationsOrStart() {
        if (guarded("read the notification permission") { ports.shouldAskNotifications() } == true) {
            guarded("record the notification request") { ports.markNotificationsAsked() }
            saved[KEY_AWAITING] = true
            effectChannel.trySend(ModelDownloadEffect.RequestNotifications)
        } else {
            start()
        }
    }

    /** Recheck prerequisites after the permission dialog, then record the parts the user approved. */
    private suspend fun start() {
        val parts = guarded("read the shown parts") {
            saved.get<ArrayList<String>>(KEY_PARTS)?.map(ModelPart::valueOf)?.toSet()
        }
        if (parts == null) {
            local.update { it.copy(busy = false) }
            return
        }
        val current = noteId?.let { guarded("read the model plan") { ports.plan() } }
        when {
            noteId != null && current == null -> local.update { it.copy(busy = false) }
            current != null && hasUnshownSpeech(current, parts) -> {
                saved.remove<ArrayList<String>>(KEY_PARTS)
                local.value = Local(plan = current)
            }
            guarded("record the consent") { ports.recordConsent(parts) } != null -> {
                saved.remove<ArrayList<String>>(KEY_PARTS)
                if (noteId == null) ports.downloadModels(parts)
                queueNote(noteId)
            }
            else -> local.update { it.copy(busy = false) }
        }
    }

    /** Close a completed standalone request, or close only after the note is queued. */
    private suspend fun queueNote(note: String?) {
        if (note == null) {
            close()
            return
        }
        when (guarded("queue the note") { ports.transcribe(note) }) {
            is TranscribeOutcome.Queued -> close()
            TranscribeOutcome.NeedsConsent -> {
                val plan = guarded("read the required models") { ports.plan() }
                local.update {
                    it.copy(
                        plan = plan ?: it.plan,
                        busy = false,
                        banner = if (plan != null && hasUnshownSpeech(plan, it.plan?.parts?.toSet().orEmpty())) {
                            null
                        } else {
                            BannerSpec(BannerKind.TX_FAILED)
                        },
                    )
                }
            }
            is TranscribeOutcome.Refused, null ->
                local.update { it.copy(busy = false, banner = BannerSpec(BannerKind.TX_FAILED)) }
        }
    }

    private fun notNow() {
        if (closing) return
        viewModelScope.launch {
            guarded("record the refusal") { ports.recordDeclined() }
            close()
        }
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
        const val KEY_AWAITING = "awaitingNotifications"
        const val KEY_PARTS = "shownModelParts"
    }
}
