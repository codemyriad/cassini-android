package io.codemyriad.cassini.flows

import android.app.AlertDialog
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import io.codemyriad.cassini.test.CiSmoke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext

/** Each transition starts after the observer's first failed query; success requires a later notification. */
@CiSmoke
class UiEventsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var text: TextView
    private lateinit var root: View

    @Before fun setUp() {
        rule.runOnUiThread {
            text = TextView(rule.activity).also { it.text = "Waiting" }
            rule.activity.setContentView(text)
            root = rule.activity.window.decorView
        }
    }

    @Test fun nativeTextChangesWakeTheObserver() = afterQuery(
        { text.text.toString() == READY },
        { text.text = READY },
    )

    @Test fun nativeDialogAppearanceWakesTheObserver() {
        val dialog = rule.runOnUiThread { AlertDialog.Builder(rule.activity).setMessage(READY).create() }
        try {
            afterQuery({ dialog.window?.decorView?.isAttachedToWindow == true }, { dialog.show() })
        } finally {
            rule.runOnUiThread { dialog.dismiss() }
        }
    }

    @Test fun nativeDialogRemovalWakesTheObserver() {
        val dialog = rule.runOnUiThread { AlertDialog.Builder(rule.activity).setMessage(READY).show() }
        try {
            afterQuery({ dialog.window?.decorView?.isAttachedToWindow == false }, { dialog.dismiss() })
        } finally {
            rule.runOnUiThread { dialog.dismiss() }
        }
    }

    @Test fun composeSemanticsChangesWakeTheObserver() {
        val state = mutableStateOf(false)
        rule.runOnUiThread {
            rule.activity.setContentView(
                ComposeView(rule.activity).apply {
                    setContent { androidx.compose.material3.Text(if (state.value) READY else "Waiting") }
                },
            )
        }
        var changed = false
        UiEvents.await(root, WAIT_MS) {
            val current = rule.onAllNodesWithText(READY).fetchSemanticsNodes().isNotEmpty()
            if (!changed) {
                changed = true
                rule.runOnUiThread { state.value = true }
            }
            current
        }
    }

    @Test fun aSnapshotWithoutAViewConsumerWakesTheObserver() {
        val state = mutableStateOf(false)
        var changed = false
        UiEvents.await(root, WAIT_MS) {
            val current = state.value
            if (!changed) {
                changed = true
                state.value = true
                Snapshot.sendApplyNotifications()
            }
            current
        }
    }

    @Test fun pendingComposeDispatchesAdvanceOnTheVirtualClock() {
        val dispatcher = StandardTestDispatcher(rule.mainClock.scheduler)
        var posted = false
        var delivered = false
        UiEvents.await(root, WAIT_MS, rule.mainClock) {
            val current = delivered
            if (!posted) {
                posted = true
                dispatcher.dispatch(EmptyCoroutineContext) { delivered = true }
            }
            current
        }
        assertTrue(delivered)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test(timeout = 5_000)
    fun recurringVirtualWorkCannotStarveTheTimeout() {
        val scope = CoroutineScope(StandardTestDispatcher(rule.mainClock.scheduler))
        var ticks = 0
        scope.launch {
            while (isActive) {
                ticks++
                delay(16)
            }
        }
        rule.mainClock.scheduler.runCurrent()
        try {
            assertThrows(ComposeTimeoutException::class.java) { UiEvents.await(root, 1_000, rule.mainClock) { false } }
            assertTrue(ticks > 1)
        } finally {
            scope.cancel()
        }
    }

    @Test fun anInvalidatedWindowMustDraw() = UiEvents.draw(root, WAIT_MS)

    @Test fun aBlockedDrawCannotAcknowledgeLayoutReadiness() {
        val attempted = CountDownLatch(1)
        val blocked = ViewTreeObserver.OnPreDrawListener {
            attempted.countDown()
            false
        }
        UiEvents.onMain(WAIT_MS) {
            root.viewTreeObserver.addOnPreDrawListener(blocked)
            root.invalidate()
        }
        try {
            assertTrue("The traversal reaches the draw gate", attempted.await(WAIT_MS, TimeUnit.MILLISECONDS))
            val failure = assertThrows(AssertionError::class.java) { UiEvents.draw(root, BLOCKED_MS) }
            assertTrue(failure.message.orEmpty().contains("did not draw"))
        } finally {
            UiEvents.onMain(WAIT_MS) {
                root.viewTreeObserver.removeOnPreDrawListener(blocked)
                root.invalidate()
            }
        }
    }

    private fun afterQuery(condition: () -> Boolean, action: () -> Unit) {
        var changed = false
        UiEvents.await(root, WAIT_MS) {
            val current = rule.runOnUiThread(condition)
            if (!changed) {
                changed = true
                rule.runOnUiThread(action)
            }
            current
        }
        assertTrue(changed)
    }

    private companion object {
        const val READY = "Ready"
        const val WAIT_MS = 20_000L
        const val BLOCKED_MS = 2_000L
    }
}
