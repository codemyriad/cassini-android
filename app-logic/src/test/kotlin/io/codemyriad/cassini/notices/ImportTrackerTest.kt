package io.codemyriad.cassini.notices

import io.codemyriad.cassini.core.Failure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class ImportTrackerTest {
    @Test fun cancellationRemovesOnlyItsImportWithoutPublishingAnError() = runTest {
        val entered = Channel<String>(Channel.UNLIMITED)
        val release = CompletableDeferred<String>()
        val tracker = ImportTracker(
            scope = backgroundScope,
            describe = { it },
            run = { uri ->
                entered.send(uri)
                release.await()
            },
            rejected = { _: String -> null },
            report = { _, error -> throw AssertionError(error) },
        )
        val first = tracker.start("one")
        assertEquals("one", entered.receive())
        val second = tracker.start("two")
        assertEquals("two", entered.receive())
        assertEquals(listOf("one", "two"), tracker.state.value.running)
        first.cancelAndJoin()
        assertEquals(listOf("two"), tracker.state.value.running)
        assertNull(tracker.state.value.failure)
        release.complete("ok")
        assertEquals("ok", second.await())
        assertEquals(emptyList<String>(), tracker.state.value.running)
        assertNull(tracker.state.value.failure)
    }

    @Test fun exceptionsRejectionsAndDismissalKeepTheirOriginalFailurePolicy() = runTest {
        val reports = mutableListOf<String>()
        val tracker = ImportTracker(
            scope = backgroundScope,
            describe = { throw IOException("name") },
            run = { uri -> if (uri == "throw") throw IOException("open") else uri },
            rejected = { result -> if (result == "reject") ImportFailure(Failure.SPACE, 4096) else null },
            report = { message, _ -> reports += message },
            clock = { 7 },
            fallbackName = { "Audio" },
        )
        assertNull(tracker.start("throw").await())
        assertEquals(ImportFailure(Failure.OPEN, at = 7), tracker.state.value.failure)
        assertEquals(listOf("Cannot describe an imported file", "Import failed"), reports)
        assertEquals("reject", tracker.start("reject").await())
        assertEquals(ImportFailure(Failure.SPACE, 4096, 7), tracker.state.value.failure)
        tracker.dismissFailure()
        assertNull(tracker.state.value.failure)
        tracker.refuse(Failure.WAV)
        assertEquals(ImportFailure(Failure.WAV, at = 7), tracker.state.value.failure)
        assertEquals("ok", tracker.start("ok").await())
        assertNull(tracker.state.value.failure)
        assertEquals(emptyList<String>(), tracker.state.value.running)
    }
}
