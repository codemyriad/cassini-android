package org.cassini.android.flows

import android.util.Log
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.ImageContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import java.util.Locale

/**
 * The Accessibility Test Framework rule of features.md section 14: every ERROR and WARNING fails the test, except a
 * touch target below 48 dp on a Library row suffix ("· Resume", "· Try again"), whose 48 dp comes from extended hit
 * testing (ui.md section 11, item 13).
 * [inline] holds the texts of those elements. [idleBars] holds the descriptions of images whose low contrast
 * ui.md section 11 allows: the Recording waveform, whose estimated foreground is the colour of its older bars
 * (rec.barIdle, decorative; the newest bars, in rec.barFresh, have 7.73:1). Each finding is logged with its check,
 * element and message.
 */
internal fun accessibilityValidator(inline: Set<String>, idleBars: Set<String> = emptySet()): AccessibilityValidator =
    AccessibilityValidator()
        .setRunChecksFromRootView(true)
        .setThrowExceptionFor(AccessibilityCheckResultType.WARNING)
        .setSuppressingResultMatcher(AllowedFindings(inline, idleBars))

private class AllowedFindings(
    private val inline: Set<String>,
    private val idleBars: Set<String>,
) : TypeSafeMatcher<AccessibilityViewCheckResult>() {
    override fun matchesSafely(result: AccessibilityViewCheckResult): Boolean {
        val element = result.element
        val text = element?.text?.toString()?.trim(*SUFFIX_MARKS)
        val description = element?.contentDescription?.toString()
        val allowed = when (result.accessibilityHierarchyCheck) {
            TouchTargetSizeCheck::class.java -> text in inline
            ImageContrastCheck::class.java -> description in idleBars
            else -> false
        }
        if (result.type == AccessibilityCheckResultType.ERROR || result.type == AccessibilityCheckResultType.WARNING) {
            Log.i(
                TAG,
                "${if (allowed) "allowed" else "finding"} ${result.accessibilityHierarchyCheck?.simpleName} " +
                    "${result.type} text=$text description=$description " +
                    "class=${element?.className}: ${result.getMessage(Locale.ENGLISH)}",
            )
        }
        return allowed
    }

    override fun describeTo(description: Description) {
        description.appendText("a small row suffix, or the waveform's idle bars")
    }
}

/** The middle dot, the space and the no-break space around a row suffix such as "· Resume". */
private val SUFFIX_MARKS = charArrayOf('·', ' ', ' ')

private const val TAG = "CassiniA11y"
