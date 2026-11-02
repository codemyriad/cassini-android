package io.codemyriad.cassini.adapters

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.UserFacingException
import io.codemyriad.cassini.core.audio.Limits
import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.core.models.ModelDescriptor
import io.codemyriad.cassini.engine.asr.ParakeetFiles
import io.codemyriad.cassini.engine.decode.AudioDecoder
import io.codemyriad.cassini.engine.job.DecoderAudioSource
import io.codemyriad.cassini.engine.job.JobEngines
import io.codemyriad.cassini.engine.job.JobEnvironment
import io.codemyriad.cassini.engine.job.OpusEncoders
import io.codemyriad.cassini.engine.job.ParakeetRecognizers
import io.codemyriad.cassini.engine.job.SortformerDiarizers
import io.codemyriad.cassini.engine.job.VoiceprintPrints
import io.codemyriad.cassini.inAppLanguage
import java.io.File
import io.codemyriad.cassini.ui.R as UiR

/**
 * The native seams of a job over the models installed in [modelRoot] (architecture 2.2 and 5.4). The paths are read
 * when a job opens each seam, so a model installed by the job's own download is used. [check] runs before each seam
 * hands a model to the native runtime: the app's `ModelCheck`, which hashes each model once per process and fails
 * with VERIFY for a damaged one (D16 section 4).
 */
object InstalledEngines {
    fun of(context: Context, modelRoot: File, cache: File, check: (ModelDescriptor) -> Unit): JobEngines = JobEngines(
        recognizers = ParakeetRecognizers(
            parakeet = {
                check(ModelCatalog.PARAKEET)
                parakeet(modelRoot)
            },
            detector = { detector(modelRoot, check) },
        ),
        diarizers = SortformerDiarizers {
            check(ModelCatalog.DIARIZATION)
            only(ModelCatalog.DIARIZATION, modelRoot)
        },
        prints = VoiceprintPrints({
            check(ModelCatalog.VOICEPRINT)
            File(only(ModelCatalog.VOICEPRINT, modelRoot))
        }),
        audio = DecoderAudioSource(AudioDecoder(context.contentResolver, cache)),
        encoders = OpusEncoders(),
    )

    /** Parakeet's four files, as 0.1.0 `InstalledModels.parakeet` named them. */
    fun parakeet(modelRoot: File): ParakeetFiles = ModelCatalog.PARAKEET.let {
        ParakeetFiles(
            encoder = it.path(modelRoot, "encoder.int8.onnx"),
            decoder = it.path(modelRoot, "decoder.int8.onnx"),
            joiner = it.path(modelRoot, "joiner.int8.onnx"),
            tokens = it.path(modelRoot, "tokens.txt"),
        )
    }

    /**
     * The speech detector when it is installed and intact; without it recordings are cut at quiet points. It is
     * optional, so a damaged copy is deleted by [check] and the job goes on without it, as when it is missing.
     */
    fun detector(modelRoot: File, check: (ModelDescriptor) -> Unit = {}): String? {
        val vad = ModelCatalog.VAD.takeIf { it.ready(modelRoot) } ?: return null
        val intact = try {
            check(vad)
            true
        } catch (damaged: UserFacingException) {
            if (damaged.failure == Failure.CANCELLED) throw damaged
            Log.w(TAG, "The speech detector is damaged and was removed; going on without it", damaged)
            false
        }
        return if (intact) only(vad, modelRoot) else null
    }

    private fun only(model: ModelDescriptor, modelRoot: File): String =
        model.path(modelRoot, model.artifacts.single().name)

    /**
     * The app's part of a job: the localized placeholder label ("Speaker 2"), the memory preflight hint
     * (architecture 5.7) and the log.
     */
    fun environment(context: Context): JobEnvironment {
        val app = context.applicationContext
        return JobEnvironment(
            placeholderLabel = { number -> speakerLabel(app, number) },
            lowMemory = { durationMs -> lowMemory(app, durationMs) },
            warn = { message, error -> Log.w(TAG, message, error) },
        )
    }

    /**
     * "Speaker 2" in the app language: `ui_speaker_label` of :ui, the label that the transcript shows. It is read at
     * each call, so it follows a language change, and it comes from [inAppLanguage] because the application context
     * does not always have the app language.
     */
    fun speakerLabel(context: Context, number: Int): String =
        context.inAppLanguage().getString(UiR.string.ui_speaker_label, number)

    /**
     * `MemoryInfo.lowMemory`, a low-RAM device, or a known [durationMs] whose PCM does not fit the free heap
     * (`Limits.memoryAllows`, architecture 5.7). It only adds a hint to the progress card.
     */
    fun lowMemory(context: Context, durationMs: Long?, availableBytes: Long = Limits.availableHeap()): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java)
        val info = manager?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo) }
        val system = info?.lowMemory == true || manager?.isLowRamDevice == true
        val heap = durationMs != null && !Limits.memoryAllows(durationMs, availableBytes)
        return system || heap
    }

    private const val TAG = "CassiniJob"
}
