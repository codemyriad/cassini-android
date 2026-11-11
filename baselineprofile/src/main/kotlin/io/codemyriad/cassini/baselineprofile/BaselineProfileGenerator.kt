package io.codemyriad.cassini.baselineprofile

import android.app.UiAutomation
import android.view.accessibility.AccessibilityNodeInfo
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Collects the Baseline Profile of the app: the code that runs at startup and in the common journeys. Android
 * compiles the profiled code ahead of time at install, so it does not run in the interpreter the first times
 * (docs/development.md, "Baseline Profile"). Before the profile, closing a note took 69-98 ms on a Pixel 8 because
 * the Library's composition code ran interpreted; fully compiled, it took 43 ms.
 *
 * The device needs notes in the Library and at least one note with a transcript. The models are not needed: the
 * journeys do not transcribe. The test does not record, so it changes no notes.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(packageName = PACKAGE) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.desc(RECORD)), TIMEOUT_MS)
        scrollLibrary(device)
        repeat(OPENINGS) {
            openNoteWithSpeech(device)
            scrollTranscript(device)
            playBriefly(device)
            device.pressBack()
            device.wait(Until.hasObject(By.desc(RECORD)), TIMEOUT_MS)
        }
        openAndLeave(device, By.desc(SETTINGS))
        openAndLeave(device, By.desc(TRASH))
    }

    private fun scrollLibrary(device: UiDevice) {
        val list = device.findObject(By.scrollable(true)) ?: return
        list.setGestureMargin(device.displayHeight / GESTURE_MARGIN_PARTS)
        list.fling(Direction.DOWN)
        device.waitForIdle()
        list.fling(Direction.UP)
        device.waitForIdle()
    }

    /** Opens the first Library row with a transcript and speech; its description reads "…, transcribed, …". */
    private fun openNoteWithSpeech(device: UiDevice) {
        val row = device.findObjects(By.descContains(TRANSCRIBED))
            .firstOrNull { it.contentDescription?.contains(NO_SPEECH) == false }
            ?: error("No transcribed note with speech in the Library")
        row.click()
        device.wait(Until.hasObject(By.desc(PLAY)), TIMEOUT_MS)
    }

    private fun scrollTranscript(device: UiDevice) {
        val list = device.findObject(By.scrollable(true)) ?: return
        list.setGestureMargin(device.displayHeight / GESTURE_MARGIN_PARTS)
        list.scroll(Direction.DOWN, SCROLL_PERCENT)
        list.scroll(Direction.UP, SCROLL_PERCENT)
    }

    private fun playBriefly(device: UiDevice) {
        val play = checkNotNull(device.findObject(By.desc(PLAY))) { "The note has no Play control" }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        awaitPlaybackProgress(automation, PACKAGE, TIMEOUT_MS, play::click)
        device.findObject(By.desc(PAUSE))?.click()
    }

    private fun openAndLeave(device: UiDevice, selector: BySelector) {
        val target: UiObject2 = device.findObject(selector) ?: return
        target.click()
        device.waitForIdle()
        device.pressBack()
        device.wait(Until.hasObject(By.desc(RECORD)), TIMEOUT_MS)
    }

    private companion object {
        const val PACKAGE = "io.codemyriad.cassini.talk"
        // The English strings of the content descriptions; the device language must be English.
        const val RECORD = "Record a new note"
        const val SETTINGS = "Settings"
        const val TRASH = "Trash"
        const val PLAY = "Play"
        const val PAUSE = "Pause"
        const val TRANSCRIBED = ", transcribed"
        const val NO_SPEECH = "no speech"
        const val OPENINGS = 3
        const val TIMEOUT_MS = 10_000L
        const val SCROLL_PERCENT = 0.8f
        const val GESTURE_MARGIN_PARTS = 5
    }
}

/** Runs the player until two seconds of audio have advanced, or until it reaches the end of a shorter note. */
@Suppress("DEPRECATION") // AccessibilityEvent.recycle is a no-op on API 33 and newer.
internal fun awaitPlaybackProgress(automation: UiAutomation, packageName: String, timeoutMs: Long, play: () -> Unit) {
    val start = checkNotNull(playbackPosition(automation.rootInActiveWindow)) { "The player has no Position range" }
    val from = if (start.current < start.end) start.current else 0f
    val target = minOf(from + PROFILE_AUDIO_MS, start.end)
    var moved = false
    val event = automation.executeAndWaitForEvent(
        { play() },
        { update ->
            if (update.packageName?.toString() != packageName) {
                false
            } else {
                val position = playbackPosition(automation.rootInActiveWindow)
                if (position != null && position.current != start.current) moved = true
                moved && position != null && position.current >= target
            }
        },
        timeoutMs,
    )
    event.recycle()
}

private data class PlaybackPosition(val current: Float, val end: Float)

@Suppress("DEPRECATION") // AccessibilityNodeInfo.recycle is needed on API 28–32.
private fun playbackPosition(node: AccessibilityNodeInfo?): PlaybackPosition? {
    node ?: return null
    return try {
        if (node.contentDescription?.toString() == "Position") {
            node.rangeInfo?.let { PlaybackPosition(it.current, it.max) }
        } else {
            (0 until node.childCount).firstNotNullOfOrNull { playbackPosition(node.getChild(it)) }
        }
    } finally {
        node.recycle()
    }
}

private const val PROFILE_AUDIO_MS = 2_000f
