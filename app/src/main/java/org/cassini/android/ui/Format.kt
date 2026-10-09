package org.cassini.android.ui

/** mm:ss, or h:mm:ss from one hour. */
internal fun clock(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return if (s >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(java.util.Locale.ROOT, "%02d:%02d", s / 60, s % 60)
}
