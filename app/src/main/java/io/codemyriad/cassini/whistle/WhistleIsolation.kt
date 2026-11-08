package io.codemyriad.cassini.whistle

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants

internal object WhistleIsolation {
    /** No host/contact is opened. Exercise the kernel/SELinux boundary before loading either neural runtime. */
    fun verify(context: Context) {
        check(Process.myUid() != context.applicationInfo.uid) { "Whistle requires an isolated UID" }
        check(context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED)
        for (family in listOf(OsConstants.AF_INET, OsConstants.AF_INET6)) {
            for (type in listOf(OsConstants.SOCK_STREAM, OsConstants.SOCK_DGRAM)) {
                deniedSocket(family, type)
            }
        }
    }

    private fun deniedSocket(family: Int, type: Int) = requireDenied {
        val socket = Os.socket(family, type, 0)
        Os.close(socket)
    }

    internal fun requireDenied(probe: () -> Unit) {
        try {
            probe()
            error("Whistle isolated process unexpectedly permits Internet sockets")
        } catch (error: ErrnoException) {
            check(error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM) {
                "Whistle socket check failed without proving network denial"
            }
        }
    }
}
