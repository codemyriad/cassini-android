@file:Suppress("InvalidPackageDeclaration") // Kept flat under tools/asr-eval; see README.md.

package org.cassini.tools.asreval

import org.cassini.core.asr.DecodePolicy
import org.cassini.core.audio.PcmAudio
import org.cassini.engine.asr.Parakeet
import org.cassini.engine.asr.ParakeetFiles
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Host evaluation: runs the app's own ASR pipeline (speech detector, cutting, seams, word gate) on raw
 * float PCM, one job per line: `<f32le file> <sample rate> <output json>`. Arguments: the model directory
 * (an INT8 Parakeet export with the file names of `parakeet-ultra/`), the Silero VAD file or `-`, and the job list.
 * Needs a host build of libsherpa-onnx-jni on java.library.path. Not built by Gradle; see README.md.
 */
object AsrEval {
    private const val NANOS_PER_MILLI = 1_000_000

    @JvmStatic fun main(args: Array<String>) {
        val (modelDir, vad, jobs) = args
        for (line in File(jobs).readLines().filter { it.isNotBlank() }) {
            val (pcm, rate, out) = line.trim().split(Regex("\\s+"))
            if (File(out).length() > 0) continue
            val bytes = ByteBuffer.wrap(File(pcm).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val audio = PcmAudio(FloatArray(bytes.remaining()).also { bytes.get(it) }, rate.toInt())
            val began = System.nanoTime()
            val contextMs = System.getenv("CASSINI_CONTEXT_MS")?.toInt() ?: 0
            val transcript = Parakeet.transcribe(
                audio,
                ParakeetFiles(
                    File(modelDir, "encoder.int8.onnx").path,
                    File(modelDir, "decoder.int8.onnx").path,
                    File(modelDir, "joiner.int8.onnx").path,
                    File(modelDir, "tokens.txt").path,
                ),
                vad.takeIf { it != "-" },
                DecodePolicy.WHOLE_SPANS.copy(contextMs = contextMs),
            )
            val elapsedMs = (System.nanoTime() - began) / NANOS_PER_MILLI
            File(out).parentFile?.mkdirs()
            val result = JSONObject(transcript.json()).put("x-elapsedMs", elapsedMs).put("x-durationMs", audio.durationMs)
            File(out).writeText(result.toString())
            println("$out ${audio.durationMs} ms in $elapsedMs ms, ${transcript.words.size} words")
        }
    }
}
