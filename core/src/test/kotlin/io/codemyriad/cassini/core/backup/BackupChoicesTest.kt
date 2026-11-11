package io.codemyriad.cassini.core.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupChoicesTest {
    @Test fun transferDefaultNeverAuthorizesCloudOrAnUnknownTransport() {
        val choices = BackupChoices()
        assertTrue(choices.permits(35, 2))
        assertTrue(choices.permits(35, 3)) // Encrypted direct transfer.
        assertTrue(choices.permits(37, 8)) // Direct cross-platform transfer.
        assertFalse(choices.permits(35, 0))
        assertFalse(choices.permits(35, 1)) // Encryption is not transfer consent.
        assertFalse(choices.permits(35, 128))
        assertTrue(choices.showTransferNotice)
    }

    @Test fun theTwoChoicesAreIndependentAndTransferTakesPrecedence() {
        val cloudOnly = BackupChoices(transfer = false, cloud = true)
        assertFalse(cloudOnly.permits(35, 2))
        assertFalse(cloudOnly.permits(35, 3))
        assertFalse(cloudOnly.permits(37, 8))
        assertTrue(cloudOnly.permits(35, 0))
        assertTrue(cloudOnly.permits(35, 1))
        assertTrue(cloudOnly.permits(35, 128))
        assertFalse(cloudOnly.showTransferNotice)
        for (flags in listOf(0, 1, 2, 3, 8, 128)) {
            assertFalse(BackupChoices.UNREADABLE.permits(35, flags))
            assertTrue(BackupChoices(cloud = true).permits(35, flags))
        }
    }

    @Test fun oldAndroidHasNoTransferProofAndRequiresCloudConsent() {
        for (api in listOf(26, 27)) {
            assertFalse(BackupChoices().permits(api, 2))
            assertFalse(BackupChoices().permits(api, 0))
            assertTrue(BackupChoices(transfer = false, cloud = true).permits(api, 0))
        }
        assertTrue(BackupChoices().permits(28, 2))
        assertFalse(BackupChoices(noticeAcknowledged = true).showTransferNotice)
    }
}
