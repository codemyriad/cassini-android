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

    @Test fun aRunningImportHasAnImportingCardThatAFailureKeeps() = runTest {
        val release = CompletableDeferred<String>()
        val entered = Channel<String>(Channel.UNLIMITED)
        val tracker = ImportTracker(
            scope = backgroundScope,
            describe = { "$it.m4a" },
            run = { uri ->
                entered.send(uri)
                if (uri == "bad") "reject" else release.await()
            },
            rejected = { result -> if (result == "reject") ImportFailure(Failure.OPEN) else null },
            report = { _, error -> throw AssertionError(error) },
            clock = { 3 },
        )
        val good = tracker.start("good")
        assertEquals("good", entered.receive())
        assertEquals(listOf(ImportCard(1, "good.m4a")), tracker.state.value.cards)
        tracker.start("bad").await()
        assertEquals("bad", entered.receive())
        assertEquals(
            listOf(ImportCard(1, "good.m4a"), ImportCard(2, "bad.m4a", Failure.OPEN)),
            tracker.state.value.cards
        )
        assertEquals(ImportFailure(Failure.OPEN, at = 3), tracker.state.value.failure)
        assertEquals(listOf("good.m4a"), tracker.state.value.running)
        release.complete("ok")
        good.await()
        assertEquals(listOf(ImportCard(2, "bad.m4a", Failure.OPEN)), tracker.state.value.cards)
        // The next import clears the failed card with the failure ("shown until dismissed or the next import").
        val next = tracker.start("next")
        assertEquals("next", entered.receive())
        next.await()
        assertEquals(emptyList<ImportCard>(), tracker.state.value.cards)
        assertNull(tracker.state.value.failure)
    }

    @Test fun pickerImportsReportCardsWithoutTheBanner() = runTest {
        val tracker = ImportTracker(
            scope = backgroundScope,
            describe = { it },
            run = { it },
            rejected = { _: String -> null },
            report = { _, error -> throw AssertionError(error) },
            fallbackName = { "Imported audio" },
        )
        val a = tracker.begin("a.mp3")
        val b = tracker.begin("b.wav")
        val c = tracker.begin("")
        assertEquals(listOf("a.mp3", "b.wav", "Imported audio"), tracker.state.value.running)
        tracker.finish(a)
        tracker.finish(b, Failure.OPEN)
        assertEquals(
            listOf(ImportCard(b, "b.wav", Failure.OPEN), ImportCard(c, "Imported audio")),
            tracker.state.value.cards
        )
        assertNull("the picker shows its own summary", tracker.state.value.failure)
        tracker.dismissCard(c)
        assertEquals("a running card cannot be dismissed", 2, tracker.state.value.cards.size)
        tracker.dismissCard(b)
        assertEquals(listOf(ImportCard(c, "Imported audio")), tracker.state.value.cards)
        tracker.finish(c, Failure.SPACE)
        tracker.dismissFailure()
        assertEquals(emptyList<ImportCard>(), tracker.state.value.cards)
    }

    @Test fun aCancelledImportRemovesItsCard() = runTest {
        val tracker = ImportTracker(
            scope = backgroundScope,
            describe = { it },
            run = { CompletableDeferred<String>().await() },
            rejected = { _: String -> null },
            report = { _, error -> throw AssertionError(error) },
        )
        val job = tracker.start("x")
        while (tracker.state.value.cards.isEmpty()) kotlinx.coroutines.yield()
        job.cancelAndJoin()
        assertEquals(emptyList<ImportCard>(), tracker.state.value.cards)
        assertNull(tracker.state.value.failure)
    }
}
