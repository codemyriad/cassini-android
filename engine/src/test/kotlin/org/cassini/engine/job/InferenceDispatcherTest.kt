package org.cassini.engine.job

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

class InferenceDispatcherTest {
    @Test fun everyTaskRunsOnTheOneInferenceThread() = runBlocking {
        val threads = (1..8).map {
            async { withContext(InferenceDispatcher.dispatcher) { Thread.currentThread() } }
        }.awaitAll().toSet()
        assertEquals(1, threads.size)
        assertEquals(InferenceDispatcher.THREAD_NAME, threads.single().name)
    }
}
