package org.cassini.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LibraryTest {
    @get:org.junit.Rule val preserveLibrary = PreserveLibraryRule()
    private fun finishScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.distinct()
                .filter { it is MainActivity || it is RecordingActivity || it is LibraryActivity || it is SettingsActivity }.forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun librarySearchOpensTheMatchingDocumentAndKeepsQueryOnReturn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        finishScreens()
        val sessions = SessionStore(context.filesDir)
        val originalSession = sessions.load()
        val backups = listOf("library.json", "library-migrated").associateWith { File(context.filesDir, it).takeIf { f -> f.exists() }?.readBytes() }
        val source = File(context.cacheDir, "library-viewer.opus")
        val scenario: ActivityScenario<LibraryActivity>
        try {
            File(context.filesDir, "library.json").delete()
            File(context.filesDir, "library-migrated").writeText("1")
            sessions.save(Session())
            val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0, 0, -128, -69, 0, 0, 0, 0, 0)
            val tags = CassiniDocument.tagsPacket(emptyList())
            val audio = OggOpus.mux(OggOpus.Stream(head, tags, listOf(byteArrayOf(-8, -1, -2)), 960, true), tags)
            val words = Transcript(listOf(Word("spk_1", 0, 10, "Ritrovare.")), "it")
            source.writeBytes(CassiniDocument.create(audio, words, "Library document", JSONObject()))
            val store = LibraryStore(context.filesDir)
            val note = store.save(Session(uri = Uri.fromFile(source).toString(), document = source.absolutePath,
                name = "Library document", transcript = words, selectedVariant = CassiniDocument.read(source.readBytes()).defaultId))
            store.save(Session(uri = "file:///other.m4a", name = "Other note"))
            scenario = ActivityScenario.launch(LibraryActivity::class.java)
            try {
                fun await(description: String, condition: () -> Boolean) {
                    val deadline = SystemClock.uptimeMillis() + 8000
                    while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(50) }
                    fail(description)
                }
                fun texts(view: android.view.View): List<String> = when (view) {
                    is TextView -> listOf(view.text.toString())
                    is android.view.ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
                    else -> emptyList()
                }
                fun cards(activity: LibraryActivity) = activity.findViewById<android.widget.LinearLayout>(R.id.library_cards).let { container ->
                    // Startup recovery catalogues the device's own captures into this temporary library; ignore them.
                    (0 until container.childCount).map { container.getChildAt(it) }.filter { it.isClickable && !texts(it).any { t -> t.startsWith(context.getString(R.string.recording_recovered)) } }
                }
                await("Library should display both notes") {
                    var ready = false; scenario.onActivity { ready = cards(it).size == 2 }; ready
                }
                scenario.onActivity { it.findViewById<EditText>(R.id.library_search).setText("ritrovare") }
                // Search is debounced and matched off the main thread.
                await("Search should narrow the library to the matching note") {
                    var ready = false; scenario.onActivity { ready = cards(it).size == 1 }; ready
                }
                scenario.onActivity { cards(it).single().performClick() }
                await("Search result should open authoritative timed words") {
                    var ready = false
                    instrumentation.runOnMainSync {
                        val viewer = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
                        ready = viewer?.findViewById<TextView>(R.id.transcript_text)?.text?.contains("Ritrovare.") == true
                    }
                    ready
                }
                instrumentation.runOnMainSync {
                    val viewer = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
                    assertEquals("ritrovare", viewer.findViewById<EditText>(R.id.search_input).text.toString())
                    assertEquals(note.libraryId, sessions.load().libraryId)
                    viewer.findViewById<Button>(R.id.notes_button).performClick()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertEquals("ritrovare", it.findViewById<EditText>(R.id.library_search).text.toString())
                    assertEquals(1, cards(it).size)
                }
            } finally { scenario.close() }
        } finally {
            finishScreens(); sessions.save(originalSession)
            backups.forEach { (name, bytes) -> File(context.filesDir, name).let { if (bytes == null) it.delete() else it.writeBytes(bytes) } }
            source.delete()
        }
    }
    @Test fun cancelledImportReturnsToTheLibraryInsteadOfTheLastNote() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        finishScreens()
        val sessions = SessionStore(context.filesDir)
        val originalSession = sessions.load()
        sessions.save(Session(uri = "file:///previous.wav", name = "Previous note.wav"))
        val picker = instrumentation.addMonitor(android.content.IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*")
        }, android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.REQUEST_IMPORT, true))
        try {
            val deadline = SystemClock.uptimeMillis() + 8000
            var library = false
            // The notes resume before the finishing viewer is destroyed.
            while ((!library || scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED) && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    library = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is LibraryActivity }
                }
                SystemClock.sleep(50)
            }
            assertEquals(1, picker.hits)
            assertTrue("Cancelling the picker should return to the notes", library)
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        } finally {
            instrumentation.removeMonitor(picker); scenario.close(); finishScreens(); sessions.save(originalSession)
        }
    }

    @Test fun savedAudioUpdatesOneCardAndCorruptIndexIsNeverOverwritten() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "library-store-test").apply { deleteRecursively(); mkdirs() }
        try {
            val store = LibraryStore(directory)
            val first = store.save(Session(uri = "file:///first.m4a", name = "First", durationMs = 2000))
            store.save(Session(uri = "file:///second.m4a", name = "Second"))
            val words = Transcript(listOf(Word("spk_1", 0, 100, "Ricordare.")), "it")
            val updated = store.save(first.copy(uri = "file:///first.opus", document = "/first.opus", transcript = words,
                selectedVariant = "first", positionMs = 1000))
            val notes = store.load()
            assertEquals(2, notes.size)
            val note = notes.single { it.id == updated.libraryId }
            assertTrue(note.matches("ricordare")); assertEquals(1000, note.session.positionMs)
            assertNull(note.session.transcript)
            val file = File(directory, "library.json"); file.writeText("damaged")
            try { store.save(updated); fail("Must refuse to overwrite an unreadable index") } catch (_: org.json.JSONException) {}
            assertEquals("damaged", file.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun migrationRecoversOldDocumentsAndKeepsSelectedVariantOfCurrentNote() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "library-migration-test").apply { deleteRecursively(); mkdirs() }
        try {
            val documents = File(directory, "documents").apply { mkdirs() }
            val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0, 0, -128, -69, 0, 0, 0, 0, 0)
            val tags = CassiniDocument.tagsPacket(emptyList())
            val audio = OggOpus.mux(OggOpus.Stream(head, tags, listOf(byteArrayOf(-8, -1, -2)), 960, true), tags)
            val words = Transcript(listOf(Word("spk_1", 0, 10, "Ciao.")), "it")
            val first = CassiniDocument.create(audio, words, "First meeting", JSONObject())
            val old = File(documents, "old.opus").apply { writeBytes(first); setLastModified(100) }
            val appended = CassiniDocument.create(first, words.copy(words = listOf(Word("spk_1", 0, 10, "Nuovo."))), "Ignored", JSONObject(), CassiniDocument.read(first))
            File(documents, "new.opus").apply { writeBytes(appended); setLastModified(200) }
            File(documents, "other.opus").writeBytes(CassiniDocument.create(audio, words, "Other meeting", JSONObject()))
            val store = LibraryStore(directory)
            // A session saved before the launcher migration must not suppress recovery.
            val current = store.save(Session(uri = Uri.fromFile(old).toString(), name = "First meeting", document = old.absolutePath,
                selectedVariant = CassiniDocument.read(first).defaultId, positionMs = 5))
            val createdAt = store.load().single().createdAt
            store.migrate(current)
            val notes = store.load()
            assertEquals(2, notes.size)
            val selected = notes.single { it.id == current.libraryId }
            assertEquals("Ciao.", selected.text)
            assertEquals(old.absolutePath, selected.session.document)
            assertEquals(5, selected.session.positionMs)
            assertEquals(createdAt, selected.createdAt)
            store.migrate(current)
            assertEquals(notes, store.load())
        } finally { directory.deleteRecursively() }
    }

    @Test fun migrationKeepsNotesSavedBeforeIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "library-kept-test").apply { deleteRecursively(); mkdirs() }
        try {
            val documents = File(directory, "documents").apply { mkdirs() }
            val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0, 0, -128, -69, 0, 0, 0, 0, 0)
            val tags = CassiniDocument.tagsPacket(emptyList())
            val audio = OggOpus.mux(OggOpus.Stream(head, tags, listOf(byteArrayOf(-8, -1, -2)), 960, true), tags)
            val words = Transcript(listOf(Word("spk_1", 0, 10, "Ciao.")), "it")
            val first = CassiniDocument.create(audio, words, "First meeting", JSONObject())
            val old = File(documents, "old.opus").apply { writeBytes(first); setLastModified(100) }
            val newer = File(documents, "new.opus").apply {
                writeBytes(CassiniDocument.create(first, words.copy(words = listOf(Word("spk_1", 0, 10, "Nuovo."))), "Ignored", JSONObject(), CassiniDocument.read(first)))
                setLastModified(200)
            }
            val store = LibraryStore(directory)
            // A viewer saved this note with its state before the library first opened. Another note is current by then.
            // It came from session.json, which does not carry a document's words.
            val saved = store.save(Session(uri = Uri.fromFile(old).toString(), name = "First meeting", document = old.absolutePath,
                selectedVariant = CassiniDocument.read(first).defaultId, positionMs = 5))
            val before = store.load().single()
            assertEquals("", before.text)
            store.migrate(Session(uri = "file:///current.m4a", name = "Current"))
            val notes = store.load()
            assertEquals("Only the missing search text is filled in", before.copy(text = "Ciao."), notes.single { it.id == saved.libraryId })
            assertEquals("Each note needs its own ID", notes.size, notes.map { it.id }.toSet().size)
            assertTrue("A later revision of a meeting that already has a note stays off the library", notes.none { it.session.document == newer.absolutePath })
            assertEquals(2, notes.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun microphonePauseResumeSavesPlayableAudioAndReopensFromLibrary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sessions = SessionStore(context.filesDir)
        val originalSession = sessions.load()
        // An upgrade can retain this retired opt-in; it must never change the capture path.
        val recordingPreferences = context.getSharedPreferences("recording", android.content.Context.MODE_PRIVATE)
        val hadLivePreference = recordingPreferences.contains("transcribe_while_recording")
        val oldLivePreference = recordingPreferences.getBoolean("transcribe_while_recording", false)
        assertTrue(recordingPreferences.edit().putBoolean("transcribe_while_recording", true).commit())
        val files = listOf("library.json", "library-migrated")
        val backups = files.associateWith { name -> File(context.filesDir, name).takeIf { it.exists() }?.readBytes() }
        val originalFiles = File(context.filesDir, "documents").listFiles().orEmpty().map { it.name }.toSet()
        finishScreens()
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
            .use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        val scenario = ActivityScenario.launch<RecordingActivity>(Intent(context, RecordingActivity::class.java).putExtra(RecordingActivity.AUTO_TRANSCRIBE, false))
        try {
            SystemClock.sleep(1100)
            scenario.onActivity {
                assertTrue(it.findViewById<Button>(R.id.capture_done).isEnabled)
                it.findViewById<EditText>(R.id.capture_title).setText("Microphone test")
                it.findViewById<Button>(R.id.capture_pause).performClick()
            }
            SystemClock.sleep(150)
            var paused = ""
            scenario.onActivity { paused = it.findViewById<TextView>(R.id.capture_timer).text.toString() }
            SystemClock.sleep(1200)
            scenario.onActivity {
                assertEquals(paused, it.findViewById<TextView>(R.id.capture_timer).text.toString())
                it.findViewById<Button>(R.id.capture_pause).performClick()
            }
            SystemClock.sleep(1200)
            scenario.onActivity { it.findViewById<Button>(R.id.capture_done).performClick() }
            instrumentation.waitForIdleSync()
            val recorded = sessions.load()
            assertEquals("Microphone test." + MicrophoneRecording.extension(), recorded.name)
            assertNotNull(recorded.libraryId)
            assertNull(recorded.document)
            val audio = AudioDecoder.decode(context, Uri.parse(recorded.uri))
            assertTrue("Paused time must be excluded: ${audio.durationMs}", audio.durationMs in 1800..3200)
            assertEquals(16000, audio.sampleRate)
            assertEquals(1, LibraryStore(context.filesDir).load().count { it.id == recorded.libraryId })
            finishScreens()
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.NOTE_ID, recorded.libraryId)).use { reopened ->
                SystemClock.sleep(500)
                reopened.onActivity {
                    assertEquals(recorded.name, it.findViewById<TextView>(R.id.recording_name).text.toString())
                    assertTrue(it.findViewById<Button>(R.id.play_button).isEnabled)
                }
            }
        } finally {
            scenario.close(); finishScreens(); sessions.save(originalSession)
            val editor = recordingPreferences.edit()
            if (hadLivePreference) editor.putBoolean("transcribe_while_recording", oldLivePreference)
            else editor.remove("transcribe_while_recording")
            assertTrue(editor.commit())
            backups.forEach { (name, bytes) -> File(context.filesDir, name).let { if (bytes == null) it.delete() else it.writeBytes(bytes) } }
            File(context.filesDir, "documents").listFiles().orEmpty().filter { it.name !in originalFiles }.forEach { it.delete() }
        }
    }

    @Test fun leavingRecordingScreenKeepsCapturingInTheService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sessions = SessionStore(context.filesDir)
        val original = sessions.load()
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
            .use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        val scenario = ActivityScenario.launch<RecordingActivity>(Intent(context, RecordingActivity::class.java).putExtra(RecordingActivity.AUTO_TRANSCRIBE, false))
        try {
            SystemClock.sleep(1200)
            val before = sessions.load()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            SystemClock.sleep(3000)
            assertEquals("Hiding the screen must not stop or save the recording", before.libraryId, sessions.load().libraryId)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { it.findViewById<Button>(R.id.capture_done).performClick() }
            instrumentation.waitForIdleSync()
            val saved = sessions.load()
            assertNotNull(saved.libraryId)
            assertNull(saved.document)
            assertTrue(AudioDecoder.decode(context, Uri.parse(saved.uri)).durationMs >= 3900)
            assertEquals(1, LibraryStore(context.filesDir).load().count { it.id == saved.libraryId })
        } finally { scenario.close(); finishScreens(); sessions.save(original) }
    }

    @Test fun doneAutomaticallyTranscribesAndSealsTheSameLibraryNote() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val model = ModelStore(File(context.filesDir, "parakeet-v3"))
        org.junit.Assume.assumeTrue("Install INT8 for the microphone-to-document check", model.ready())
        val sessions = SessionStore(context.filesDir)
        val original = sessions.load()
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
            .use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        val scenario = ActivityScenario.launch(RecordingActivity::class.java)
        try {
            SystemClock.sleep(1500)
            scenario.onActivity { it.findViewById<Button>(R.id.capture_done).performClick() }
            val id = sessions.load().libraryId
            assertNotNull(id)
            val deadline = SystemClock.uptimeMillis() + 90000
            while (sessions.load().document == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            val saved = sessions.load()
            assertEquals(id, saved.libraryId)
            assertNotNull("Done should transcribe automatically", saved.document)
            assertEquals("ok", CassiniDocument.read(File(saved.document!!).readBytes()).state)
            assertEquals(1, LibraryStore(context.filesDir).load().count { it.id == id })
            val playable = SystemClock.uptimeMillis() + 8000
            var enabled = false; var status = ""
            while (!enabled && SystemClock.uptimeMillis() < playable) {
                instrumentation.runOnMainSync {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()?.let {
                        enabled = it.findViewById<Button>(R.id.play_button).isEnabled; status = it.findViewById<TextView>(R.id.operation_status).text.toString()
                    }
                }
                SystemClock.sleep(100)
            }
            assertTrue("The new document must be playable after transcription: $status", enabled)
        } finally { scenario.close(); finishScreens(); sessions.save(original) }
    }
}
