package org.cassini.android

import android.content.Context

/** Device preference, independent of individual notes and their model settings. */
internal object RecordingPreferences {
    private const val KEY = "transcribe_while_recording"
    fun live(context: Context) = context.getSharedPreferences("recording", Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun setLive(context: Context, enabled: Boolean) {
        context.getSharedPreferences("recording", Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }
}
