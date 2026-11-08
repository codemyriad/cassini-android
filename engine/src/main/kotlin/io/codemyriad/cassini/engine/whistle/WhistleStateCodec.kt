package io.codemyriad.cassini.engine.whistle

import io.codemyriad.cassini.core.asr.SileroInputFilter
import io.codemyriad.cassini.core.asr.whistle.WhistleSpeechGate
import io.codemyriad.cassini.core.audio.whistle.WhistleResampler
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Bounded internal sidecar, not a portable recording format. Caller owns streams and durable atomic publication. */
object WhistleStateCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAGIC = 0x43575331 // CWS1. A schema change requires a new magic and compatibility decision.
    private const val RING = 262144
    private const val FRAME = 512
    private const val VOTES = 3
    private const val HISTORY = 128
    private const val VAD = 320
    private const val FILTER = 4

    private val signature = MessageDigest.getInstance("SHA-256").digest(
        listOf(
            "needle-3.3.0-93e88ede867e4311c39151285e74df513e4b835b",
            "archive-9be8b4b185ef1710f57179e3cc68835ac67fca8470bdbc6915d6966646d95385",
            "model-b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb",
            "vad-1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3",
            "ort-e40f09d07dc53726b8bfbf48a7907673b8f86718a057655a62790a39874a7302",
            "fir-52423b160d7d18e1c535a0750cc615e7df27c33f9e56f0f2b6bd7f27fd204e74",
            SileroInputFilter.STAGE,
            "gate-512-vote2of3-pre10-0.5-0.35-silence9600-pause224000-2048-limit240000",
            "native-vad-state256-context64-api23-intra1-inter1",
            "policy-arm64-dotprod-api29-v1",
        ).joinToString("\n").toByteArray(Charsets.UTF_8),
    )

    fun write(target: OutputStream, state: WhistleStream.State) {
        WhistleStream.validate(state)
        val out = DataOutputStream(BufferedOutputStream(target))
        out.writeInt(MAGIC)
        out.write(signature)
        out.writeInt(state.rate)
        out.writeLong(state.count)
        out.writeLong(state.rawFirst)
        out.writeInt(state.raw.size)
        floats(out, state.raw)
        state.filter.forEach(out::writeDouble)
        gate(out, state.gate)
        out.writeInt(state.resampler.rate)
        floats(out, state.resampler.history)
        out.writeLong(state.resampler.count)
        out.writeLong(state.resampler.next)
        floats(out, state.vad)
        out.writeInt(state.frames)
        out.writeInt(state.voiced)
        out.writeFloat(state.maximum)
        out.flush()
    }

    fun read(source: InputStream): WhistleStream.State {
        val input = DataInputStream(BufferedInputStream(source))
        require(input.readInt() == MAGIC) { "Unknown Whistle checkpoint schema" }
        val pinned = ByteArray(signature.size).also(input::readFully)
        require(pinned.contentEquals(signature)) { "Whistle checkpoint engine/model/pipeline mismatch" }
        val rate = input.readInt()
        val count = input.readLong()
        val first = input.readLong()
        val length = input.readInt().also { require(it in 0..RING) }
        val raw = floats(input, length)
        val filter = DoubleArray(FILTER) { input.readDouble() }
        val gate = gate(input)
        val resampler = WhistleResampler.State(input.readInt(), floats(input, HISTORY), input.readLong(), input.readLong())
        val state = WhistleStream.State(
            rate, count, first, raw, filter, gate, resampler, floats(input, VAD),
            input.readInt(), input.readInt(), input.readFloat(),
        )
        require(input.read() == -1) { "Trailing Whistle checkpoint bytes" }
        WhistleStream.validate(state)
        return state
    }

    private fun gate(out: DataOutputStream, state: WhistleSpeechGate.State) {
        floats(out, state.frame)
        state.votes.forEach(out::writeBoolean)
        out.writeInt(state.used)
        out.writeLong(state.position)
        out.writeInt(state.pre)
        out.writeInt(state.voteAt)
        out.writeBoolean(state.active)
        out.writeInt(state.quiet)
        out.writeLong(state.duration)
        out.writeLong(state.start)
    }

    private fun gate(input: DataInputStream) = WhistleSpeechGate.State(
        floats(input, FRAME), BooleanArray(VOTES) { boolean(input) }, input.readInt(), input.readLong(),
        input.readInt(), input.readInt(), boolean(input), input.readInt(), input.readLong(), input.readLong(),
    )

    private fun boolean(input: DataInputStream): Boolean = input.readUnsignedByte().also { require(it in 0..1) } == 1
    private fun floats(out: DataOutputStream, values: FloatArray) = values.forEach(out::writeFloat)
    private fun floats(input: DataInputStream, length: Int) = FloatArray(length) { input.readFloat() }
}
