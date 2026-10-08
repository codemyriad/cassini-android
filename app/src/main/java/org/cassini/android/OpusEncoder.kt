package org.cassini.android

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Platform Opus encoder for re-encoded imports: 16 kHz mono speech at 48 kb/s, written to disk page by page as
 * packets arrive. Pre-skip and EOS trimming retain the original clock.
 */
internal object OpusEncoder {
    fun available() = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
        it.isEncoder && it.supportedTypes.any { type -> type.equals("audio/opus", true) }
    }
    const val RATE = 16000
    private const val FRAME = RATE / 50
    fun encode(audio: PcmAudio): ByteArray {
        val file = File.createTempFile("opus", ".tmp")
        try { encode(audio, file); return file.readBytes() } finally { file.delete() }
    }
    fun encode(audio: PcmAudio, out: File) = FileOutputStream(out).use { file ->
        val stream = file.buffered(); encode(audio, stream); stream.flush(); file.fd.sync()
    }
    private fun encode(audio: PcmAudio, out: OutputStream) {
        requireUser(available(), Failure.OPUS_ENCODER)
        val codec = MediaCodec.createEncoderByType("audio/opus")
        var started = false
        try {
            codec.configure(MediaFormat.createAudioFormat("audio/opus", RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 48000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * 2)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start(); started = true
            val samples = audio.samples.size.toLong() * RATE / audio.sampleRate
            require(samples in 1..RATE / 1000L * Limits.MAX_RECORDING_MS)
            // Feed a silent frame after the recording to flush encoder lookahead; EOS granule
            // discards this padding. It never enters the transcript or the playable timeline.
            var cursor = 0L; var queuedEos = false; var eos = false; var head: ByteArray? = null
            var muxer: OggOpus.Muxer? = null
            val info = MediaCodec.BufferInfo()
            var lastProgress = System.nanoTime()
            fun config(bytes: ByteArray) {
                val marker = "OpusHead".toByteArray()
                val start = (0..(bytes.size - 19)).firstOrNull { p -> marker.indices.all { bytes[p + it] == marker[it] } }
                if (start != null) {
                    // Android's unified AOPUS CSD contains delay/preroll after the identification header.
                    val channels = bytes[start + 9].toInt() and 255
                    require(channels == 1 && bytes[start + 18] == 0.toByte())
                    val header = bytes.copyOfRange(start, start + 19)
                    if (head == null) muxer = OggOpus.Muxer(out, header, CassiniDocument.tagsPacket(emptyList()),
                        samples * (48000 / RATE) + OggOpus.u16(header, 10))
                    head = header
                }
            }
            while (!eos) {
                requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
                if (!queuedEos) {
                    val index = codec.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!.apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
                        val n = minOf(FRAME.toLong(), samples + FRAME - cursor, input.remaining() / 2L).toInt()
                        repeat(n) { offset ->
                            if (cursor + offset >= samples) { input.putShort(0); return@repeat }
                            val source = (cursor + offset) * audio.sampleRate / RATE.toDouble()
                            val a = source.toInt().coerceAtMost(audio.samples.lastIndex)
                            val fraction = (source - a).toFloat()
                            val value = audio.samples[a] * (1 - fraction) + audio.samples[minOf(a + 1, audio.samples.lastIndex)] * fraction
                            input.putShort((value.coerceIn(-1f, 1f) * 32767).roundToInt().toShort())
                        }
                        val flags = if (n == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        codec.queueInputBuffer(index, 0, n * 2, cursor * 1000000 / RATE, flags)
                        cursor += n; queuedEos = n == 0; lastProgress = System.nanoTime()
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 1000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> codec.outputFormat.getByteBuffer("csd-0")?.let { b ->
                        val bytes = ByteArray(b.remaining()); b.get(bytes); config(bytes)
                    }
                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)!!
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size); buffer.get(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) config(bytes)
                        else if (bytes.isNotEmpty()) (muxer ?: error("No OpusHead from platform encoder")).add(bytes)
                        eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false); lastProgress = System.nanoTime()
                    }
                }
                requireUser(System.nanoTime() - lastProgress < 30_000_000_000L, Failure.STALLED)
            }
            (muxer ?: error("No OpusHead from platform encoder")).finish()
        } finally { if (started) codec.stop(); codec.release() }
    }
}
