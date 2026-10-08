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
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import android.text.Spanned
import android.text.style.ClickableSpan
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
        // Once Parakeet is installed consent was given; optional models are retried without asking.
        val bundle = ModelBundle.inFiles(context.filesDir)
        org.junit.Assume.assumeFalse("Requires the speech model to be absent", bundle.consented())
        val missing = bundle.missingBytes()
        val file = File(context.cacheDir, "speaker-consent.wav")
        instrumentation.context.assets.open("italian-smoke.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
        SessionStore(context.filesDir).save(Session(uri = Uri.fromFile(file).toString(), name = file.name))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.findViewById<Button>(R.id.transcribe_button).performClick() }
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
            assertEquals(missing, bundle.missingBytes())
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
    /** A sealed two-speaker note made without the recognition models. */
    private fun twoSpeakerNote(): File {
        val wav = File(context.cacheDir, "speaker-rename.wav")
        instrumentation.context.assets.open("italian-smoke.wav").use { input -> wav.outputStream().use { input.copyTo(it) } }
        try {
            val transcript = Transcript(listOf(Word("spk_1", 1000, 1400, "Ciao."), Word("spk_2", 2000, 2400, "Salve.")), "it")
            val (file, doc) = DocumentStore(context).create(Uri.fromFile(wav), { AudioDecoder.decode(context, Uri.fromFile(wav)) }, transcript,
                "rename.wav", JSONObject().put("engine", "Parakeet TDT"), null,
                mapOf("spk_1" to context.getString(R.string.speaker_label, 1), "spk_2" to context.getString(R.string.speaker_label, 2)))
            SessionStore(context.filesDir).save(Session(uri = Uri.fromFile(file).toString(), name = "rename.opus", document = file.absolutePath,
                selectedVariant = doc.defaultId, durationMs = 15800))
            return file
        } finally { wav.delete() }
    }
    private fun waitFor(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        var done = false
        while (!done && SystemClock.uptimeMillis() < deadline) { scenario.onActivity { done = condition(it) }; if (!done) SystemClock.sleep(50) }
        assertTrue(done)
    }
    private fun tapLabel(scenario: ActivityScenario<MainActivity>, label: String) = scenario.onActivity {
        val view = it.findViewById<TextView>(R.id.transcript_text)
        val text = view.text as Spanned; val start = text.indexOf(label)
        text.getSpans(start, start + label.length, ClickableSpan::class.java).single().onClick(view)
    }
    @Test fun renamingSpeakerRewritesDocumentAndKeepsVariant() {
        val original = twoSpeakerNote()
        val before = CassiniDocument.read(original)
        val first = context.getString(R.string.speaker_label, 1)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var renamed: File? = null
        try {
            waitFor(scenario) { it.findViewById<TextView>(R.id.transcript_text).text.contains(first) }
            tapLabel(scenario, first)
            onView(withId(R.id.speaker_name_input)).inRoot(isDialog()).check(matches(withText("")))
            onView(withId(R.id.speaker_name_input)).inRoot(isDialog()).perform(replaceText("Anna"))
            // The draft survives recreation and the dialog comes back with it.
            scenario.recreate()
            onView(withId(R.id.speaker_name_input)).inRoot(isDialog()).check(matches(withText("Anna")))
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            val sessions = SessionStore(context.filesDir)
            waitFor(scenario) { sessions.load().document != original.absolutePath && it.findViewById<TextView>(R.id.transcript_text).text.contains("Anna") }
            renamed = File(sessions.load().document!!)
            val after = CassiniDocument.read(renamed)
            assertEquals("ok", after.state)
            assertEquals("Anna", after.speakerLabel("spk_1"))
            assertEquals(before.speakerLabel("spk_2"), after.speakerLabel("spk_2"))
            assertEquals(before.variants.map { it.id to it.body }, after.variants.map { it.id to it.body })
            assertEquals(before.manifest!!.getJSONObject("meeting").getString("id"), after.manifest!!.getJSONObject("meeting").getString("id"))
            assertFalse("The old file is retired once the note points at the new one", original.exists())
            assertEquals(Uri.fromFile(renamed).toString(), sessions.load().uri)
            assertTrue(LibraryStore(context.filesDir).load().any { it.session.document == renamed.absolutePath })
            scenario.onActivity { assertEquals(it.getString(R.string.speaker_renamed, "Anna"), it.findViewById<TextView>(R.id.operation_status).text.toString()) }
            // Naming again prefills the current name.
            tapLabel(scenario, "Anna")
            onView(withId(R.id.speaker_name_input)).inRoot(isDialog()).check(matches(withText("Anna")))
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
        } finally { scenario.close(); original.delete(); renamed?.delete() }
    }
}
