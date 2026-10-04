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
    @Test fun modelAndLanguageChoicesSurviveReturningFromSettings() {
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
        sessions.save(Session(name = "Italian sample.wav", transcript = transcript, durationMs = 1000, modelChoice = "int8"))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun awaitText(text: String) {
            onView(withText(text)).check(matches(isDisplayed()))
        }
        fun openPreference(text: String) {
            onView(allOf(withId(android.R.id.title), withText(text))).perform(click())
        }
        fun selectOption(text: String) {
            // Restrict options to the dialog: Automatic also labels a row behind it.
            onView(withText(text)).inRoot(isDialog()).perform(click())
        }
        fun awaitModelChoice(choice: String) {
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (sessions.load().modelChoice != choice && android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(50)
            }
            assertEquals("The selected model must be saved", choice, sessions.load().modelChoice)
        }
        try {
            scenario.onActivity { AppLanguage.set(it, "en") }
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.findViewById<TextView>(R.id.settings_button).performClick() }
            openPreference("Transcription model")
            selectOption("Automatic · prefer full precision")
            awaitModelChoice("auto")
            openPreference("Transcription model")
            selectOption("FP32 · 2.37 GiB · full precision")
            awaitModelChoice("fp32")
            assertTrue(sessions.load().fp32)
            openPreference("Interface language")
            selectOption("Italiano")
            awaitText("Impostazioni")
            awaitText("Italiano") // The selected value appears under the language row.
            assertTrue("Changing language must retain the chosen model", sessions.load().fp32)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitText("Ciao mondo.")
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<TextView>(R.id.model_status).text.toString().startsWith("FP32"))
                assertEquals("Italian sample.wav", activity.findViewById<TextView>(R.id.recording_name).text.toString())
            }
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
