package org.cassini.engine.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.OggOpus
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * API 29+: AudioRecord 48 kHz mono into the platform Opus encoder at 48 kb/s, muxed live. Pages reach the file
 * about every second and the disk about every five, so a killed process loses only the last few seconds.
 * Elapsed time is the captured sample count.
 */
internal class OpusCapture(override val file: File, private val onError: () -> Unit) : MicrophoneCapture {
    private val output = FileOutputStream(file)
    private val stream = output.buffered(1 shl 16)
    private val codec = MediaCodec.createEncoderByType("audio/opus")
    private val record: AudioRecord
    @Volatile private var samples = 0L
    @Volatile private var peak = 0
    @Volatile private var running = true
    @Volatile override var paused = false
        private set
    @Volatile private var failure: Throwable? = null
    private var muxer: OggOpus.LiveMuxer? = null
    private val thread: Thread
    override val opus = true
    override val sessionId: Int get() = record.audioSessionId
    override val elapsedMs get() = samples * 1000 / RATE
    override val amplitude get() = if (paused) 0 else peak

    init {
        try {
            codec.configure(MediaFormat.createAudioFormat("audio/opus", RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 48000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * 2)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            record = microphone()
            record.startRecording()
            require(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
        } catch (error: Exception) { codec.release(); stream.close(); file.delete(); throw error }
        thread = Thread(::capture, "cassini-recording").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    @SuppressLint("MissingPermission")
    private fun microphone(): AudioRecord {
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, RATE * 2))
        if (audio.state != AudioRecord.STATE_INITIALIZED) { audio.release(); error("Microphone unavailable") }
        return audio
    }

    private fun capture() {
        val frame = ShortArray(FRAME)
        val info = MediaCodec.BufferInfo()
        var recording = true
        var flushed = SystemClock.elapsedRealtime(); var synced = flushed
        try {
            while (running) {
                if (paused != !recording) {
                    if (paused) record.stop() else record.startRecording()
                    recording = !paused
                }
                if (!recording) { drain(info, 0); SystemClock.sleep(20); continue }
                var n = 0
                while (n < FRAME && running && !paused) {
                    val got = record.read(frame, n, FRAME - n)
                    check(got >= 0) { "AudioRecord read failed: $got" }
                    n += got
                }
                if (n == 0) continue
                var max = 0
                for (i in 0 until n) max = maxOf(max, abs(frame[i].toInt()))
                peak = max
                queue(frame, n, 0, info)
                samples += n
                drain(info, 0)
                val now = SystemClock.elapsedRealtime()
                if (now - flushed >= 1000) { stream.flush(); flushed = now }
                if (now - synced >= 5000) { output.fd.sync(); synced = now }
            }
            queue(ShortArray(FRAME), FRAME, 0, info)
            queue(frame, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM, info)
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!drain(info, 10_000) && SystemClock.elapsedRealtime() < deadline) Unit
            muxer?.let { it.finish(it.preSkip + samples) }
        } catch (error: Throwable) {
            failure = error
            try { muxer?.finish() } catch (_: Exception) {}
        } finally {
            try { stream.flush(); output.fd.sync() } catch (_: Exception) {}
            try { stream.close() } catch (_: Exception) {}
            try { record.stop() } catch (_: Exception) {}
            record.release()
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            if (muxer == null) file.delete()
        }
        if (failure != null && running) onError()
    }

    private fun queue(pcm: ShortArray, n: Int, flags: Int, info: MediaCodec.BufferInfo) {
        var index: Int
        do { index = codec.dequeueInputBuffer(10_000); if (index < 0) drain(info, 0) } while (index < 0)
        val input = codec.getInputBuffer(index)!!.apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
        for (i in 0 until n) input.putShort(pcm[i])
        codec.queueInputBuffer(index, 0, n * 2, samples * 1_000_000 / RATE, flags)
    }

    /** Moves every ready packet to the muxer; true once the encoder has signalled end of stream. */
    private fun drain(info: MediaCodec.BufferInfo, timeoutUs: Long): Boolean {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, timeoutUs)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                codec.outputFormat.getByteBuffer("csd-0")?.let { b -> val bytes = ByteArray(b.remaining()); b.get(bytes); config(bytes) }
                continue
            }
            if (index < 0) return false
            val buffer = codec.getOutputBuffer(index)!!
            buffer.position(info.offset); buffer.limit(info.offset + info.size)
            val bytes = ByteArray(info.size); buffer.get(bytes)
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) config(bytes)
            else if (bytes.isNotEmpty()) (muxer ?: error("No OpusHead from platform encoder")).add(bytes)
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
        }
    }

    private fun config(bytes: ByteArray) {
        if (muxer != null) return
        val marker = "OpusHead".toByteArray()
        val start = (0..(bytes.size - 19)).firstOrNull { p -> marker.indices.all { bytes[p + it] == marker[it] } } ?: return
        require((bytes[start + 9].toInt() and 255) == 1 && bytes[start + 18] == 0.toByte())
        muxer = OggOpus.LiveMuxer(stream, bytes.copyOfRange(start, start + 19), CassiniDocument.tagsPacket(emptyList()))
    }

    override fun togglePause() { paused = !paused }

    override suspend fun stop(): File = runInterruptible(Dispatchers.IO) {
        running = false
        thread.join(10_000)
        check(!thread.isAlive) { "Recording thread did not stop" }
        if (!file.exists()) throw IllegalStateException("No audio captured", failure)
        file
    }

    override fun release() { running = false; thread.join(10_000) }

    companion object { const val RATE = 48000; private const val FRAME = RATE / 50 }
}
