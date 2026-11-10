package io.codemyriad.cassini.processing

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingNoticesTest {
    @Test fun aNoticeRecordedBeforeObservationIsRetained() = runBlocking {
        val notices = RecordingNotices()
        notices.readyToTranscribe("Note", "Note")

        notices.awaitNotice(TIMEOUT_MS) { "Note" in notices.ready }

        assertEquals(listOf("Note"), notices.ready)
    }

    @Test fun eachRequiredNoticeMustArriveBeforeTheWaitCompletes() = runBlocking {
        listOf(false, true).forEach { readyFirst ->
            val notices = RecordingNotices()
            val pending = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                notices.awaitNotice(TIMEOUT_MS) { "Failed" in notices.failed && "Consent" in notices.ready }
            }
            assertFalse(pending.isCompleted)

            if (readyFirst) {
                notices.readyToTranscribe("Consent", "Consent")
            } else {
                notices.failed("Failed", "Failed", "DOWNLOAD")
            }
            assertFalse("one notice does not satisfy the combined predicate", pending.isCompleted)
            if (readyFirst) {
                notices.failed("Failed", "Failed", "DOWNLOAD")
            } else {
                notices.readyToTranscribe("Consent", "Consent")
            }

            assertTrue("the second recorded fact wakes the existing waiter", pending.isCompleted)
            pending.await()
            assertEquals(listOf("Failed"), notices.failed)
            assertEquals(listOf("Consent"), notices.ready)
        }
    }

    @Test fun anInterruptedNoticeWakesItsWaiterAfterRecordingTheFact() = runBlocking {
        val notices = RecordingNotices()
        val pending = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            notices.awaitNotice(TIMEOUT_MS) { "Interrupted" in notices.interrupted }
        }
        assertFalse(pending.isCompleted)

        notices.interrupted("Interrupted", "Interrupted")

        assertTrue(pending.isCompleted)
        pending.await()
        assertEquals(listOf("Interrupted"), notices.interrupted)
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
