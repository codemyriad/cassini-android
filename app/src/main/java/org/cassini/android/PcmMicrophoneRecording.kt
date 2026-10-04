package org.cassini.android

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 16 kHz mono PCM16 capture on one dedicated thread. Each block is written to [file] before it is appended to
 * [audio], so a slow or failed recognizer never costs the saved recording. Reads never block: the worker polls,
 * which keeps pause, resume and stop on the same thread as the microphone and bounds them by the poll interval.
 * Pausing stops the microphone and discards what it still holds; paused time is absent from file and buffer.
 */
@SuppressLint("MissingPermission")
internal class PcmMicrophoneRecording(
    override val file: File,
    private val audio: LiveAudioBuffer,
    private val onLimit: () -> Unit,
    private val onError: (Exception) -> Unit,
) : RecordingCapture {
    private val lock = Object()
    private val main = Handler(Looper.getMainLooper())
    private val peak = AtomicInteger()
    private val limitSent = AtomicBoolean()
    private val errorSent = AtomicBoolean()
    private val writer: PcmWaveWriter
    private val recorder: AudioRecord
    private val thread: Thread
    private var wantPaused = false
    private var stopRequested = false
    @Volatile private var captured = 0
    @Volatile private var failure: Exception? = null
    private var outcome: Result<File>? = null

    override val paused: Boolean get() = synchronized(lock) { wantPaused }
    override val elapsedMs: Long get() = captured * 1000L / SAMPLE_RATE
    override val amplitude: Int get() = if (paused) 0 else peak.getAndSet(0)

    init {
        var w: PcmWaveWriter? = null
        var r: AudioRecord? = null
        try {
            w = PcmWaveWriter(file, SAMPLE_RATE)
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) throw IOException("Microphone does not support 16 kHz mono capture")
            r = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum * 2, SAMPLE_RATE),
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) throw IOException("Microphone is unavailable")
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("Microphone did not start")
            writer = w
            recorder = r
            thread = Thread(::capture, "cassini-capture")
            thread.start()
        } catch (error: Throwable) {
            runCatching { r?.release() }
            runCatching { w?.close() }
            file.delete()
            throw error
        }
    }

    override fun togglePause() {
        synchronized(lock) {
            if (stopRequested || !thread.isAlive) return
            wantPaused = !wantPaused
            lock.notifyAll()
        }
    }

    override fun stop(): File = finishCapture().getOrThrow()

    override fun release() {
        synchronized(lock) { stopRequested = true; lock.notifyAll() }
    }

    /** Stops the worker, waits for it to finalize, and caches the result so repeated calls agree. */
    private fun finishCapture(): Result<File> {
        synchronized(lock) {
            outcome?.let { return it }
            stopRequested = true
            lock.notifyAll()
        }
        // Activity runs this on a separate thread: slow storage must not orphan the recording or block the UI.
        thread.join()
        synchronized(lock) {
            outcome?.let { return it }
            val problem = failure ?: if (captured == 0) IOException("No audio was captured") else null
            val result = if (problem == null) Result.success(file) else {
                file.delete()
                Result.failure(problem)
            }
            outcome = result
            return result
        }
    }

    private fun capture() {
        var recording = true
        var limit = false
        var problem: Exception? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val block = ShortArray(READ_SAMPLES)
            while (true) {
                val stop: Boolean
                val pause: Boolean
                synchronized(lock) { stop = stopRequested; pause = wantPaused }
                if (stop) break
                if (pause) {
                    if (recording) {
                        recorder.stop()
                        recording = false
                        // Whatever the microphone still holds predates the pause or belongs to it.
                        var drained = 0
                        while (drained++ < MAX_DRAIN_READS && recorder.read(block, 0, block.size, AudioRecord.READ_NON_BLOCKING) > 0) Unit
                    }
                    synchronized(lock) { if (wantPaused && !stopRequested) lock.wait() }
                    continue
                }
                if (!recording) {
                    recorder.startRecording()
                    if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("Microphone did not restart")
                    recording = true
                }
                val read = recorder.read(block, 0, block.size, AudioRecord.READ_NON_BLOCKING)
                if (read < 0) throw IOException("Microphone read failed ($read)")
                if (read == 0) {
                    synchronized(lock) { if (!stopRequested && !wantPaused) lock.wait(IDLE_MS) }
                    continue
                }
                val take = minOf(read, MAX_SAMPLES - captured)
                // Disk first: the recording must survive anything the consumer does.
                writer.write(block, take)
                captured += take
                audio.append(block, take)
                var loudest = 0
                for (i in 0 until take) loudest = maxOf(loudest, Math.abs(block[i].toInt()))
                peak.accumulateAndGet(loudest) { a, b -> maxOf(a, b) }
                if (captured >= MAX_SAMPLES) { limit = true; break }
            }
        } catch (error: Throwable) {
            problem = error as? Exception ?: IOException("Capture failed", error)
        } finally {
            if (recording) runCatching { recorder.stop() }
            runCatching { recorder.release() }
            try { writer.close() } catch (error: IOException) { if (problem == null) problem = error }
            failure = problem
            if (problem == null) audio.finish() else audio.cancel()
        }
        problem?.let { error ->
            // A failure while the caller is stopping is reported by stop() itself.
            if (!synchronized(lock) { stopRequested } && errorSent.compareAndSet(false, true)) main.post { onError(error) }
        }
        if (limit && limitSent.compareAndSet(false, true)) main.post { onLimit() }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_SAMPLES = LiveAudioBuffer.DEFAULT_MAX_SECONDS * SAMPLE_RATE
        private const val READ_SAMPLES = SAMPLE_RATE / 10
        private const val MAX_DRAIN_READS = 64
        private const val IDLE_MS = 10L
    }
}
