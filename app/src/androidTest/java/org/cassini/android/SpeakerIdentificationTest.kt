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
    private fun twoSpeakerNote(first: String = context.getString(R.string.speaker_label, 1)): File {
        val wav = File(context.cacheDir, "speaker-rename.wav")
        instrumentation.context.assets.open("italian-smoke.wav").use { input -> wav.outputStream().use { input.copyTo(it) } }
        try {
            val transcript = Transcript(listOf(Word("spk_1", 1000, 1400, "Ciao."), Word("spk_2", 2000, 2400, "Salve.")), "it")
            val (file, doc) = DocumentStore(context).create(Uri.fromFile(wav), { AudioDecoder.decode(context, Uri.fromFile(wav)) }, transcript,
                "rename.wav", JSONObject().put("engine", "Parakeet TDT"), null,
                mapOf("spk_1" to first, "spk_2" to context.getString(R.string.speaker_label, 2)))
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
        assertTrue("$label is shown", start >= 0)
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

    private val app get() = context.applicationContext as CassiniApplication
    private fun meeting(file: File) = CassiniDocument.read(file).let { it.manifest!!.getJSONObject("meeting").getString("id") to it.defaultId!! }
    private fun print() = Voiceprints.normalised(FloatArray(512) { kotlin.random.Random(7).nextFloat() - .5f + it % 3 })!!
    /** Saves [name] as a person and records how this note's first speaker matched them, as a transcription would. */
    private fun savedMatch(note: File, name: String, state: Match.State): Voice {
        File(context.filesDir, "voices.json").delete()
        val voice = app.voices.enrol(null, name, VoiceprintModel.model.sha256, print(), 12.0, 1)
        val (meeting, variant) = meeting(note)
        app.noteVoices.merge(meeting, variant, VoiceprintModel.model.sha256, mapOf("spk_1" to SpeakerPrint(print(), 12.0, Match(voice.id, .6f, state))))
        return voice
    }
    @Test fun suggestionTapAppliesName() {
        val original = twoSpeakerNote()
        val voice = savedMatch(original, "Marco", Match.State.SUGGESTED)
        val (meeting, variant) = meeting(original)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val suggestion = context.getString(R.string.speaker_suggestion, "Marco")
            waitFor(scenario) { it.findViewById<TextView>(R.id.transcript_text).text.contains(suggestion) }
            waitFor(scenario) { it.findViewById<Button>(R.id.more_button).isEnabled }
            tapLabel(scenario, suggestion)
            val sessions = SessionStore(context.filesDir)
            waitFor(scenario) { sessions.load().document != original.absolutePath }
            assertEquals("Marco", CassiniDocument.read(File(sessions.load().document!!)).speakerLabel("spk_1"))
            val match = app.noteVoices.load(meeting, variant).getValue("spk_1").match!!
            assertEquals(voice.id to Match.State.CONFIRMED, match.voiceId to match.state)
            assertEquals("Accepting teaches the saved voice", 2, app.voices.load().single().count)
            scenario.onActivity { assertFalse(it.findViewById<TextView>(R.id.transcript_text).text.contains(suggestion)) }
        } finally { scenario.close(); original.delete(); SessionStore(context.filesDir).load().document?.let { File(it).delete() } }
    }
    @Test fun notThisPersonRevertsLabel() {
        val original = twoSpeakerNote("Marco")
        val voice = savedMatch(original, "Marco", Match.State.AUTO)
        val (meeting, variant) = meeting(original)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val auto = context.getString(R.string.speaker_auto_mark)
            waitFor(scenario) { it.findViewById<TextView>(R.id.transcript_text).text.contains("Marco  · $auto") }
            tapLabel(scenario, "Marco")
            onView(withId(android.R.id.button3)).inRoot(isDialog()).perform(click())
            val sessions = SessionStore(context.filesDir)
            val first = context.getString(R.string.speaker_label, 1)
            waitFor(scenario) { sessions.load().document != original.absolutePath && it.findViewById<TextView>(R.id.transcript_text).text.contains(first) }
            assertEquals(first, CassiniDocument.read(File(sessions.load().document!!)).speakerLabel("spk_1"))
            assertEquals(Match.State.REJECTED, app.noteVoices.load(meeting, variant).getValue("spk_1").match!!.state)
            assertEquals("An automatic name never taught the voice", 1, app.voices.load().single { it.id == voice.id }.count)
        } finally { scenario.close(); original.delete(); SessionStore(context.filesDir).load().document?.let { File(it).delete() } }
    }
    private fun awaitDocument(previous: String?): String {
        val began = SystemClock.uptimeMillis()
        val sessions = SessionStore(context.filesDir)
        while ((sessions.load().document == null || sessions.load().document == previous) && SystemClock.uptimeMillis() - began < 300000) SystemClock.sleep(100)
        return requireNotNull(sessions.load().document) { "Transcription must save a document" }
    }
    @Test fun namedVoiceIsAutoAppliedOnSecondTranscription() {
        DiarizationModels.inFiles(context.filesDir).install { }
        org.junit.Assume.assumeTrue("Install the voice model", VoiceprintModel.inFiles(context.filesDir).ready())
        File(context.filesDir, "voices.json").delete()
        val (file, _) = twoVoiceRecording()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var made = emptyList<String>()
        try {
            startTranscription(scenario)
            val first = awaitDocument(null); made = made + first
            val label = context.getString(R.string.speaker_label, 1)
            waitFor(scenario) { it.findViewById<TextView>(R.id.transcript_text).text.contains(label) && it.findViewById<Button>(R.id.more_button).isEnabled }
            tapLabel(scenario, label)
            onView(withId(R.id.speaker_remember)).inRoot(isDialog()).check(matches(androidx.test.espresso.matcher.ViewMatchers.isChecked()))
            onView(withId(R.id.speaker_name_input)).inRoot(isDialog()).perform(replaceText("Anna"))
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            val named = awaitDocument(first); made = made + named
            val anna = app.voices.load().single()
            assertEquals("Anna", anna.name)
            val speaker = CassiniDocument.read(File(named)).let { doc ->
                val speakers = doc.manifest!!.getJSONArray("speakers")
                (0 until speakers.length()).map { speakers.getJSONObject(it).getString("id") }.firstOrNull { doc.speakerLabel(it) == "Anna" }
                    ?: throw AssertionError("No speaker is named Anna in $named (was $first): $speakers") }
            waitFor(scenario) { it.findViewById<Button>(R.id.more_button).isEnabled }
            onView(withId(R.id.more_button)).perform(click())
            onView(withText(R.string.transcribe_again)).inRoot(androidx.test.espresso.matcher.RootMatchers.isPlatformPopup()).perform(click())
            val again = awaitDocument(named); made = made + again
            val doc = CassiniDocument.read(File(again))
            assertEquals(2, doc.variants.size)
            val prints = app.noteVoices.load(doc.manifest!!.getJSONObject("meeting").getString("id"), doc.defaultId!!)
            val matched = prints.entries.singleOrNull { it.value.match?.voiceId == anna.id }
            assertNotNull("The saved voice is recognised again", matched)
            assertTrue(matched!!.value.match!!.state in setOf(Match.State.AUTO, Match.State.SUGGESTED))
            println("Second transcription matched $speaker as ${matched.key} ${matched.value.match}")
            waitFor(scenario) { it.findViewById<TextView>(R.id.transcript_text).text.contains("Anna") }
        } finally { scenario.close(); file.delete(); made.forEach { File(it).delete() } }
    }
    @Test fun cancelDuringVoiceprintsSavesNothing() {
        DiarizationModels.inFiles(context.filesDir).install { }
        org.junit.Assume.assumeTrue("Install the voice model", VoiceprintModel.inFiles(context.filesDir).ready())
        val voices = File(context.filesDir, "voices.json").takeIf { it.exists() }?.readBytes()
        val notes = File(context.filesDir, "voiceprints").list().orEmpty().toSet()
        val (file, _) = twoVoiceRecording()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            startTranscription(scenario)
            val deadline = SystemClock.uptimeMillis() + 300000
            var matching = false
            while (!matching && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { matching = it.findViewById<TextView>(R.id.operation_status).text.toString() == it.getString(R.string.matching_voices) }
                if (!matching) SystemClock.sleep(5)
            }
            assertTrue("Cancel while voiceprints are computed", matching)
            scenario.onActivity { it.findViewById<Button>(R.id.cancel_operation).performClick() }
            assertTrue(NativeInference.lease.tryAcquire(180, TimeUnit.SECONDS))
            NativeInference.lease.release()
            instrumentation.waitForIdleSync()
            assertNull(SessionStore(context.filesDir).load().document)
            assertEquals(notes, File(context.filesDir, "voiceprints").list().orEmpty().toSet())
            assertArrayEquals(voices, File(context.filesDir, "voices.json").takeIf { it.exists() }?.readBytes())
        } finally { scenario.close(); file.delete() }
    }
}
