package io.codemyriad.cassini.recording

import android.util.Log
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.engine.asr.LiveMicrophoneRecognition
import io.codemyriad.cassini.engine.capture.LiveMicrophonePcm
import io.codemyriad.cassini.engine.job.RecognizerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** Optional app-owned preview. Capture and final publication remain independent of this state. */
class RecordingPreview(
    private val scope: CoroutineScope,
    private val visible: StateFlow<Boolean>,
    private val model: suspend () -> SpeechModel,
    private val recognizer: suspend (SpeechModel) -> RecognizerFactory,
) {
    data class Snapshot(
        val id: UUID? = null,
        val spans: List<LiveMicrophoneRecognition.Update> = emptyList(),
        val needsSavedAudio: Boolean = false,
    )

    private val draft = MutableStateFlow(Snapshot())
    val snapshot = draft.asStateFlow()
    private var active: Job? = null

    fun attach(input: LiveMicrophonePcm) {
        active?.cancel()
        val id = UUID.randomUUID()
        draft.value = Snapshot(id = id)
        active = scope.launch {
            try {
                if (!visible.value) {
                    incomplete(id)
                    return@launch
                }
                coroutineScope {
                    val preview = launch {
                        val selected = model()
                        val events = LiveMicrophoneRecognition.Events(
                            updated = { revision ->
                                draft.update { snapshot ->
                                    if (snapshot.id != id) {
                                        snapshot
                                    } else {
                                        snapshot.copy(spans = snapshot.spans.filterNot { it.fromMs == revision.fromMs } + revision)
                                    }
                                }
                            },
                            missing = { incomplete(id) },
                        )
                        LiveMicrophoneRecognition.follow(input, selected, recognizer(selected), events, settleOnStop = false)
                    }
                    val visibility = launch {
                        visible.first { !it }
                        incomplete(id)
                        preview.cancel()
                    }
                    val stop = launch {
                        input.awaitStop()
                        preview.cancel()
                    }
                    preview.join()
                    if (preview.isCancelled) incomplete(id)
                    stop.cancel()
                    visibility.cancel()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
                // Avoid exception text from native speech engines in app logs.
                Log.w("CassiniLivePreview", "Preview stopped: ${failure.javaClass.simpleName}")
                incomplete(id)
            } finally {
                input.cancel()
            }
        }
    }
    private fun incomplete(id: UUID) {
        draft.update { if (it.id == id) it.copy(needsSavedAudio = true) else it }
    }
}
