package org.cassini.android.adapters

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import org.cassini.android.R
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
            placeholderLabel = { number -> app.getString(R.string.speaker_label, number) },
            lowMemory = { lowMemory(app) },
            warn = { message, error -> Log.w(TAG, message, error) },
        )
    }

    /** `MemoryInfo.lowMemory` or a low-RAM device. It only adds a hint to the progress card. */
    fun lowMemory(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return info.lowMemory || manager.isLowRamDevice
    }

    private const val TAG = "CassiniJob"
}
