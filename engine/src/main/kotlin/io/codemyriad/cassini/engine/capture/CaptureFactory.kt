package io.codemyriad.cassini.engine.capture

import android.content.Context
import android.media.AudioRecord
import android.media.MediaCodecList
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Chooses the capture format once per process and starts captures in it. */
object CaptureFactory {
    /** Optional test-only post-read hook invoked inside [OpusCapture] after `AudioRecord.read`; null in production. */
    fun interface OpusReadHook {
        fun onRead(record: AudioRecord, ringSamples: Int, frame: ShortArray, count: Int, sampleOffset: Long)
    }

    @Volatile private var platformOpus: Boolean? = null

    /**
     * Whether the platform has an Opus encoder. The MediaCodecList probe can take a noticeable time, so it runs
     * once, off the caller's thread, and its answer is kept for the process.
     */
    suspend fun platformOpusEncoder(): Boolean =
        platformOpus ?: withContext(Dispatchers.IO) { platformOpusEncoderNow() }

    /** [platformOpusEncoder] on the calling thread, for code that already runs on a worker. Never call it on main. */
    fun platformOpusEncoderNow(): Boolean = platformOpus ?: MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .any { it.isEncoder && it.supportedTypes.any { type -> type.equals("audio/opus", true) } }
        .also { platformOpus = it }

    /** `opus` on API 29+ with a platform Opus encoder ([OpusCapture]), otherwise `m4a` ([AacCapture]). */
    suspend fun extension(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && platformOpusEncoder()) "opus" else "m4a"

    /** Starts capturing into [file], whose extension came from [extension]. [onError] runs on the capture thread. */
    fun create(
        context: Context,
        file: File,
        onError: () -> Unit,
        opusReadHook: OpusReadHook? = null,
    ): MicrophoneCapture =
        if (file.extension == "opus") OpusCapture(file, onError, opusReadHook) else AacCapture(context, file, onError)
}
