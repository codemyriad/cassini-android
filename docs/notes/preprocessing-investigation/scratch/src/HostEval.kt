package org.cassini.tools.hosteval

import org.cassini.core.asr.DecodePolicy
import org.cassini.core.audio.PcmAudio
import org.cassini.engine.asr.Parakeet
import org.cassini.engine.asr.ParakeetFiles
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Scratch variant of tools/asr-eval/AsrEval.kt. Env: POLICY=WHOLE_SPANS|REFERENCE, CUTTING=SPEECH|QUIET|WHOLE|FIXED, CONTEXT_MS, TAIL_MS. */
object HostEval {
    @JvmStatic fun main(args: Array<String>) {
        val (modelDir, vad, jobs) = args
        val base = when (System.getenv("POLICY") ?: "WHOLE_SPANS") {
            "REFERENCE" -> DecodePolicy.PARAKEET_V3_REFERENCE
            else -> DecodePolicy.WHOLE_SPANS
        }
        var policy = base
        System.getenv("CONTEXT_MS")?.let { policy = policy.copy(contextMs = it.toInt()) }
        System.getenv("TAIL_MS")?.let { policy = policy.copy(tailPaddingMs = it.toInt()) }
        val detector = vad.takeIf { it != "-" }
        val cutting = System.getenv("CUTTING")?.let { Parakeet.Cutting.valueOf(it) } ?: Parakeet.cutting(detector)
        val files = ParakeetFiles(File(modelDir, "encoder.int8.onnx").path, File(modelDir, "decoder.int8.onnx").path,
            File(modelDir, "joiner.int8.onnx").path, File(modelDir, "tokens.txt").path)
        for (line in File(jobs).readLines().filter { it.isNotBlank() }) {
            val (pcm, rate, out) = line.trim().split(Regex("\\s+"))
            if (File(out).length() > 0) continue
            val bytes = ByteBuffer.wrap(File(pcm).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val audio = PcmAudio(FloatArray(bytes.remaining()).also { bytes.get(it) }, rate.toInt())
            val began = System.nanoTime()
            val transcript = Parakeet.transcribe(audio, files, detector, policy, cutting)
            val elapsedMs = (System.nanoTime() - began) / 1_000_000
            File(out).parentFile?.mkdirs()
            val result = JSONObject(transcript.json()).put("x-elapsedMs", elapsedMs).put("x-durationMs", audio.durationMs)
                .put("x-policy", policy.toString()).put("x-cutting", cutting.name)
            File(out).writeText(result.toString())
            println("$out ${audio.durationMs} ms in $elapsedMs ms: " + transcript.words.joinToString(" ") { it.text })
        }
    }
}
