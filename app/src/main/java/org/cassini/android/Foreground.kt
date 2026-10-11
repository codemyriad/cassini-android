package org.cassini.android

import android.app.Notification
import android.app.Service
import android.os.Build
import androidx.core.app.ServiceCompat

/**
 * `startForeground` with [type], for every foreground service of the app (architecture 5.7 and 5.8).
 *
 * From API 29 it calls `Service.startForeground(id, notification, type)` itself. androidx.core's
 * `ServiceCompat.startForeground` masks the type with the types it knows (0x40000FFF on API 34+ in core 1.19.1,
 * 0xFF on API 29-33), so `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` (0x2000, API 35) became 0 and Android refused
 * the start with "Starting FGS with type none". Below API 29 services have no type, and `ServiceCompat` drops it.
 * The caller keeps its catch of the refusals ([org.cassini.android.processing.ProcessingService.isForegroundRefusal]).
 */
fun Service.startForegroundWithType(id: Int, notification: Notification, type: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(id, notification, type)
    } else {
        ServiceCompat.startForeground(this, id, notification, type)
    }
}
