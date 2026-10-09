package org.cassini.engine.job

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * The one thread that runs every sherpa-onnx call, the Opus encoder and a job's checkpoint writes. Native objects
 * are created, used and released on it, so no two inference calls ever overlap and none runs on the main thread.
 * The thread starts with the first task and lives as long as the process.
 */
object InferenceDispatcher {
    const val THREAD_NAME = "cassini-inference"

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, THREAD_NAME).apply { isDaemon = true }
    }

    /** Use with `withContext(InferenceDispatcher.dispatcher)`. */
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
}
