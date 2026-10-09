package org.cassini.ui

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric SDK 36+ needs a JDK 21 runtime; robolectric.properties pins SDK 35 until CI moves. */
@RunWith(RobolectricTestRunner::class)
class RobolectricSdkTest {
    @Test fun runsAgainstThePinnedSdk() {
        assertEquals(35, Build.VERSION.SDK_INT)
    }
}
