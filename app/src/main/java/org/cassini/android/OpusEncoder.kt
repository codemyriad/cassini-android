package org.cassini.android

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Platform Opus encoder. Mono speech at 48 kb/s; pre-skip and EOS trimming retain the original clock. */
internal object OpusEncoder {
    fun available() = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
        it.isEncoder && it.supportedTypes.any { type -> type.equals("audio/opus", true) }
    }
    fun encode(audio: PcmAudio): ByteArray {
        requireUser(available(), Failure.OPUS_ENCODER)
        val codec = MediaCodec.createEncoderByType("audio/opus")
        var started = false
        try {
            codec.configure(MediaFormat.createAudioFormat("audio/opus", 48000, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 48000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1920)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start(); started = true
            val samples = audio.samples.size * 48000L / audio.sampleRate
            require(samples in 1..48L * Limits.MAX_RECORDING_MS)
            // Feed a silent frame after the recording to flush encoder lookahead; EOS granule
            // discards this padding. It never enters the transcript or the playable timeline.
            var cursor = 0L; var queuedEos = false; var eos = false; var head: ByteArray? = null
            val packets = mutableListOf<ByteArray>()
            val info = MediaCodec.BufferInfo()
            var lastProgress = System.nanoTime()
            fun config(bytes: ByteArray) {
                val marker = "OpusHead".toByteArray()
                val start = (0..(bytes.size - 19)).firstOrNull { p -> marker.indices.all { bytes[p + it] == marker[it] } }
                if (start != null) {
                    // Android's unified AOPUS CSD contains delay/preroll after the identification header.
                    val channels = bytes[start + 9].toInt() and 255
                    require(channels == 1 && bytes[start + 18] == 0.toByte())
                    head = bytes.copyOfRange(start, start + 19)
                }
            }
            while (!eos) {
                requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
                if (!queuedEos) {
                    val index = codec.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!.apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
                        val n = minOf(960L, samples + 960 - cursor, input.remaining() / 2L).toInt()
                        repeat(n) { offset ->
                            if (cursor + offset >= samples) { input.putShort(0); return@repeat }
                            val source = (cursor + offset) * audio.sampleRate / 48000.0
                            val a = source.toInt().coerceAtMost(audio.samples.lastIndex)
                            val fraction = (source - a).toFloat()
                            val value = audio.samples[a] * (1 - fraction) + audio.samples[minOf(a + 1, audio.samples.lastIndex)] * fraction
                            input.putShort((value.coerceIn(-1f, 1f) * 32767).roundToInt().toShort())
                        }
                        val flags = if (n == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        codec.queueInputBuffer(index, 0, n * 2, cursor * 1000000 / 48000, flags)
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
                        else if (bytes.isNotEmpty()) packets += bytes
                        eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false); lastProgress = System.nanoTime()
                    }
                }
                requireUser(System.nanoTime() - lastProgress < 30_000_000_000L, Failure.STALLED)
            }
            val header = head ?: error("No OpusHead from platform encoder")
            val preSkip = OggOpus.u16(header, 10)
            val finalGranule = samples + preSkip
            require(packets.sumOf { OggOpus.packetSamples(it).toLong() } >= finalGranule) { "Opus encoder did not flush its lookahead" }
            // Keep only the packets covering the playable samples; encoder flush packets may extend beyond them.
            var covered = 0L
            val used = packets.takeWhile { if (covered >= finalGranule) false else { covered += OggOpus.packetSamples(it); true } }
            return OggOpus.mux(OggOpus.Stream(header, CassiniDocument.tagsPacket(emptyList()), used, finalGranule, true),
                CassiniDocument.tagsPacket(emptyList()))
        } finally { if (started) codec.stop(); codec.release() }
    }
}
