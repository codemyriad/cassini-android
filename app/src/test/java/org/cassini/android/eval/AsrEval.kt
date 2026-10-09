package org.cassini.android.eval

import org.cassini.android.ModelStore
import org.cassini.core.asr.DecodePolicy
import org.cassini.core.audio.PcmAudio
import org.cassini.engine.asr.Parakeet
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Host evaluation: runs the app's own ASR pipeline (speech detector, cutting, seams, word gate) on raw
 * float PCM, one job per line: `<f32le file> <sample rate> <output json>`. Needs a host build of
 * libsherpa-onnx-jni on java.library.path. Any Parakeet export with the same file names can be compared.
 * Not a unit test. No script starts it any more: run `main` from an IDE with the testDebugUnitTest runtime
 * classpath. It is a candidate for the :bench module.
 */
object AsrEval {
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
                ModelStore(File(modelDir)).files(),
                vad.takeIf { it != "-" },
                DecodePolicy.WHOLE_SPANS.copy(contextMs = contextMs),
            )
            val elapsedMs = (System.nanoTime() - began) / 1_000_000
            File(out).parentFile?.mkdirs()
            File(out).writeText(JSONObject(transcript.json()).put("x-elapsedMs", elapsedMs).put("x-durationMs", audio.durationMs).toString())
            println("$out ${audio.durationMs} ms in $elapsedMs ms, ${transcript.words.size} words")
        }
    }
}
