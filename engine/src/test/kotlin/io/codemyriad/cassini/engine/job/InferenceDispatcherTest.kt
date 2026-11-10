package io.codemyriad.cassini.engine.job

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

class InferenceDispatcherTest {
    @Test fun everyTaskRunsOnTheOneInferenceThread() = runBlocking {
        val samples = (1..8).map {
            async {
                withContext(InferenceDispatcher.dispatcher) {
                    val thread = Thread.currentThread()
                    // Debug mode decorates the name until the dispatched block has fully unwound.
                    thread to thread.name.substringBefore(" @coroutine#")
                }
            }
        }.awaitAll()
        assertEquals(1, samples.map { it.first }.toSet().size)
        samples.forEach { assertEquals(InferenceDispatcher.THREAD_NAME, it.second) }
    }
}
