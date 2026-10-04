package org.cassini.android

import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test

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
        sessions.save(Session(name = "Italian sample.wav", transcript = transcript, durationMs = 1000))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun textVisible(text: String): Boolean = instrumentation.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByText(text)?.any { it.text?.toString() == text } == true
        fun awaitText(text: String) {
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                if (textVisible(text)) return
                android.os.SystemClock.sleep(50)
            }
            fail("Expected visible text: $text")
        }
        fun clickText(text: String) {
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                val nodes = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty()
                for (found in nodes.filter { it.text?.toString() == text }) {
                    var node: AccessibilityNodeInfo? = found
                    while (node != null && !node.isClickable) node = node.parent
                    if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                        instrumentation.waitForIdleSync()
                        return
                    }
                    // API 34's platform Preference list can omit clickable ancestors in its
                    // accessibility tree. Exercise the actual touch target in that case.
                    val bounds = android.graphics.Rect().also { found.getBoundsInScreen(it) }
                    if (!bounds.isEmpty) {
                        val now = android.os.SystemClock.uptimeMillis()
                        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                            val event = android.view.MotionEvent.obtain(now, now, action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                            instrumentation.uiAutomation.injectInputEvent(event, true)
                            event.recycle()
                        }
                        instrumentation.waitForIdleSync()
                        return
                    }
                }
                android.os.SystemClock.sleep(50)
            }
            fail("Expected clickable row: $text")
        }
        try {
            scenario.onActivity { AppLanguage.set(it, "en") }
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.findViewById<TextView>(R.id.settings_button).performClick() }
            clickText("Transcription model")
            // Automatic also labels a row behind the dialog. Wait for a unique option so
            // an older platform's stale accessibility window cannot select that row.
            awaitText("FP32 · 2.37 GiB · full precision")
            clickText("Automatic · prefer full precision")
            assertEquals("auto", sessions.load().modelChoice)
            clickText("Transcription model")
            clickText("FP32 · 2.37 GiB · full precision")
            assertTrue(sessions.load().fp32)
            assertEquals("fp32", sessions.load().modelChoice)
            clickText("Interface language")
            clickText("Italiano")
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
