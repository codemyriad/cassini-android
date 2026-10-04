package org.cassini.android

import android.net.Uri
import android.text.Spanned
import android.text.style.ClickableSpan
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.SeekBar
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InterfaceTest {
    @get:org.junit.Rule val preserveLibrary = PreserveLibraryRule()
    private fun finishExistingScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().flatMap { monitor.getActivitiesInStage(it) }.distinct()
                .filter { it is MainActivity || it is SettingsActivity }.forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun playbackFollowsOffscreenWordsAndLeavesPausedReadingAlone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = SessionStore(context.filesDir)
        val originalSession = store.load()
        finishExistingScreens()
        val audio = File(context.cacheDir, "follow-playback.wav")
        instrumentation.context.assets.open("italian-smoke.wav").use { input ->
            audio.outputStream().use { output -> input.copyTo(output) }
        }
        val words = listOf(Word("spk_1", 0, 1500, "Inizio.")) +
            (0 until 40).map { Word("spk_1", 2000L + it * 150, 2100L + it * 150, "Parola$it.") } +
            Word("spk_1", 10000, 15500, "Fine.")
        store.save(Session(Uri.fromFile(audio).toString(), "Follow sample.wav", Transcript(words, "it"),
            durationMs = 15840, positionMs = 11000))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun awaitCondition(description: String, condition: (MainActivity) -> Boolean) {
            val deadline = android.os.SystemClock.uptimeMillis() + 4000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                var satisfied = false
                scenario.onActivity { satisfied = condition(it) }
                if (satisfied) return
                android.os.SystemClock.sleep(50)
            }
            var state = ""
            scenario.onActivity { activity ->
                val scroll = activity.findViewById<ScrollView>(R.id.content_scroll)
                val text = activity.findViewById<TextView>(R.id.transcript_text)
                val spans = text.text as? Spanned
                val active = spans?.getSpans(0, spans.length, android.text.style.BackgroundColorSpan::class.java)
                    ?.map { spans.subSequence(spans.getSpanStart(it), spans.getSpanEnd(it)).toString() }
                val last = text.layout?.let { layout ->
                    android.graphics.Rect(0, text.totalPaddingTop + layout.getLineTop(layout.lineCount - 1),
                        text.width, text.totalPaddingTop + layout.getLineBottom(layout.lineCount - 1)).also {
                        scroll.offsetDescendantRectToMyCoords(text, it)
                    }
                }
                state = "position=${activity.findViewById<TextView>(R.id.playback_position).text}, " +
                    "play=${activity.findViewById<Button>(R.id.play_button).text}, " +
                    "scroll=${scroll.scrollY}/${scroll.height}, lines=${text.layout?.lineCount}, " +
                    "last=$last, content=${scroll.getChildAt(0).height}, " +
                    "active=$active, status=${activity.findViewById<TextView>(R.id.operation_status).text}"
            }
            fail("$description ($state)")
        }
        fun wordVisible(activity: MainActivity, first: Boolean): Boolean {
            val text = activity.findViewById<TextView>(R.id.transcript_text)
            val layout = text.layout ?: return false
            val line = if (first) 0 else layout.lineCount - 1
            val bounds = android.graphics.Rect(0, text.totalPaddingTop + layout.getLineTop(line),
                text.width, text.totalPaddingTop + layout.getLineBottom(line))
            val scroll = activity.findViewById<ScrollView>(R.id.content_scroll)
            scroll.offsetDescendantRectToMyCoords(text, bounds)
            return bounds.top >= scroll.scrollY && bounds.bottom <= scroll.scrollY + scroll.height
        }
        try {
            awaitCondition("Audio should become ready") { it.findViewById<Button>(R.id.play_button).isEnabled }
            scenario.onActivity { activity ->
                assertFalse("Opening a note must not focus the search field", activity.findViewById<android.widget.EditText>(R.id.search_input).hasFocus())
                assertEquals("Restored paused playback must not scroll", 0, activity.findViewById<ScrollView>(R.id.content_scroll).scrollY)
                activity.findViewById<Button>(R.id.play_button).performClick()
            }
            awaitCondition("Playback should bring the final word into view") { activity ->
                activity.findViewById<ScrollView>(R.id.content_scroll).scrollY > 0 && wordVisible(activity, first = false)
            }
            fun activeWord(text: Spanned) = text.getSpans(0, text.length, android.text.style.BackgroundColorSpan::class.java).single()
                .let { text.subSequence(text.getSpanStart(it), text.getSpanEnd(it)).toString() }
            var buffer: CharSequence? = null
            var firstWord: ClickableSpan? = null
            scenario.onActivity { activity ->
                val text = activity.findViewById<TextView>(R.id.transcript_text).text as Spanned
                assertEquals("Fine.", activeWord(text))
                buffer = text; firstWord = text.getSpans(0, 1, ClickableSpan::class.java).single()
                activity.findViewById<Button>(R.id.play_button).performClick()
            }
            android.os.SystemClock.sleep(350) // Let the smooth scroll finish before reading manually.
            scenario.onActivity { it.findViewById<ScrollView>(R.id.content_scroll).scrollTo(0, 0) }
            android.os.SystemClock.sleep(400)
            scenario.onActivity { activity ->
                assertEquals("Paused playback must leave manual scrolling alone", 0, activity.findViewById<ScrollView>(R.id.content_scroll).scrollY)
                val seek = activity.findViewById<SeekBar>(R.id.playback_seek)
                // A different position within the same word: Android ignores no-op progress actions.
                assertTrue(seek.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    android.os.Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 10500f) }))
            }
            awaitCondition("Seeking while paused should reveal the final word, even when it stays highlighted") {
                wordVisible(it, first = false)
            }
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.play), activity.findViewById<Button>(R.id.play_button).text.toString())
                // Keyboard activation leaves LinkMovementMethod's selection on the word.
                val text = activity.findViewById<TextView>(R.id.transcript_text).text as android.text.Spannable
                android.text.Selection.setSelection(text, text.length - "Fine.".length, text.length)
                activity.findViewById<Button>(R.id.back_button).performClick()
            }
            awaitCondition("Back 10 seconds while paused should reveal the first word") { wordVisible(it, first = true) }
            scenario.onActivity { activity ->
                val text = activity.findViewById<TextView>(R.id.transcript_text).text as Spanned
                assertEquals("Inizio.", activeWord(text))
                assertSame("Moving the highlight must not rebuild the transcript", buffer, text)
                assertSame(firstWord, text.getSpans(0, 1, ClickableSpan::class.java).single())
                assertEquals("A stale word selection must not look like a second active word", -1, android.text.Selection.getSelectionStart(text))
            }
            scenario.onActivity { it.findViewById<Button>(R.id.forward_button).performClick() }
            awaitCondition("Forward 10 seconds while paused should reveal the final word") { wordVisible(it, first = false) }
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.play), activity.findViewById<Button>(R.id.play_button).text.toString())
                val scroll = activity.findViewById<ScrollView>(R.id.content_scroll)
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                val text = activity.findViewById<TextView>(R.id.transcript_text)
                (text.text as Spanned).getSpans(0, 1, ClickableSpan::class.java).single().onClick(text)
            }
            awaitCondition("Seeking backward should follow the first word") { activity ->
                wordVisible(activity, first = true)
            }
        } finally {
            scenario.close()
            store.save(originalSession)
            audio.delete()
        }
    }

    @Test fun leavingBeforePlaybackStartsKeepsAudioPlayable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = SessionStore(context.filesDir)
        val originalSession = store.load()
        finishExistingScreens()
        val audio = File(context.cacheDir, "leave-before-playback.wav")
        instrumentation.context.assets.open("italian-smoke.wav").use { input -> audio.outputStream().use { output -> input.copyTo(output) } }
        store.save(Session(Uri.fromFile(audio).toString(), "Leave sample.wav", Transcript(listOf(Word("spk_1", 0, 1500, "Inizio.")), "it"), durationMs = 15840))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun awaitCondition(description: String, condition: (MainActivity) -> Boolean) {
            val deadline = android.os.SystemClock.uptimeMillis() + 4000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                var satisfied = false
                scenario.onActivity { satisfied = condition(it) }
                if (satisfied) return
                android.os.SystemClock.sleep(50)
            }
            fail(description)
        }
        try {
            awaitCondition("Audio should become ready") { it.findViewById<Button>(R.id.play_button).isEnabled }
            // The player is prepared but has never played. Pausing it in that state is a MediaPlayer error.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            android.os.SystemClock.sleep(500) // The player reports errors asynchronously.
            scenario.onActivity { activity ->
                val play = activity.findViewById<Button>(R.id.play_button)
                assertTrue("Returning to a note that was never played must leave playback available: " +
                    activity.findViewById<TextView>(R.id.operation_status).text, play.isEnabled)
                play.performClick()
            }
            awaitCondition("Playback should start after returning") {
                it.findViewById<Button>(R.id.play_button).text.toString() == it.getString(R.string.pause)
            }
            // A playing note is paused when the screen leaves, and can resume.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            awaitCondition("Leaving should pause playback and keep it available") {
                val play = it.findViewById<Button>(R.id.play_button)
                play.isEnabled && play.text.toString() == it.getString(R.string.play)
            }
            scenario.onActivity { it.findViewById<Button>(R.id.play_button).performClick() }
            awaitCondition("Playback should resume after it was paused by leaving") {
                it.findViewById<Button>(R.id.play_button).text.toString() == it.getString(R.string.pause)
            }
        } finally {
            scenario.close()
            store.save(originalSession)
            audio.delete()
        }
    }

    @Test fun languageSwitchRetainsItalianTranscriptRecordingAndPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = SessionStore(context.filesDir)
        val originalSession = store.load()
        val originalLanguage = AppLanguage.selected(context)
        finishExistingScreens()
        val audio = File(context.cacheDir, "interface-test.wav")
        instrumentation.context.assets.open("italian-smoke.wav").use { input ->
            audio.outputStream().use { output -> input.copyTo(output) }
        }
        val transcript = Transcript(listOf(Word("spk_1", 0, 800, "Ciao"), Word("spk_1", 1500, 2500, "mondo.")), "it")
        store.save(Session(Uri.fromFile(audio).toString(), "Italian sample.wav", transcript, 15840, 500, 3500))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun awaitSettingsLabel(label: String) {
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                var ready = false
                scenario.onActivity { ready = it.findViewById<TextView>(R.id.settings_button).text.toString() == label }
                if (ready) return
                android.os.SystemClock.sleep(50)
            }
            fail("Locale recreation should display $label")
        }
        try {
            scenario.onActivity { AppLanguage.set(it, "en") }
            instrumentation.waitForIdleSync()
            awaitSettingsLabel("Settings")
            scenario.onActivity { activity ->
                assertEquals("Settings", activity.findViewById<TextView>(R.id.settings_button).text.toString())
                assertEquals("Ciao mondo.", activity.findViewById<TextView>(R.id.transcript_text).text.toString())
                assertEquals("Italian sample.wav", activity.findViewById<TextView>(R.id.recording_name).text.toString())
                AppLanguage.set(activity, "it")
            }
            instrumentation.waitForIdleSync()
            awaitSettingsLabel("Impostazioni")
            scenario.onActivity { activity ->
                assertEquals("Impostazioni", activity.findViewById<TextView>(R.id.settings_button).text.toString())
                assertEquals("Trascrivi di nuovo", activity.findViewById<TextView>(R.id.transcribe_button).text.toString())
                assertEquals("Salva Cassini", activity.findViewById<TextView>(R.id.export_button).text.toString())
                assertEquals("Ciao mondo.", activity.findViewById<TextView>(R.id.transcript_text).text.toString())
                assertEquals("2 parole · 00:15 · INT8", activity.findViewById<TextView>(R.id.transcript_details).text.toString())
                assertTrue(activity.findViewById<TextView>(R.id.playback_position).text.toString().contains("00:03"))
                val search = activity.findViewById<android.widget.EditText>(R.id.search_input)
                search.setText("mondo")
                assertEquals("1 risultato", activity.findViewById<TextView>(R.id.search_matches).text.toString())
                activity.findViewById<android.widget.Button>(R.id.clear_search_button).performClick()
                assertEquals("", search.text.toString())
                AppLanguage.set(activity, "en")
            }
            instrumentation.waitForIdleSync()
            awaitSettingsLabel("Settings")
            scenario.onActivity { activity ->
                assertEquals("Settings", activity.findViewById<TextView>(R.id.settings_button).text.toString())
                assertEquals("Ciao mondo.", activity.findViewById<TextView>(R.id.transcript_text).text.toString())
                AppLanguage.set(activity, originalLanguage)
            }
        } finally {
            scenario.close()
            store.save(originalSession)
            audio.delete()
        }
    }
}
