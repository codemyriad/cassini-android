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
    @Test fun aTranscriptLeavesNoSeparateTranscribeOrSpeakerStep() {
        val file = File(context.cacheDir, "speaker-transcribed.wav")
        instrumentation.context.assets.open("italian-smoke.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
        SessionStore(context.filesDir).save(Session(uri = Uri.fromFile(file).toString(), name = file.name, durationMs = 15800,
            transcript = Transcript(listOf(Word("spk_1", 1000, 1200, "Ciao")))))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            SystemClock.sleep(500)
            scenario.onActivity {
                assertEquals(View.GONE, it.findViewById<Button>(R.id.transcribe_button).visibility)
                assertEquals(View.GONE, it.findViewById<Button>(R.id.cancel_operation).visibility)
            }
        } finally { scenario.close(); file.delete() }
    }
    @Test fun downloadingModelsRequiresConsent() {
        org.junit.Assume.assumeFalse("Requires the transcription model to be absent", ModelStore(File(context.filesDir, "parakeet-v3")).ready())
        val file = File(context.cacheDir, "speaker-consent.wav")
        instrumentation.context.assets.open("italian-smoke.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
        SessionStore(context.filesDir).save(Session(uri = Uri.fromFile(file).toString(), name = file.name))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.findViewById<Button>(R.id.transcribe_button).performClick() }
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
            assertFalse(DiarizationModels.inFiles(context.filesDir).ready())
            assertNull(SessionStore(context.filesDir).load().document)
        } finally { scenario.close(); file.delete() }
    }
    /** Two different voices with a second of silence between them, as a plain recording. Returns the boundary. */
    private fun twoVoiceRecording(): Pair<File, Long> {
        org.junit.Assume.assumeTrue("Install the INT8 model for the end-to-end speaker check", ModelStore(File(context.filesDir, "parakeet-v3")).ready())
        val first = fixture("italian-smoke.wav"); val second = fixture("youtube-smoke.wav")
        val audio = PcmAudio(resampleTo16k(first.samples, first.sampleRate) + FloatArray(16000) +
            resampleTo16k(second.samples, second.sampleRate), 16000)
        val file = File(context.cacheDir, "speaker-two-voices.opus").also { OpusEncoder.encode(audio, it) }
        SessionStore(context.filesDir).save(Session(uri = Uri.fromFile(file).toString(), name = file.name, durationMs = audio.durationMs))
        return file to first.durationMs + 1000
    }
    private fun startTranscription(scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { ready = it.findViewById<Button>(R.id.transcribe_button).isEnabled }
            if (!ready) SystemClock.sleep(50)
        }
        assertTrue(ready)
        scenario.onActivity { it.findViewById<Button>(R.id.transcribe_button).performClick() }
    }
    @Test fun transcribingLabelsEachVoice() {
        DiarizationModels.inFiles(context.filesDir).install { }
        val (file, boundary) = twoVoiceRecording()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            startTranscription(scenario)
            val began = SystemClock.uptimeMillis()
            val sessions = SessionStore(context.filesDir)
            while (sessions.load().document == null && SystemClock.uptimeMillis() - began < 300000) SystemClock.sleep(100)
            val path = sessions.load().document
            assertNotNull("Transcription must save a document", path)
            val next = CassiniDocument.read(File(path!!).readBytes())
            val words = next.selected(null)!!.transcript!!.words
            assertEquals("ok", next.state)
            assertEquals(1, next.variants.size)
            val before = words.filter { it.endMs < boundary - 1000 }.map { it.speaker }.toSet()
            val after = words.filter { it.startMs > boundary + 1000 }.map { it.speaker }.toSet()
            assertTrue(before.isNotEmpty() && after.isNotEmpty())
            assertTrue("The two voices must not share a label", before.intersect(after).isEmpty())
            val processing = next.manifest!!.getJSONObject("provenance").getJSONObject("speechToText").getJSONObject(next.defaultId!!)
            assertEquals("Parakeet TDT", processing.getString("engine"))
            assertTrue(processing.has("x-speakerDiarization"))
            scenario.onActivity {
                val text = it.findViewById<TextView>(R.id.transcript_text).text.toString()
                assertTrue(text.contains(it.getString(R.string.speaker_label, 1)))
                assertTrue(text.contains(it.getString(R.string.speaker_label, 2)))
            }
            println("Transcription with speakers: ${SystemClock.uptimeMillis() - began} ms")
        } finally { scenario.close(); file.delete() }
    }
    @Test fun cancellingDuringSpeakerIdentificationSavesNothing() {
        DiarizationModels.inFiles(context.filesDir).install { }
        val (file, _) = twoVoiceRecording()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            startTranscription(scenario)
            val deadline = SystemClock.uptimeMillis() + 300000
            var identifying = false
            while (!identifying && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity {
                    val progress = it.findViewById<ProgressBar>(R.id.operation_progress)
                    val status = it.findViewById<TextView>(R.id.operation_status).text.toString()
                    identifying = status.startsWith(it.getString(R.string.identifying_speakers_progress, 0).substringBefore(':')) &&
                        !progress.isIndeterminate && progress.progress in 1..99
                }
                if (!identifying) SystemClock.sleep(10)
            }
            assertTrue("Cancel during actual native work", identifying)
            scenario.onActivity { it.findViewById<Button>(R.id.cancel_operation).performClick() }
            assertTrue("Cancelled call must release its native models", NativeInference.lease.tryAcquire(180, TimeUnit.SECONDS))
            NativeInference.lease.release()
            instrumentation.waitForIdleSync()
            assertNull(SessionStore(context.filesDir).load().document)
        } finally { scenario.close(); file.delete() }
    }
}
