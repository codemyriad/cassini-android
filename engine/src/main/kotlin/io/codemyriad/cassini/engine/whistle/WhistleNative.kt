package io.codemyriad.cassini.engine.whistle

import java.nio.ByteBuffer

/** Bootstrap only: no link-time Needle/ORT dependency or neural constructor. Owns one dedicated worker process. */
object WhistleNative {
    fun bootstrap() = System.loadLibrary("cassini_whistle_jni")
    external fun open(needle: String, ort: String, model: ByteBuffer, vad: ByteBuffer)
    external fun vadState(): FloatArray
    external fun restoreVad(state: FloatArray)
    external fun probability(frame: FloatArray): Float
    external fun transcribe(samples: FloatArray): ByteArray
}
