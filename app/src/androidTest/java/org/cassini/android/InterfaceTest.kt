package org.cassini.android

import android.net.Uri
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InterfaceTest {
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
