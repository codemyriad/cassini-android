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
import org.hamcrest.Matchers.instanceOf
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import java.io.File

class SettingsTest {
    // Espresso's PreferenceMatchers call toString() on a missing title or summary; several preferences have only one.
    private fun withTitleText(text: String) = preferenceText("title", text) { it.title }
    private fun withSummaryText(text: String) = preferenceText("summary", text) { it.summary }
    private fun preferenceText(what: String, text: String, read: (android.preference.Preference) -> CharSequence?) =
        object : TypeSafeMatcher<android.preference.Preference>() {
            override fun describeTo(description: Description) { description.appendText("preference with $what \"$text\"") }
            override fun matchesSafely(item: android.preference.Preference) = read(item)?.toString() == text
        }
    private fun eventually(check: () -> Unit) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (true) {
            try { check(); return } catch (error: Throwable) { if (android.os.SystemClock.uptimeMillis() > deadline) throw error; android.os.SystemClock.sleep(50) }
        }
    }

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
            // Open Settings from the viewer directly: a popup menu may never take window focus on a slow emulator.
            scenario.onActivity { it.startActivity(android.content.Intent(it, SettingsActivity::class.java)) }
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

    @Test fun peopleCanBeRenamedAndForgotten() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val originalLanguage = AppLanguage.selected(context)
        val voices = VoiceStore(context.filesDir)
        val notes = NoteVoiceStore(context.filesDir)
        voices.clear()
        val current = VoiceprintModel.model.sha256
        val anna = voices.enrol(null, "Anna", current, FloatArray(4) { if (it == 0) 1f else 0f }, 84.0, 1L)
        voices.enrol(null, "Old Marco", "older-model", FloatArray(4) { if (it == 1) 1f else 0f }, 12.0, 1L)
        notes.merge("people_test_note", "v1", current, mapOf("spk_1" to SpeakerPrint(FloatArray(4) { 1f }, 30.0, Match(anna.id, 0.8f, Match.State.CONFIRMED))))
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        scenario.onActivity { AppLanguage.set(it, "en") }
        instrumentation.waitForIdleSync()
        fun preference(title: String) = onData(allOf(instanceOf(android.preference.Preference::class.java), withTitleText(title)))
        try {
            preference("Anna").check(matches(isDisplayed()))
            // Note counts are filled in once the background scan finishes.
            eventually { onData(allOf(instanceOf(android.preference.Preference::class.java), withSummaryText("Heard in 1 note · 01:24 of speech"))).check(matches(isDisplayed())) }
            onData(allOf(instanceOf(android.preference.Preference::class.java), withSummaryText(context.getString(R.string.people_needs_reenrol)))).check(matches(isDisplayed()))
            onData(allOf(instanceOf(android.preference.Preference::class.java), withSummaryText(context.getString(R.string.people_privacy)))).check(matches(isDisplayed()))

            preference("Anna").perform(click())
            onView(withId(R.id.person_name_input)).inRoot(isDialog()).perform(replaceText("Annalisa"))
            onView(withText("Rename")).inRoot(isDialog()).perform(click())
            preference("Annalisa").check(matches(isDisplayed()))
            assertEquals(listOf("Annalisa", "Old Marco"), voices.load().map { it.name })

            preference("Annalisa").perform(click())
            onView(withText("Forget")).inRoot(isDialog()).perform(click())
            onView(withText("Forget")).inRoot(isDialog()).perform(click()) // confirm
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (voices.load().size != 1 && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(25)
            assertEquals(listOf("Old Marco"), voices.load().map { it.name })
            assertNull(notes.load("people_test_note", "v1").getValue("spk_1").match)
            instrumentation.waitForIdleSync()
            onView(withText("Annalisa")).check(doesNotExist())

            preference("Forget all voices").perform(click())
            onView(withText("Forget")).inRoot(isDialog()).perform(click())
            while (voices.load().isNotEmpty() && android.os.SystemClock.uptimeMillis() < deadline + 5000) android.os.SystemClock.sleep(25)
            assertTrue(voices.load().isEmpty())
            assertFalse(File(context.filesDir, "voiceprints/people_test_note.json").exists())
            // The rebuild is posted after the background forget; wait for the empty state.
            eventually { preference(context.getString(R.string.people_empty)).check(matches(isDisplayed())) }
        } finally {
            scenario.onActivity { AppLanguage.set(it, originalLanguage) }
            instrumentation.waitForIdleSync()
            scenario.close()
        }
    }

    @Test fun forgetFinishingAfterRecreationRefreshesTheNewScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val originalLanguage = AppLanguage.selected(context)
        val voices = VoiceStore(context.filesDir)
        voices.clear()
        voices.enrol(null, "Bruna", VoiceprintModel.model.sha256, FloatArray(4) { if (it == 2) 1f else 0f }, 20.0, 1L)
        val scenario = ActivityScenario.launch(SettingsActivity::class.java)
        scenario.onActivity { AppLanguage.set(it, "en") }
        instrumentation.waitForIdleSync()
        fun preference(title: String) = onData(allOf(instanceOf(android.preference.Preference::class.java), withTitleText(title)))
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            preference("Bruna").perform(click())
            onView(withText("Forget")).inRoot(isDialog()).perform(click())
            // Hold the forget behind a blocked task until the screen has been recreated.
            SettingsActivity.SettingsFragment.background.execute { gate.await(10, java.util.concurrent.TimeUnit.SECONDS) }
            onView(withText("Forget")).inRoot(isDialog()).perform(click())
            scenario.recreate()
            preference("Bruna").check(matches(isDisplayed()))
            gate.countDown()
            eventually { preference(context.getString(R.string.people_empty)).check(matches(isDisplayed())) }
            assertTrue(voices.load().isEmpty())
        } finally {
            gate.countDown()
            scenario.onActivity { AppLanguage.set(it, originalLanguage) }
            instrumentation.waitForIdleSync()
            scenario.close()
            voices.clear()
        }
    }
}
