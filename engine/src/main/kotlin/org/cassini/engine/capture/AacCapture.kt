package org.cassini.engine.capture

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File

/** API 26-28: MediaRecorder AAC in MP4 at 32 kb/s. The index is written on stop, so a crash loses the clip. */
internal class AacCapture(context: Context, override val file: File, onError: () -> Unit) : MicrophoneCapture {
    private var recorder: MediaRecorder? = null
    private var segmentStarted = 0L
    private var accumulatedMs = 0L
    override var paused = false
        private set
    override val opus = false
    override val elapsedMs get() = accumulatedMs + if (paused || recorder == null) 0 else SystemClock.elapsedRealtime() - segmentStarted
    override val amplitude get() = if (paused) 0 else try { recorder?.maxAmplitude ?: 0 } catch (_: IllegalStateException) { 0 }

    init {
        @Suppress("DEPRECATION")
        val media = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        recorder = media
        try {
            media.setAudioSource(MediaRecorder.AudioSource.MIC)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioChannels(1)
            media.setAudioSamplingRate(48000)
            media.setAudioEncodingBitRate(32000)
            media.setOutputFile(file.absolutePath)
            media.setOnErrorListener { _, _, _ -> onError() }
            media.prepare(); media.start()
            segmentStarted = SystemClock.elapsedRealtime()
        } catch (error: Exception) { media.release(); recorder = null; file.delete(); throw error }
    }

    override fun togglePause() {
        val media = recorder ?: return
        if (paused) { media.resume(); segmentStarted = SystemClock.elapsedRealtime(); paused = false }
        else { media.pause(); accumulatedMs = elapsedMs; paused = true }
    }

    override suspend fun stop(): File = runInterruptible(Dispatchers.IO) { stopNow() }

    private fun stopNow(): File {
        val media = recorder ?: error("Recording already stopped")
        accumulatedMs = elapsedMs
        recorder = null
        try { media.stop() }
        catch (error: RuntimeException) { file.delete(); throw error }
        finally { media.release() }
        return file
    }

    override fun release() { if (recorder != null) try { stopNow() } catch (_: Exception) {} }
}
