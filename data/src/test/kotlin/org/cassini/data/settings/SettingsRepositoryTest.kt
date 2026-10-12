package org.cassini.data.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.cassini.data.AppPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The DataStore settings of architecture 3.4 at AppPaths.settings. */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var paths: AppPaths

    @Before fun setUp() {
        paths = AppPaths(root = folder.newFolder("root"), cache = folder.newFolder("cache"))
    }

    /** Runs [block] with a store over [paths] and closes it after, so the next store can open the same file. */
    private suspend fun <T> withStore(block: suspend (SettingsRepository) -> T): T {
        val job = Job()
        try {
            return block(SettingsRepository.create(paths, CoroutineScope(Dispatchers.IO + job)))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun defaultsBeforeAnyWrite() = runTest {
        val settings = withStore { it.snapshot() }
        assertEquals(Settings(null, null, null, null, null, 0, false, false, null), settings)
    }

    @Test fun everyKeyRoundTripsThroughTheFile() = runTest {
        withStore { store ->
            store.recordConsent(1_000)
            store.recordDeclined(900)
            store.recordFinalization(8_000)
            store.setQueueHold(QueueHold.SYSTEM)
            store.setLastRecordingFormat("m4a")
            store.acknowledgeRecovered(5_000)
            store.acknowledgeOpusNative()
            store.markNotificationsAsked()
            store.recordPurge(7_000)
        }
        assertTrue(paths.settings.isFile)

        // A new process reads the same values.
        withStore { store ->
            assertEquals(
                Settings(1_000, 900, 8_000, QueueHold.SYSTEM, "m4a", 5_000, true, true, 7_000),
                store.snapshot(),
            )
            assertEquals(1_000L, store.consentAt.first())
            assertEquals(QueueHold.SYSTEM, store.queueHold.first())
            assertTrue(store.opusNativeAck.first())
        }
    }

    @Test fun keysKeepTheirStoredNames() = runTest {
        withStore { store ->
            store.recordConsent(1)
            store.setQueueHold(QueueHold.USER)
        }
        val text = String(paths.settings.readBytes(), Charsets.ISO_8859_1)
        assertTrue(text.contains("models.consentAt"))
        assertTrue(text.contains("queue.hold"))
        assertTrue(text.contains("USER"))
    }

    @Test fun finalizationIsARunningEstimate() = runTest {
        withStore { store ->
            assertEquals(8_000L, store.recordFinalization(8_000))
            assertEquals((8_000L * 3 + 4_000) / 4, store.recordFinalization(4_000))
            assertEquals(7_000L, store.finalizationMs.first())
        }
    }

    @Test fun holdsClearAndRecoveredAckNeverGoesBack() = runTest {
        withStore { store ->
            store.setQueueHold(QueueHold.USER)
            store.setQueueHold(null)
            assertNull(store.queueHold.first())

            store.acknowledgeRecovered(5_000)
            store.acknowledgeRecovered(3_000)
            assertEquals(5_000L, store.recoveredAck.first())
            assertFalse(store.notificationsAsked.first())
        }
    }

    @Test fun aCorruptFileReadsAsDefaults() = runTest {
        paths.settings.writeBytes(ByteArray(64) { 0x7f })
        withStore { store ->
            assertNull(store.consentAt.first())
            store.recordConsent(42)
            assertEquals(42L, store.consentAt.first())
        }
    }
}
