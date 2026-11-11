package io.codemyriad.cassini.acceptance

import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.codemyriad.cassini.MainActivity
import io.codemyriad.cassini.test.CiSmoke
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@CiSmoke
class ScreenshotFrameTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun aHardwareWindowWaitsForItsFrameCommit() {
        withRoot { root ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                awaitDrawnFrames(listOf(root), "hardware fixture", FRAME_MS)
            } else {
                expectUnsupported { awaitDrawnFrames(listOf(root), "old API fixture", FRAME_MS) }
            }
        }
    }

    @Test fun aRootWithoutHardwareRenderingIsRejected() {
        withRoot { window ->
            lateinit var software: View
            instrumentation.runOnMainSync { software = View(window.context) }
            expectUnsupported { awaitDrawnFrames(listOf(software), "software fixture", FRAME_MS) }
        }
    }

    @Test fun aWindowThatCannotDrawFailsTheFrameWait() {
        withRoot { root ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                expectUnsupported { awaitDrawnFrames(listOf(root), "old API fixture", FRAME_MS) }
            } else {
                val blockDrawing = ViewTreeObserver.OnPreDrawListener { false }
                instrumentation.runOnMainSync { root.viewTreeObserver.addOnPreDrawListener(blockDrawing) }
                try {
                    val failure = assertThrows(AssertionError::class.java) {
                        awaitDrawnFrames(listOf(root), "blocked draw", MISSING_FRAME_MS)
                    }
                    assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("did not commit a frame"))
                } finally {
                    instrumentation.runOnMainSync {
                        root.viewTreeObserver.removeOnPreDrawListener(blockDrawing)
                        root.invalidate()
                    }
                }
            }
        }
    }

    private fun expectUnsupported(action: () -> Unit) {
        val failure = assertThrows(AssertionError::class.java, action)
        val requirement = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) "API 29" else "hardware-accelerated"
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(requirement))
    }

    private fun withRoot(action: (View) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var root: View
            scenario.onActivity { root = it.window.decorView }
            action(root)
        }
    }

    private companion object {
        const val FRAME_MS = 10_000L
        const val MISSING_FRAME_MS = 500L
    }
}
