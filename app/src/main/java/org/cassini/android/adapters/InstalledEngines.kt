package org.cassini.android.adapters

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import org.cassini.core.audio.Limits
import org.cassini.core.models.ModelCatalog
import org.cassini.core.models.ModelDescriptor
import org.cassini.engine.asr.ParakeetFiles
import org.cassini.engine.decode.AudioDecoder
import org.cassini.engine.job.DecoderAudioSource
import org.cassini.engine.job.JobEngines
import org.cassini.engine.job.JobEnvironment
import org.cassini.engine.job.OpusEncoders
import org.cassini.engine.job.ParakeetRecognizers
import org.cassini.engine.job.SortformerDiarizers
import org.cassini.engine.job.VoiceprintPrints
import java.io.File
import org.cassini.ui.R as UiR

/**
 * The native seams of a job over the models installed in [modelRoot] (architecture 2.2 and 5.4). The paths are read
 * when a job opens each seam, so a model installed by the job's own download is used.
 */
object InstalledEngines {
    fun of(context: Context, modelRoot: File, cache: File): JobEngines = JobEngines(
        recognizers = ParakeetRecognizers(parakeet = { parakeet(modelRoot) }, detector = { detector(modelRoot) }),
        diarizers = SortformerDiarizers { only(ModelCatalog.DIARIZATION, modelRoot) },
        prints = VoiceprintPrints({ File(only(ModelCatalog.VOICEPRINT, modelRoot)) }),
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

    /** The speech detector when it is installed; without it recordings are cut at quiet points. */
    fun detector(modelRoot: File): String? =
        ModelCatalog.VAD.takeIf { it.ready(modelRoot) }?.let { only(it, modelRoot) }

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
     * each call, so it follows a language change, and below API 33 it comes from `getContextForLanguage` because the
     * application context keeps the system language there.
     */
    fun speakerLabel(context: Context, number: Int): String =
        ContextCompat.getContextForLanguage(context).getString(UiR.string.ui_speaker_label, number)

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
