package org.cassini.android

import android.net.Uri
import android.text.Spanned
import android.text.style.ClickableSpan
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InterfaceTest {
    @Test fun playbackFollowsOffscreenWordsAndLeavesPausedReadingAlone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = SessionStore(context.filesDir)
        val originalSession = store.load()
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
            fail(description)
        }
        try {
            awaitCondition("Audio should become ready") { it.findViewById<Button>(R.id.play_button).isEnabled }
            scenario.onActivity { activity ->
                assertEquals("Restored paused playback must not scroll", 0, activity.findViewById<ScrollView>(R.id.content_scroll).scrollY)
                activity.findViewById<Button>(R.id.play_button).performClick()
            }
            awaitCondition("Playback should bring the final word into view") { activity ->
                val text = activity.findViewById<TextView>(R.id.transcript_text)
                val layout = text.layout
                val bounds = android.graphics.Rect(0, text.totalPaddingTop + layout.getLineTop(layout.lineCount - 1),
                    text.width, text.totalPaddingTop + layout.getLineBottom(layout.lineCount - 1))
                val scroll = activity.findViewById<ScrollView>(R.id.content_scroll)
                scroll.offsetDescendantRectToMyCoords(text, bounds)
                scroll.scrollY > 0 && bounds.top >= scroll.scrollY && bounds.bottom <= scroll.scrollY + scroll.height
            }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.play_button).performClick()
            }
            android.os.SystemClock.sleep(350) // Let the smooth scroll finish before reading manually.
            scenario.onActivity { it.findViewById<ScrollView>(R.id.content_scroll).scrollTo(0, 0) }
            android.os.SystemClock.sleep(400)
            scenario.onActivity { activity ->
                assertEquals("Paused playback must leave manual scrolling alone", 0, activity.findViewById<ScrollView>(R.id.content_scroll).scrollY)
                val scroll = activity.findViewById<ScrollView>(R.id.content_scroll)
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                val text = activity.findViewById<TextView>(R.id.transcript_text)
                (text.text as Spanned).getSpans(0, 1, ClickableSpan::class.java).single().onClick(text)
            }
            awaitCondition("Seeking backward should follow the first word") { activity ->
                val text = activity.findViewById<TextView>(R.id.transcript_text)
                val scroll = activity.findViewById<ScrollView>(R.id.content_scroll)
                val bounds = android.graphics.Rect(0, text.totalPaddingTop, text.width,
                    text.totalPaddingTop + text.layout.getLineBottom(0))
                scroll.offsetDescendantRectToMyCoords(text, bounds)
                bounds.top >= scroll.scrollY && bounds.bottom <= scroll.scrollY + scroll.height
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
        val audio = File(context.cacheDir, "interface-test.wav")
        instrumentation.context.assets.open("italian-smoke.wav").use { input ->
            audio.outputStream().use { output -> input.copyTo(output) }
        }
        val transcript = Transcript(listOf(Word("spk_1", 0, 800, "Ciao"), Word("spk_1", 1500, 2500, "mondo.")), "it")
        store.save(Session(Uri.fromFile(audio).toString(), "Italian sample.wav", transcript, 15840, 500, 3500))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { AppLanguage.set(it, "en") }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("Settings", activity.findViewById<TextView>(R.id.settings_button).text.toString())
                assertEquals("Ciao mondo.", activity.findViewById<TextView>(R.id.transcript_text).text.toString())
                assertEquals("Italian sample.wav", activity.findViewById<TextView>(R.id.recording_name).text.toString())
                AppLanguage.set(activity, "it")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("Impostazioni", activity.findViewById<TextView>(R.id.settings_button).text.toString())
                assertEquals("Trascrivi di nuovo", activity.findViewById<TextView>(R.id.transcribe_button).text.toString())
                assertEquals("Esporta", activity.findViewById<TextView>(R.id.export_button).text.toString())
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
