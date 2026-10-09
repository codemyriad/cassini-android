package org.cassini.engine.job

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * The single inference thread for the P3 `TranscriptionJob`, which will run every sherpa-onnx call, the Opus encoder
 * and a job's checkpoint writes on it. Nothing uses it yet: until P3 the 0.1.0 `MainActivity` worker thread owns
 * those calls and takes [NativeBudget] there. The thread starts with the first task and lives as long as the process.
 */
object InferenceDispatcher {
    const val THREAD_NAME = "cassini-inference"

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, THREAD_NAME).apply { isDaemon = true }
    }

    /** Use with `withContext(InferenceDispatcher.dispatcher)`. */
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
}
