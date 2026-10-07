package org.cassini.android

import android.view.KeyEvent
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import org.hamcrest.Matchers.allOf

class SettingsTest {
    @get:org.junit.Rule val preserveLibrary = PreserveLibraryRule()
    @Test fun languageChoiceSurvivesReturningFromSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sessions = SessionStore(context.filesDir)
        val original = sessions.load()
        val originalLanguage = AppLanguage.selected(context)
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().flatMap { monitor.getActivitiesInStage(it) }.distinct()
                .filter { it is MainActivity || it is SettingsActivity }.forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
        val transcript = Transcript(listOf(Word("spk_1", 0, 500, "Ciao"), Word("spk_1", 600, 1000, "mondo.")), "it")
        sessions.save(Session(name = "Italian sample.wav", transcript = transcript, durationMs = 1000))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun awaitText(text: String) {
            onView(withText(text)).check(matches(isDisplayed()))
        }
        fun openPreference(text: String) {
            onView(allOf(withId(android.R.id.title), withText(text))).perform(click())
        }
        fun selectOption(text: String) {
            onView(withText(text)).inRoot(isDialog()).perform(click())
        }
        try {
            scenario.onActivity { AppLanguage.set(it, "en") }
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.findViewById<TextView>(R.id.settings_button).performClick() }
            awaitText("Transcription model")
            onView(withText("Transcribe while recording")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            onView(withText("Automatic · prefer full precision")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            openPreference("Interface language")
            selectOption("Italiano")
            awaitText("Impostazioni")
            awaitText("Italiano") // The selected value appears under the language row.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            // Applying app locales can replace the viewer after Back. Wait for the current
            // focused activity instead of retaining a decor view from the closing instance.
            val viewerDeadline = android.os.SystemClock.uptimeMillis() + 5000
            var returned = false
            while (!returned && android.os.SystemClock.uptimeMillis() < viewerDeadline) {
                instrumentation.runOnMainSync {
                    val viewer = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().firstOrNull { it.hasWindowFocus() && !it.isFinishing }
                    if (viewer != null && viewer.findViewById<TextView>(R.id.transcript_text).text.toString() == "Ciao mondo.") {
                        assertTrue(viewer.findViewById<TextView>(R.id.transcript_text).isShown)
                        assertTrue(viewer.findViewById<TextView>(R.id.model_status).text.toString().startsWith("INT8"))
                        assertEquals("Italian sample.wav", viewer.findViewById<TextView>(R.id.recording_name).text.toString())
                        returned = true
                    }
                }
                if (!returned) android.os.SystemClock.sleep(25)
            }
            assertTrue("The focused viewer must show the original note after settings", returned)
        } finally {
            // Finish any settings activity before restoring the user's session.
            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                Stage.values().flatMap { monitor.getActivitiesInStage(it) }.distinct()
                    .filterIsInstance<SettingsActivity>().forEach { it.finish() }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { AppLanguage.set(it, originalLanguage) }
            instrumentation.waitForIdleSync()
            scenario.close()
            sessions.save(original)
        }
    }
}
