package org.cassini.android

import java.io.File

/** A foreground microphone capture that leaves a finalized audio file behind. */
internal interface RecordingCapture {
    val file: File
    val paused: Boolean
    val elapsedMs: Long
    val amplitude: Int
    fun togglePause()
    /** Finalizes the file and returns it; throws if capture failed or recorded nothing. */
    fun stop(): File
    fun release()
}
