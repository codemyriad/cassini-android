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

    /**
     * The audio session of the running capture, which `AudioRecordingConfiguration.getClientAudioSessionId` names
     * (API 29+); null when it is not known yet.
     */
    val sessionId: Int? get() = null
    fun togglePause()

    /**
     * Ends the capture and returns the finished [file]. The wait (up to 10 s for the capture thread) runs on
     * [kotlinx.coroutines.Dispatchers.IO], so a caller that suspends does not block its thread. A caller that wraps
     * it in `runBlocking` still blocks: 0.1.0's `RecordingService.finish` does so on the main thread until P3
     * replaces the service.
     */
    suspend fun stop(): File

    /** Stops without waiting for the result; for error paths and teardown. */
    fun release()
}
