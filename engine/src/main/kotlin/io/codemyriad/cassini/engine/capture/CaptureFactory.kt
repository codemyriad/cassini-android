package io.codemyriad.cassini.engine.capture

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Chooses the capture format once per process and starts captures in it. */
object CaptureFactory {
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
    fun create(context: Context, file: File, onError: () -> Unit): MicrophoneCapture =
        create(context, file, null, onError)

    /** AAC uses MediaRecorder and exposes no PCM tap. Close the feed immediately on that path. */
    fun create(context: Context, file: File, live: LiveMicrophonePcm?, onError: () -> Unit): MicrophoneCapture {
        var opusStarted = false
        try {
            if (file.extension != "opus") return AacCapture(context, file, onError)
            return OpusCapture(file, live, onError).also { opusStarted = true }
        } finally {
            if (!opusStarted) live?.finish()
        }
    }
}
