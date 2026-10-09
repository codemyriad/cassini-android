package org.cassini.engine.capture

import java.io.File

/**
 * Microphone capture owned by the recording service. Audio written so far is retained on stop, error or release.
 * [CaptureFactory] picks the implementation.
 */
interface MicrophoneCapture {
    val file: File
    val elapsedMs: Long
    val amplitude: Int
    val paused: Boolean
    val opus: Boolean
    fun togglePause()

    /** Ends the capture and returns the finished [file]. Waits on a background thread, never on the caller's. */
    suspend fun stop(): File

    /** Stops without waiting for the result; for error paths and teardown. */
    fun release()
}
