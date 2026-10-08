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
    @get:org.junit.Rule val preserveLibrary = PreserveLibraryRule()
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
    @Test fun opusEncoderCapabilityHasClearOutcome() {
        val available = OpusEncoder.available()
        android.util.Log.i("CassiniCompatibility", "API ${android.os.Build.VERSION.SDK_INT}: Opus encoder available=$available")
        if (available) {
            platformOpusEncodingKeepsDurationAndSpeechClock()
        } else {
            val failure = assertThrows(UserFacingException::class.java) {
                OpusEncoder.encode(PcmAudio(FloatArray(1600), 16000))
            }
            assertEquals(Failure.OPUS_ENCODER, failure.failure)
        }
    }
    @Test fun threeMinuteOpusTrimsPaddingToTheExactDuration() {
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
        assertEquals(180 * Limits.ASR_RATE, decoded.samples.size)
    }

    @Test fun transcriptionCreatesASealedPortableDocument() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val model = ModelStore(File(context.filesDir, "parakeet-v3"))
        org.junit.Assume.assumeTrue("Install the INT8 model for the end-to-end transcription check", model.ready())
        finishScreens()
        val sessions = SessionStore(context.filesDir); val originalSession = sessions.load()
        val wav = File(context.cacheDir, "portable-asr-source.wav")
        instrumentation.context.assets.open("youtube-smoke.wav").use { input -> wav.outputStream().use { input.copyTo(it) } }
        sessions.save(Session(uri = Uri.fromFile(wav).toString(), name = "Italian ASR.wav"))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.findViewById<Button>(R.id.transcribe_button).performClick() }
            val deadline = android.os.SystemClock.uptimeMillis() + 120000
            var success = false
            // While it runs: a moving progress bar, the pace with the time left, and words before the document exists.
            var progressSeen = 0; var paceSeen = ""; var interimWords = false; var draftSeen = false
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                if (sessions.load().document != null) { success = true; break }
                scenario.onActivity {
                    val status = it.findViewById<TextView>(R.id.operation_status).text.toString()
                    if (status.contains("×") && status.contains("%")) paceSeen = status
                    val bar = it.findViewById<android.widget.ProgressBar>(R.id.operation_progress)
                    if (bar.isShown && !bar.isIndeterminate && bar.progress in 1..99) progressSeen = bar.progress
                    if (it.findViewById<TextView>(R.id.transcript_text).text.isNotBlank()) {
                        interimWords = true
                        if (it.findViewById<TextView>(R.id.transcript_draft).isShown) draftSeen = true
                    }
                }
                android.os.SystemClock.sleep(100)
            }
            assertTrue("ASR should create a portable document automatically", success)
            assertTrue("Transcription should show measured progress before it completes", progressSeen in 1..99)
            assertTrue("Transcription should show its pace and the time left", paceSeen.isNotEmpty())
            assertTrue("Words should appear while transcription is still running", interimWords)
            assertTrue("Partial results must be labelled as a draft", draftSeen)
            val saved = sessions.load()
            val doc = CassiniDocument.read(File(saved.document!!).readBytes())
            assertEquals("ok", doc.state)
            assertTrue(doc.selected(null)!!.transcript!!.words.size > 60)
            val step = doc.manifest!!.getJSONObject("provenance").getJSONObject("speechToText").getJSONObject(doc.defaultId!!)
            assertTrue(step.getString("model").contains("INT8"))
            assertTrue(step.getString("version").contains(model.revision))
            assertTrue(step.getLong("x-inferenceMs") > 0)
            assertTrue(saved.processingMs >= saved.inferenceMs)
            scenario.onActivity {
                val status = it.findViewById<TextView>(R.id.operation_status).text.toString()
                assertTrue("Completion shows the realtime multiplier: $status", status.contains("×"))
                assertEquals(android.view.View.GONE, it.findViewById<TextView>(R.id.transcript_draft).visibility)
            }
            assertEquals(30000L, doc.manifest.getJSONObject("audio").getLong("durationMs"))
            val playable = android.os.SystemClock.uptimeMillis() + 8000
            var enabled = false; var status = ""
            while (!enabled && android.os.SystemClock.uptimeMillis() < playable) {
                scenario.onActivity { enabled = it.findViewById<Button>(R.id.play_button).isEnabled; status = it.findViewById<TextView>(R.id.operation_status).text.toString() }
                android.os.SystemClock.sleep(100)
            }
            assertTrue("The new document must be playable after transcription: $status", enabled)
            File(context.filesDir, "portable-asr-test.opus").writeBytes(File(saved.document!!).readBytes())
        } finally { scenario.close(); finishScreens(); sessions.save(originalSession) }
    }

    @Test fun viewerKeepsTheSessionItReplaces() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        finishScreens()
        val sessions = SessionStore(context.filesDir); val originalSession = sessions.load()
        // Before the notes library, the current recording was referenced by session.json alone.
        val legacy = Session(uri = "file:///legacy-${System.nanoTime()}.input", name = "Legacy.wav", positionMs = 8500,
            transcript = Transcript(listOf(Word("spk_1", 0, 100, "Prima.")), "it"))
        sessions.save(legacy)
        fun open(file: File) = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(file)))
        fun await(scenario: ActivityScenario<MainActivity>, description: String, condition: (MainActivity) -> Boolean) {
            val deadline = android.os.SystemClock.uptimeMillis() + 8000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                var ready = false; scenario.onActivity { ready = condition(it) }
                if (ready) return
                android.os.SystemClock.sleep(50)
            }
            fail(description)
        }
        val document = File(context.cacheDir, "replacing-document.opus")
        try {
            // A file that cannot be opened leaves an empty viewer, which has nothing to save over that session.
            open(File(context.cacheDir, "missing-document.opus")).use { scenario ->
                await(scenario, "A missing file must report that it could not be opened") {
                    it.findViewById<TextView>(R.id.operation_status).text.toString() == it.getString(R.string.error_open)
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                assertEquals(legacy.uri, sessions.load().uri); assertEquals(8500, sessions.load().positionMs)
            }
            // A file that opens replaces the session, once that has reached the catalogue with its state.
            // A second session, so that nothing the first viewer did can account for it.
            val replaced = legacy.copy(uri = "file:///legacy-${System.nanoTime()}.input", positionMs = 4200)
            sessions.save(replaced)
            val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0, 0, -128, -69, 0, 0, 0, 0, 0)
            val tags = CassiniDocument.tagsPacket(emptyList())
            val audio = OggOpus.mux(OggOpus.Stream(head, tags, listOf(byteArrayOf(-8, -1, -2)), 960, true), tags)
            document.writeBytes(CassiniDocument.create(audio, Transcript(listOf(Word("spk_1", 0, 10, "Dopo.")), "it"), "Replacing document", JSONObject()))
            open(document).use { scenario ->
                await(scenario, "The document must open") { it.findViewById<TextView>(R.id.transcript_text).text.contains("Dopo.") }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                assertNotEquals(replaced.uri, sessions.load().uri)
                val kept = LibraryStore(context.filesDir).load().single { it.session.uri == replaced.uri }
                assertEquals("Prima.", kept.text); assertEquals(4200, kept.session.positionMs)
            }
        } finally { finishScreens(); sessions.save(originalSession); document.delete() }
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
            fun documents() = File(context.filesDir, "documents").listFiles().orEmpty().map { it.name }.toSet()
            fun notes() = LibraryStore(context.filesDir).load().count { it.session.uri == saved.uri }
            fun shows(activity: MainActivity, word: String, clock: String) = activity.findViewById<TextView>(R.id.transcript_text).text.contains(word) &&
                activity.findViewById<Button>(R.id.export_button).isEnabled && activity.findViewById<TextView>(R.id.playback_position).text.startsWith(clock)
            fun seek(position: Float) = scenario.onActivity {
                assertTrue(it.findViewById<android.widget.SeekBar>(R.id.playback_seek).performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    android.os.Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, position) }))
            }
            // The same URI with other content: ActivityScenario stops tracking an activity whose intent changes.
            fun deliver(bytes: ByteArray) {
                source.writeBytes(bytes)
                scenario.onActivity { instrumentation.callActivityOnNewIntent(it, Intent(it.intent)) }
            }
            // Another viewer can replace session.json while this one waits to be recreated.
            fun recreateAfterAnotherViewer(other: Session = Session(uri = "file:///elsewhere.wav", name = "Elsewhere.wav")) {
                val elsewhere = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                    if (stage == Stage.PRE_ON_CREATE && activity is MainActivity) sessions.save(other)
                }
                ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(elsewhere)
                try { scenario.recreate() } finally { ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(elsewhere) }
            }
            val original = source.readBytes()
            val imported = documents()
            assertEquals(1, notes())
            await("Audio must become ready") { it.findViewById<Button>(R.id.play_button).isEnabled }
            seek(8500f)
            await("Seeking must move playback") { shows(it, "Benvenuti.", "00:08") }
            recreateAfterAnotherViewer()
            await("The recreated screen must return to its own document and position") { shows(it, "Benvenuti.", "00:08") }
            assertEquals("Recreation must not import the intent again", imported, documents())
            assertEquals(1, notes())
            deliver(CassiniDocument.create(audio.readBytes(), Transcript(listOf(Word("spk_a", 100, 600, "Secondo.")), "it"), "Second document", JSONObject()))
            await("A new view intent must open its document") { shows(it, "Secondo.", "00:00") }
            val both = documents()
            assertEquals(imported.size + 1, both.size)
            deliver(original)
            await("A file opened before must return to its note and position") { shows(it, "Benvenuti.", "00:08") }
            assertEquals("Opening the same file again must reuse its owned copy", both, documents())
            assertEquals("Opening the same file again must return to its note", 1, notes())
            seek(3500f)
            await("Seeking must move playback") { shows(it, "Benvenuti.", "00:03") }
            deliver(original)
            await("Opening the file already on screen must leave playback alone") { shows(it, "Benvenuti.", "00:03") }
            // Retranscription moves the note to a new revision. A recreated viewer stays on it rather than importing its intent again.
            val revision = File(context.filesDir, "documents/portable-ui-revision.opus")
            revision.writeBytes(CassiniDocument.create(original, Transcript(listOf(Word("spk_a", 100, 600, "Revisione.")), "it"), "Ignored", JSONObject(), CassiniDocument.read(original)))
            scenario.onActivity {
                val field = MainActivity::class.java.getDeclaredField("session").apply { isAccessible = true }
                field.set(it, (field.get(it) as Session).copy(uri = Uri.fromFile(revision).toString(), document = revision.absolutePath,
                    selectedVariant = CassiniDocument.read(revision.readBytes()).defaultId))
            }
            // The other viewer shows the same file as a different note, at its start.
            recreateAfterAnotherViewer(Session(uri = Uri.fromFile(revision).toString(), document = revision.absolutePath, name = "Other note", libraryId = "other", screen = "other"))
            await("A recreated viewer must keep the revision it moved to, as its own note") { shows(it, "Revisione.", "00:03") }
            assertEquals(Uri.fromFile(revision).toString(), LibraryStore(context.filesDir).load().single { it.id == saved.libraryId }.session.uri)
            assertEquals(both + revision.name, documents())
            assertTrue(CassiniDocument.read(exported.readBytes()).manifest!!.getJSONObject("x-test").getBoolean("preserve"))
        } finally {
            scenario.close(); finishScreens(); context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags(language); sessions.save(originalSession)
        }
    }
}
