package org.cassini.android

import android.content.Intent
import android.net.Uri
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PortableDocumentTest {
    private fun finishScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.distinct()
                .filter { it is MainActivity || it is SettingsActivity }.forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
    }
    @Test fun platformOpusEncodingKeepsDurationAndSpeechClock() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val source = File(context.cacheDir, "opus-source.wav")
        instrumentation.context.assets.open("italian-smoke.wav").use { input -> source.outputStream().use { input.copyTo(it) } }
        val original = AudioDecoder.decode(context, Uri.fromFile(source))
        val file = File(context.filesDir, "portable-encoder-test.opus")
        file.writeBytes(OpusEncoder.encode(original))
        val stream = OggOpus.read(file.readBytes())
        assertEquals(original.samples.size * 48000L / original.sampleRate, stream.sampleCount)
        assertEquals(original.durationMs, stream.durationMs)
        assertTrue(stream.digest().matches(Regex("[a-f0-9]{64}")))
        val decoded = AudioDecoder.decode(context, Uri.fromFile(file))
        assertEquals(original.durationMs, decoded.durationMs)
        // Compare the beginning and end of speech after resampling, not just the container's duration.
        fun edge(audio: PcmAudio, reverse: Boolean): Long {
            val indices = if (reverse) audio.samples.indices.reversed() else audio.samples.indices
            return (indices.firstOrNull { kotlin.math.abs(audio.samples[it]) > .04f } ?: 0) * 1000L / audio.sampleRate
        }
        assertTrue("Speech onset moved", kotlin.math.abs(edge(original, false) - edge(decoded, false)) < 30)
        assertTrue("Speech tail moved", kotlin.math.abs(edge(original, true) - edge(decoded, true)) < 30)
    }
    @Test fun threeMinuteOpusTrimsPaddingAtTheTranscriptionLimit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        org.junit.Assume.assumeTrue("Fetch the optional three-minute YouTube fixture", instrumentation.context.assets.list("")!!.contains("youtube-long.wav"))
        val wav = File(context.cacheDir, "portable-long-source.wav")
        instrumentation.context.assets.open("youtube-long.wav").use { input -> wav.outputStream().use { input.copyTo(it) } }
        val original = AudioDecoder.decode(context, Uri.fromFile(wav))
        val file = File(context.filesDir, "portable-long-test.opus")
        file.writeBytes(OpusEncoder.encode(original))
        assertEquals(8640000L, OggOpus.read(file.readBytes()).sampleCount)
        val decoded = AudioDecoder.decode(context, Uri.fromFile(file))
        assertEquals(180000L, decoded.durationMs)
        assertEquals(8640000, decoded.samples.size)
    }

    @Test fun transcriptionCreatesASealedPortableDocument() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val model = ModelStore(File(context.filesDir, "parakeet-v3"), false)
        org.junit.Assume.assumeTrue("Install the INT8 model for the end-to-end transcription check", model.ready())
        finishScreens()
        val sessions = SessionStore(context.filesDir); val originalSession = sessions.load()
        val wav = File(context.cacheDir, "portable-asr-source.wav")
        instrumentation.context.assets.open("youtube-smoke.wav").use { input -> wav.outputStream().use { input.copyTo(it) } }
        sessions.save(Session(uri = Uri.fromFile(wav).toString(), name = "Italian ASR.wav", fp32 = false))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.findViewById<Button>(R.id.transcribe_button).performClick() }
            val deadline = android.os.SystemClock.uptimeMillis() + 120000
            var success = false
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                if (sessions.load().document != null) { success = true; break }
                android.os.SystemClock.sleep(100)
            }
            assertTrue("ASR should create a portable document automatically", success)
            val saved = sessions.load()
            val doc = CassiniDocument.read(File(saved.document!!).readBytes())
            assertEquals("ok", doc.state)
            assertTrue(doc.selected(null)!!.transcript!!.words.size > 60)
            val step = doc.manifest!!.getJSONObject("provenance").getJSONObject("speechToText").getJSONObject(doc.defaultId!!)
            assertTrue(step.getString("model").contains("INT8"))
            assertTrue(step.getString("version").contains(model.revision))
            assertEquals(30000L, doc.manifest.getJSONObject("audio").getLong("durationMs"))
            File(context.filesDir, "portable-asr-test.opus").writeBytes(File(saved.document).readBytes())
        } finally { scenario.close(); finishScreens(); sessions.save(originalSession) }
    }

    @android.annotation.TargetApi(33)
    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun openSaveAndReopenUseDocumentAsAuthority() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        finishScreens()
        val sessions = SessionStore(context.filesDir); val originalSession = sessions.load(); val language = AppLanguage.selected(context)
        context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags("en")
        val audio = File(context.filesDir, "portable-encoder-test.opus")
        if (!audio.exists()) {
            val wav = File(context.cacheDir, "portable-source.wav")
            instrumentation.context.assets.open("italian-smoke.wav").use { input -> wav.outputStream().use { input.copyTo(it) } }
            audio.writeBytes(OpusEncoder.encode(AudioDecoder.decode(context, Uri.fromFile(wav))))
        }
        val words = Transcript(listOf(Word("spk_a", 100, 600, "Ciao."), Word("spk_b", 8000, 8500, "Benvenuti.")), "it")
        val first = CassiniDocument.create(audio.readBytes(), words, "Portable Italian", JSONObject().put("model", "test"))
        val initial = CassiniDocument.read(first)
        initial.manifest!!.getJSONArray("speakers").getJSONObject(0).put("label", "Ada")
        initial.manifest.getJSONArray("speakers").getJSONObject(1).put("label", "Ben")
        initial.manifest.put("x-test", JSONObject().put("preserve", true))
        val source = File(context.cacheDir, "portable-ui-source.opus")
        source.writeBytes(CassiniDocument.create(first, words, "Ignored", JSONObject(), initial))
        val exported = File(context.filesDir, "portable-ui-export.opus")
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(source)))
        fun await(description: String, condition: (MainActivity) -> Boolean) {
            val deadline = android.os.SystemClock.uptimeMillis() + 20000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                var ready = false; scenario.onActivity { ready = condition(it) }
                if (ready) return
                android.os.SystemClock.sleep(50)
            }
            fail(description)
        }
        try {
            await("Cassini must open without ASR") { it.findViewById<TextView>(R.id.transcript_text).text.contains("Benvenuti.") && it.findViewById<Button>(R.id.export_button).isEnabled }
            scenario.onActivity {
                val text = it.findViewById<TextView>(R.id.transcript_text).text.toString()
                assertTrue(text.contains("Ada")); assertTrue(text.contains("Ben"))
                assertEquals("Cassini · audio verified", it.findViewById<TextView>(R.id.document_trust).text.toString())
                assertTrue(it.findViewById<Button>(R.id.variant_button).isShown)
                @Suppress("DEPRECATION")
                MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
                    .apply { isAccessible = true }.invoke(it, MainActivity.SAVE_DOCUMENT, android.app.Activity.RESULT_OK, Intent().setData(Uri.fromFile(exported)))
            }
            await("Save must complete") { it.findViewById<TextView>(R.id.operation_status).text.contains("Cassini saved") }
            assertArrayEquals("Save must preserve every unknown field, variant, tag and audio packet", source.readBytes(), exported.readBytes())
            val saved = sessions.load()
            assertNotNull(saved.document); assertNull("Session cache must not replace the portable body", saved.transcript)
            scenario.recreate()
            await("Document body must restore after activity recreation") { it.findViewById<TextView>(R.id.transcript_text).text.contains("Benvenuti.") }
            assertTrue(CassiniDocument.read(exported.readBytes()).manifest!!.getJSONObject("x-test").getBoolean("preserve"))
        } finally {
            scenario.close(); finishScreens(); context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags(language); sessions.save(originalSession)
        }
    }
}
