package org.cassini.android

import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class SpeakerIdentificationTest {
    @get:org.junit.Rule val preserveLibrary = PreserveLibraryRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture(name: String): PcmAudio {
        val file = File(context.cacheDir, "speaker-$name")
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        try { return AudioDecoder.decode(context, Uri.fromFile(file)) } finally { file.delete() }
    }
    private fun plainSession(): File {
        val file = File(context.cacheDir, "speaker-consent.wav")
        instrumentation.context.assets.open("italian-smoke.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
        SessionStore(context.filesDir).save(Session(uri = Uri.fromFile(file).toString(), name = file.name, durationMs = 15800,
            transcript = Transcript(listOf(Word("spk_1", 1000, 1200, "Ciao")))))
        return file
    }
    @Test fun speakerIdentificationRequiresAnExplicitAction() {
        val file = plainSession()
        val original = SessionStore(context.filesDir).load().transcript
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            SystemClock.sleep(500)
            scenario.onActivity {
                assertEquals(View.VISIBLE, it.findViewById<Button>(R.id.speakers_button).visibility)
                assertTrue(it.findViewById<Button>(R.id.speakers_button).isEnabled)
                assertEquals(View.GONE, it.findViewById<Button>(R.id.cancel_operation).visibility)
            }
            SystemClock.sleep(1000)
            assertEquals(original, SessionStore(context.filesDir).load().transcript)
            assertNull(SessionStore(context.filesDir).load().document)
        } finally { scenario.close(); file.delete() }
    }
    @Test fun noWordsMeansNoSpeakerIdentification() {
        SessionStore(context.filesDir).save(Session(transcript = Transcript(emptyList())))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertEquals(View.GONE, it.findViewById<Button>(R.id.speakers_button).visibility) }
        }
    }
    @Test fun downloadingSpeakerModelsRequiresConsent() {
        org.junit.Assume.assumeFalse("Requires speaker models to be absent", DiarizationModels.inFiles(context.filesDir).ready())
        val file = plainSession()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.findViewById<Button>(R.id.speakers_button).performClick() }
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
            assertFalse(DiarizationModels.inFiles(context.filesDir).ready())
            assertNull(SessionStore(context.filesDir).load().document)
            assertEquals("Ciao", SessionStore(context.filesDir).load().transcript!!.words.single().text)
        } finally { scenario.close(); file.delete() }
    }
    private fun twoVoiceDocument(): Pair<File, CassiniDocument> {
        val first = fixture("italian-smoke.wav"); val second = fixture("youtube-smoke.wav")
        val audio = PcmAudio(resampleTo16k(first.samples, first.sampleRate) + FloatArray(16000) +
            resampleTo16k(second.samples, second.sampleRate), 16000)
        val boundary = first.durationMs + 1000
        val transcript = Transcript(listOf(Word("spk_1", 3000, 3400, "Prima"),
            Word("spk_1", 9000, 9400, "voce."), Word("spk_1", boundary + 3000, boundary + 3400, "Seconda"),
            Word("spk_1", boundary + 13000, boundary + 13400, "voce.")))
        val bytes = CassiniDocument.create(OpusEncoder.encode(audio), transcript, "Two voices",
            JSONObject().put("engine", "fixture; no ASR"))
        val file = File(context.cacheDir, "speaker-two-voices.opus").also { it.writeBytes(bytes) }
        val doc = CassiniDocument.read(bytes)
        SessionStore(context.filesDir).save(Session(uri = Uri.fromFile(file).toString(), name = file.name,
            document = file.path, selectedVariant = doc.defaultId, durationMs = audio.durationMs))
        return file to doc
    }
    private fun startIdentification(scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { ready = it.findViewById<Button>(R.id.speakers_button).isEnabled }
            if (!ready) SystemClock.sleep(50)
        }
        assertTrue(ready)
        scenario.onActivity { it.findViewById<Button>(R.id.speakers_button).performClick() }
    }
    @Test fun actualModelsAddAPlayableVariantWithoutChangingRecognitionOrAudio() {
        DiarizationModels.inFiles(context.filesDir).install { }
        val (file, original) = twoVoiceDocument()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            startIdentification(scenario)
            val began = SystemClock.uptimeMillis()
            val sessions = SessionStore(context.filesDir)
            while (sessions.load().document == file.path && SystemClock.uptimeMillis() - began < 180000) SystemClock.sleep(100)
            val saved = sessions.load()
            val path = saved.document
            assertTrue("Speaker pass must save a new variant", path != null && path != file.path)
            val next = CassiniDocument.read(File(path!!).readBytes())
            val words = next.selected(null)!!.transcript!!.words
            assertEquals("ok", next.state)
            assertEquals(2, next.variants.size)
            assertEquals(original.selected(null)?.transcript, next.selected(original.defaultId)?.transcript)
            assertEquals(original.selected(null)!!.transcript!!.words, words.map { it.copy(speaker = "spk_1") })
            assertEquals(2, words.map { it.speaker }.distinct().size)
            assertEquals(words[0].speaker, words[1].speaker)
            assertEquals(words[2].speaker, words[3].speaker)
            assertNotEquals(words[0].speaker, words[2].speaker)
            assertEquals(OggOpus.read(file.readBytes()).digest(), OggOpus.read(File(path).readBytes()).digest())
            val processing = next.manifest!!.getJSONObject("provenance").getJSONObject("speechToText").getJSONObject(next.defaultId!!)
            assertEquals("fixture; no ASR", processing.getString("engine"))
            assertTrue(processing.has("x-speakerDiarization"))
            assertTrue(AudioDecoder.decode(context, Uri.fromFile(File(path))).durationMs > 40000)
            scenario.onActivity {
                val text = it.findViewById<TextView>(R.id.transcript_text).text.toString()
                assertTrue(text.contains(it.getString(R.string.speaker_label, 1)))
                assertTrue(text.contains(it.getString(R.string.speaker_label, 2)))
            }
            println("Speaker identification: ${SystemClock.uptimeMillis() - began} ms for ${saved.durationMs} ms, correct two-voice fixture attribution")
        } finally { scenario.close(); file.delete() }
    }
    @Test fun automaticDetectionDistinguishesTheTwoFixtureVoices() {
        DiarizationModels.inFiles(context.filesDir).install { }
        val (file, _) = twoVoiceDocument()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            startIdentification(scenario)
            val deadline = SystemClock.uptimeMillis() + 180000
            val sessions = SessionStore(context.filesDir)
            while (sessions.load().document == file.path && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            val saved = sessions.load().document
            assertTrue(saved != null && saved != file.path)
            val words = CassiniDocument.read(File(saved!!).readBytes()).selected(null)!!.transcript!!.words
            assertEquals(2, words.map { it.speaker }.distinct().size)
            assertEquals(words[0].speaker, words[1].speaker)
            assertEquals(words[2].speaker, words[3].speaker)
            assertNotEquals(words[0].speaker, words[2].speaker)
        } finally { scenario.close(); file.delete() }
    }
    @Test fun cancellingNativeIdentificationRetainsTheOriginalVariant() {
        DiarizationModels.inFiles(context.filesDir).install { }
        val (file, original) = twoVoiceDocument()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            startIdentification(scenario)
            val deadline = SystemClock.uptimeMillis() + 180000
            var embedding = false
            while (!embedding && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity {
                    val progress = it.findViewById<ProgressBar>(R.id.operation_progress)
                    embedding = !progress.isIndeterminate && progress.progress in 1..99
                }
                if (!embedding) SystemClock.sleep(10)
            }
            assertTrue("Cancel during actual native work", embedding)
            scenario.onActivity { it.findViewById<Button>(R.id.cancel_operation).performClick() }
            assertTrue("Cancelled call must release its native models", NativeInference.lease.tryAcquire(180, TimeUnit.SECONDS))
            NativeInference.lease.release()
            instrumentation.waitForIdleSync()
            assertEquals(file.path, SessionStore(context.filesDir).load().document)
            assertEquals(original.defaultId, SessionStore(context.filesDir).load().selectedVariant)
            assertEquals(1, CassiniDocument.read(file.readBytes()).variants.size)
        } finally { scenario.close(); file.delete() }
    }
}
