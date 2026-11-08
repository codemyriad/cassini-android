package io.codemyriad.cassini.whistle

import android.system.ErrnoException
import android.system.OsConstants
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WhistleIsolationTest {
    @Test
    fun successfulSocketOrUnrelatedFailureCannotProveIsolation() {
        assertThrows(IllegalStateException::class.java) { WhistleIsolation.requireDenied {} }
        assertThrows(IllegalStateException::class.java) {
            WhistleIsolation.requireDenied { throw ErrnoException("socket", OsConstants.ENOSYS) }
        }
    }

    @Test
    fun onlyPermissionDenialsProveIsolation() {
        for (errno in listOf(OsConstants.EACCES, OsConstants.EPERM)) {
            WhistleIsolation.requireDenied { throw ErrnoException("socket", errno) }
        }
    }
}
