package org.cassini.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LiveRecordingTest {
    @get:org.junit.Rule val preserveLibrary = PreserveLibraryRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun grantMicrophone() {
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
            .use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
    }
    private fun models() = ModelStore(File(context.filesDir, "parakeet-v3"), false).also {
        org.junit.Assume.assumeTrue("Install INT8 for live transcription checks", it.ready())
    }

    @Test fun pcmPauseResumeKeepsAudioWhenTheConsumerStops() {
        grantMicrophone()
        val file = File(context.cacheDir, "live-capture-test.wav")
        val audio = LiveAudioBuffer()
        val capture = PcmMicrophoneRecording(file, audio, { fail("Unexpected recording limit") }, { throw AssertionError(it) })
        try {
            SystemClock.sleep(1100)
            capture.togglePause()
            SystemClock.sleep(150)
            val pausedAt = capture.elapsedMs
            SystemClock.sleep(1000)
            assertEquals("Paused time must not enter either clock", pausedAt, capture.elapsedMs)
            capture.togglePause()
            SystemClock.sleep(1100)
            audio.cancel() // A failed/cancelled consumer must never take the saved audio with it.
            SystemClock.sleep(300)
            val saved = capture.stop()
            assertEquals(saved, capture.stop())
            val decoded = AudioDecoder.decode(context, Uri.fromFile(saved))
            assertEquals(16000, decoded.sampleRate)
            assertEquals(capture.elapsedMs, decoded.durationMs)
            assertTrue("Clock excludes the one-second pause: ${decoded.durationMs}", decoded.durationMs in 2100..2900)
            assertTrue(decoded.samples.size > audio.size)
        } finally { capture.release(); file.delete() }
    }

    @Test fun missingSelectedModelStillRecordsAndSaves() {
        val model = ModelStore(File(context.filesDir, "parakeet-v3-fp32"), true)
        org.junit.Assume.assumeFalse("Requires the FP32 model to be absent", model.ready())
        grantMicrophone()
        val sessions = SessionStore(context.filesDir)
        val original = sessions.load()
        sessions.save(original.copy(modelChoice = "fp32", fp32 = true))
        RecordingPreferences.setLive(context, true)
        val scenario = ActivityScenario.launch<RecordingActivity>(Intent(context, RecordingActivity::class.java).putExtra(RecordingActivity.AUTO_TRANSCRIBE, false))
        try {
            SystemClock.sleep(1200)
            scenario.onActivity {
                assertTrue(it.findViewById<Button>(R.id.capture_done).isEnabled)
                assertEquals(android.view.View.VISIBLE, it.findViewById<TextView>(R.id.capture_live_status).visibility)
                assertEquals(it.getString(R.string.live_recording_unavailable), it.findViewById<TextView>(R.id.capture_live_status).text.toString())
                it.findViewById<Button>(R.id.capture_done).performClick()
            }
            instrumentation.waitForIdleSync()
            val saved = sessions.load()
            assertNotNull(saved.libraryId)
            assertNull(saved.document)
            assertTrue(saved.name.endsWith(".m4a"))
            assertTrue(AudioDecoder.decode(context, Uri.parse(saved.uri)).durationMs >= 900)
        } finally { scenario.close(); sessions.save(original) }
    }

    @Test fun realDecoderShowsDraftBeforeCaptureEndsAndSealsTimedAudio() {
        val model = models()
        val file = File(context.cacheDir, "live-fixture.wav")
        instrumentation.context.assets.open("italian-smoke.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
        val fixture = AudioDecoder.decode(context, Uri.fromFile(file))
        val converted = resampleTo16k(fixture.samples, fixture.sampleRate)
        val samples = ShortArray(converted.size) { (converted[it] * 32767).toInt().coerceIn(-32768, 32767).toShort() }
        assertTrue("Need at least one complete preview chunk", samples.size > 80000)
        val audio = LiveAudioBuffer()
        audio.append(samples.copyOfRange(0, 80000))
        val draft = CountDownLatch(1)
        val preview = AtomicReference<List<Word>>()
        val result = AtomicReference<Transcript>()
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                Parakeet.Decoder(model).use { decoder ->
                    result.set(LiveTranscription.transcribe(audio, { pcm, padding -> decoder.decode(pcm, tailPad = padding) }) {
                        if (preview.get() == null) { preview.set(it.words); draft.countDown() }
                    })
                }
            } catch (error: Throwable) { failure.set(error); draft.countDown() }
        }
        worker.start()
        var portableFile: File? = null
        try {
            assertTrue("First draft must arrive before recording is finished", draft.await(120, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError(it) }
            assertNull(result.get())
            assertFalse(audio.finished)
            assertTrue("Speech must produce draft words", preview.get().isNotEmpty())
            audio.append(samples.copyOfRange(80000, samples.size)); audio.finish()
            worker.join(120000)
            assertFalse("Recognition must finish", worker.isAlive)
            failure.get()?.let { throw AssertionError(it) }
            val transcript = result.get()
            val text = transcript.words.joinToString(" ") { it.text }
            android.util.Log.i("CassiniLiveTest", "Live FLEURS: $text")
            assertTrue("Full fixture must produce more than a token: $text", transcript.words.size >= 10)
            assertTrue("Recognized: $text", text.contains("settentrionale", ignoreCase = true))
            assertTrue(transcript.words.all { it.startMs >= 0 && it.endMs <= audio.snapshot().durationMs })
            val (saved, portable) = DocumentStore(context).create(Uri.fromFile(file), audio.snapshot(), transcript, "Live fixture.wav",
                org.json.JSONObject().put("backend", "sherpa-onnx").put("x-segmentation", LiveTranscription.PROVENANCE), null)
            portableFile = saved
            assertEquals("ok", portable.state)
            assertEquals(transcript.words, portable.selected(portable.defaultId)?.transcript?.words)
            val reopened = AudioDecoder.decode(context, Uri.fromFile(saved))
            assertTrue(kotlin.math.abs(reopened.durationMs - audio.snapshot().durationMs) <= 20)
        } finally { audio.cancel(); worker.interrupt(); worker.join(5000); portableFile?.delete(); file.delete() }
    }

    @Test fun optedInRecordingFinishesTheSameLibraryNote() {
        models(); grantMicrophone()
        val sessions = SessionStore(context.filesDir)
        val original = sessions.load()
        sessions.save(original.copy(modelChoice = "int8", fp32 = false))
        RecordingPreferences.setLive(context, true)
        val scenario = ActivityScenario.launch(RecordingActivity::class.java)
        try {
            SystemClock.sleep(6500)
            scenario.onActivity {
                assertEquals(android.view.View.VISIBLE, it.findViewById<TextView>(R.id.capture_live_status).visibility)
                it.findViewById<Button>(R.id.capture_done).performClick()
            }
            val saveDeadline = SystemClock.uptimeMillis() + 5000
            while (sessions.load().libraryId == original.libraryId && SystemClock.uptimeMillis() < saveDeadline) SystemClock.sleep(10)
            val raw = sessions.load()
            assertNotNull("Audio must be durable while recognition finishes", raw.libraryId)
            assertNull(raw.document)
            assertTrue(raw.name.endsWith(".wav"))
            assertTrue(AudioDecoder.decode(context, Uri.parse(raw.uri)).durationMs >= 6000)
            val deadline = SystemClock.uptimeMillis() + 120000
            while (sessions.load().document == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            val complete = sessions.load()
            assertNotNull("Live recording must create a portable document", complete.document)
            assertEquals(raw.libraryId, complete.libraryId)
            assertEquals(1, LibraryStore(context.filesDir).load().count { it.id == raw.libraryId })
            val portable = CassiniDocument.read(File(complete.document!!).readBytes())
            assertEquals("ok", portable.state)
        } finally { scenario.close(); sessions.save(original) }
    }

    @Test fun leavingWhileDoneClosesTheFileSavesRawAudioWithoutRestartingInference() {
        models(); grantMicrophone()
        val sessions = SessionStore(context.filesDir)
        val original = sessions.load()
        sessions.save(original.copy(modelChoice = "int8", fp32 = false))
        RecordingPreferences.setLive(context, true)
        val scenario = ActivityScenario.launch(RecordingActivity::class.java)
        try {
            SystemClock.sleep(1200)
            scenario.onActivity { it.findViewById<Button>(R.id.capture_done).performClick() }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            val saveDeadline = SystemClock.uptimeMillis() + 5000
            while (sessions.load().libraryId == original.libraryId && SystemClock.uptimeMillis() < saveDeadline) SystemClock.sleep(10)
            val saved = sessions.load()
            assertNotNull(saved.libraryId)
            assertTrue(saved.name.endsWith(".wav"))
            assertTrue(AudioDecoder.decode(context, Uri.parse(saved.uri)).durationMs >= 900)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(2000)
            assertEquals(saved.libraryId, sessions.load().libraryId)
            assertNull("Returning after interruption must leave retry under user control", sessions.load().document)
        } finally { scenario.close(); sessions.save(original) }
    }

    @Test fun leavingLiveRecordingRetainsAudioAndCancelsRecognition() {
        models(); grantMicrophone()
        val sessions = SessionStore(context.filesDir)
        val original = sessions.load()
        sessions.save(original.copy(modelChoice = "int8", fp32 = false))
        RecordingPreferences.setLive(context, true)
        val scenario = ActivityScenario.launch(RecordingActivity::class.java)
        try {
            SystemClock.sleep(1200)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            val saveDeadline = SystemClock.uptimeMillis() + 5000
            while (sessions.load().libraryId == original.libraryId && SystemClock.uptimeMillis() < saveDeadline) SystemClock.sleep(10)
            val saved = sessions.load()
            assertNotNull(saved.libraryId)
            assertNull(saved.document)
            assertTrue(saved.name.endsWith(".wav"))
            assertTrue(AudioDecoder.decode(context, Uri.parse(saved.uri)).durationMs >= 900)
            SystemClock.sleep(2000)
            assertNull("Cancelled drafts must never replace a library note", sessions.load().document)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()
            assertEquals(saved.libraryId, sessions.load().libraryId)
            assertNull(sessions.load().document)
        } finally { scenario.close(); sessions.save(original) }
    }
}
