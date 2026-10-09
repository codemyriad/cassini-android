package org.cassini.android

import android.net.Uri
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProcessingResumeTest {
    @get:org.junit.Rule val preserveLibrary = PreserveLibraryRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @Test fun stopReopenAndResumeKeepsNamesPositionAndContinuousAudio() {
        assumeTrue(ModelStore(File(context.filesDir, "parakeet-v3")).ready())
        assumeTrue(DiarizationModels.inFiles(context.filesDir).ready())
        (context.applicationContext as CassiniApplication).voices.clear() // The rule restores the user’s exact saved profiles.
        val fixture = File(context.cacheDir, "resume-fixture.wav")
        instrumentation.context.assets.open("youtube-smoke.wav").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val original = AudioDecoder.decode(context, Uri.fromFile(fixture)).samples
        val samples = FloatArray(90 * 16000) { original[it % original.size] }
        val file = File(context.cacheDir, "resume-long.wav")
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size * 2)
        }.array()
        file.outputStream().buffered().use { out -> out.write(header); samples.forEach {
            val sample = (it * 32767).toInt().coerceIn(-32768, 32767); out.write(sample and 255); out.write((sample shr 8) and 255)
        } }
        val sessions = SessionStore(context.filesDir)
        val uri = Uri.fromFile(file).toString()
        sessions.save(Session(uri = uri, name = file.name, durationMs = 90000))
        var scenario = ActivityScenario.launch(MainActivity::class.java)
        fun waitFor(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 240000
            while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            assertTrue(message, predicate())
        }
        try {
            waitFor("Transcribe is ready") { var ready = false; scenario.onActivity { ready = it.findViewById<Button>(R.id.transcribe_button).isEnabled }; ready }
            scenario.onActivity { it.findViewById<Button>(R.id.transcribe_button).performClick() }
            var named = false
            waitFor("First speaker appears during processing") {
                scenario.onActivity { activity ->
                    if (!named) {
                        val view = activity.findViewById<TextView>(R.id.transcript_text)
                        val text = view.text as? Spanned
                        val label = activity.getString(R.string.speaker_label, 1)
                        val at = text?.toString()?.indexOf(label) ?: -1
                        if (at >= 0) text!!.getSpans(at, at + label.length, ClickableSpan::class.java).firstOrNull()?.let { named = true; it.onClick(view) }
                    }
                }; named
            }
            onView(withId(R.id.speaker_name_input)).inRoot(isDialog()).perform(replaceText("Resume Voice"))
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            scenario.onActivity { it.findViewById<Button>(R.id.cancel_operation).performClick() }
            waitFor("Stop keeps a durable partial result") { sessions.load().processingPaused }
            val checkpoint = ProcessingCheckpoint(context.filesDir, uri)
            val saved = checkpoint.load()!!
            val done = saved.getLong("doneMs")
            assertTrue(done in 28000 until 90000)
            assertTrue(checkpoint.loadNames().first.values.contains("Resume Voice"))
            val partial = sessions.load().transcript!!.words
            assertTrue(partial.isNotEmpty())
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            waitFor("Reopened partial names and resume control") { var ready = false; scenario.onActivity {
                ready = it.findViewById<Button>(R.id.transcribe_button).isEnabled && it.findViewById<Button>(R.id.transcribe_button).text == it.getString(R.string.resume_processing) &&
                    it.findViewById<TextView>(R.id.transcript_text).text.contains("Resume Voice")
            }; ready }
            scenario.onActivity { it.findViewById<Button>(R.id.transcribe_button).performClick() }
            waitFor("Resume saves the complete artifact") { sessions.load().document != null }
            instrumentation.waitForIdleSync()
            val document = CassiniDocument.read(File(sessions.load().document!!))
            assertEquals("ok", document.state)
            assertEquals(90000L, document.manifest!!.getJSONObject("audio").getLong("durationMs"))
            assertTrue(document.manifest!!.getJSONArray("speakers").toString().contains("Resume Voice"))
            val complete = document.selected(null)!!.transcript!!.words
            assertEquals(partial.filter { it.endMs < done - 2000 }, complete.filter { it.endMs < done - 2000 })
            val voices = (context.applicationContext as CassiniApplication).voices.load()
            assertEquals(1, voices.count { it.name == "Resume Voice" })
            assertEquals(1, voices.single { it.name == "Resume Voice" }.count)
            assertNull(checkpoint.load())
            assertFalse(sessions.load().processingPaused)
            assertEquals(done, document.manifest!!.getJSONObject("provenance").getJSONObject("speechToText").getJSONObject(document.defaultId!!).getLong("x-resumedFromMs"))
            scenario.close()
            sessions.save(Session(uri = uri, name = "Second recognition.wav", durationMs = 90000))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            waitFor("Second transcription ready") { var ready = false; scenario.onActivity { ready = it.findViewById<Button>(R.id.transcribe_button).isEnabled }; ready }
            scenario.onActivity { it.findViewById<Button>(R.id.transcribe_button).performClick() }
            waitFor("Remembered voice appears in the first live results") { var recognised = false; scenario.onActivity {
                recognised = it.findViewById<TextView>(R.id.transcript_text).text.contains("Resume Voice") &&
                    it.findViewById<Button>(R.id.cancel_operation).visibility == View.VISIBLE
            }; recognised }
            scenario.onActivity { it.findViewById<Button>(R.id.cancel_operation).performClick() }
            waitFor("Second recognition stopped") { sessions.load().processingPaused }
            assertEquals(1, (context.applicationContext as CassiniApplication).voices.load().single { it.name == "Resume Voice" }.count)

        } finally { scenario.close(); file.delete(); fixture.delete() }
    }
}
