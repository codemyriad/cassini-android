package org.cassini.android

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/** Foreground AAC capture. Finalized audio is retained even if recognition fails. */
internal class MicrophoneRecording(context: Context, val file: File, onLimit: () -> Unit, onError: () -> Unit) {
    private var recorder: MediaRecorder? = null
    private var segmentStarted = 0L
    private var accumulatedMs = 0L
    var paused = false
        private set
    val elapsedMs get() = accumulatedMs + if (paused || recorder == null) 0 else SystemClock.elapsedRealtime() - segmentStarted
    val amplitude get() = if (paused) 0 else try { recorder?.maxAmplitude ?: 0 } catch (_: IllegalStateException) { 0 }

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
            media.setAudioEncodingBitRate(96000)
            media.setOutputFile(file.absolutePath)
            // Leave room for encoder padding within the bounded whole-utterance decoder.
            media.setMaxDuration(MAX_DURATION_MS)
            media.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onLimit() }
            media.setOnErrorListener { _, _, _ -> onError() }
            media.prepare(); media.start()
            segmentStarted = SystemClock.elapsedRealtime()
        } catch (error: Exception) { media.release(); recorder = null; file.delete(); throw error }
    }

    fun togglePause() {
        val media = recorder ?: return
        if (paused) { media.resume(); segmentStarted = SystemClock.elapsedRealtime(); paused = false }
        else { media.pause(); accumulatedMs = elapsedMs; paused = true }
    }

    fun stop(): File {
        val media = recorder ?: error("Recording already stopped")
        accumulatedMs = elapsedMs
        recorder = null
        try { media.stop() }
        catch (error: RuntimeException) { file.delete(); throw error }
        finally { media.release() }
        return file
    }

    fun release() { recorder?.release(); recorder = null }
    companion object { const val MAX_DURATION_MS = AudioDecoder.MAX_SECONDS * 1000 - 1000 }
}
