package io.codemyriad.cassini

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.engine.asr.Parakeet
import io.codemyriad.cassini.test.NeedsModels
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Temporary D86 measurement harness, removed after recording both encoder results. */
@NeedsModels
class D86PinProbeTest {
    @Test fun compareEnglishEncoders() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val models = InstalledModels(context.filesDir)
        val candidate = models.parakeet()
        val baseline = candidate.copy(encoder = File(context.filesDir, "d86-old-encoder.onnx").path)
        val input = instrumentation.context.assets.open("dc-quiet-jfk-v1.wav").use { it.readBytes() }
        // The existing D26.1 control restores the exact +2060 PCM16 offset before Android decoding/resampling.
        val control = input.copyOf()
        val pcm = ByteBuffer.wrap(control).order(ByteOrder.LITTLE_ENDIAN)
        var cursor = 12
        while (String(control, cursor, 4, Charsets.US_ASCII) != "data") cursor += 8 + pcm.getInt(cursor + 4)
        val end = cursor + 8 + pcm.getInt(cursor + 4)
        cursor += 8
        while (cursor < end) {
            pcm.putShort(cursor, (pcm.getShort(cursor).toInt() + 2060).toShort())
            cursor += 2
        }
        for ((name, bytes) in listOf("dc" to input, "control" to control)) {
            val file = File(context.cacheDir, "d86-$name.wav").apply { writeBytes(bytes) }
            try {
                val audio = audioDecoder(context).decode(Uri.fromFile(file))
                for ((label, encoder) in listOf("baseline" to baseline, "candidate" to candidate)) {
                    val transcript = Parakeet.transcribe(audio, encoder, models.detector())
                    assertTrue(transcript.words.isNotEmpty())
                    val report = JSONObject().put("fixture", name).put("encoder", label)
                        .put("revision", if (label == "candidate") ModelCatalog.PARAKEET_REVISION else "18cb6230")
                        .put("transcript", JSONObject(transcript.json()))
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "D86_REPORT=$report\n") })
                }
            } finally { file.delete() }
        }
    }
}
