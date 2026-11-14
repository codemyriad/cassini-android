package io.codemyriad.cassini.core.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupChoicesTest {
    private val encrypted = 1
    private val transfer = 2

    @Test fun defaultsAreOnForCloudTransferAndSavedVoices() {
        val choices = BackupChoices()
        assertTrue(choices.cloud)
        assertTrue(choices.transfer)
        assertTrue(choices.voices)
        assertTrue(choices.showBackupCard(35, cloudEnabled = true))
    }

    @Test fun disabledCloudBuildFlagOverridesTheChoiceOnEveryApiAndTransport() {
        val choices = BackupChoices(transfer = false, cloud = true)
        for (api in listOf(26, 27, 28, 35)) {
            for (flags in listOf(0, 1, 128)) assertFalse(choices.permits(api, flags))
        }
        assertFalse(choices.cloudActive(35, cloudEnabled = false))
        assertFalse(choices.showBackupCard(35, cloudEnabled = false))
    }

    @Test fun cloudNeedsClientSideEncryptionSoTheEncryptedClaimHolds() {
        val choices = BackupChoices(transfer = false)
        assertTrue(choices.permits(35, encrypted, cloudEnabled = true))
        assertTrue(choices.permits(35, encrypted or 128, cloudEnabled = true))
        assertFalse(choices.permits(35, 0, cloudEnabled = true)) // Unencrypted cloud is refused.
        assertFalse(choices.permits(35, 128, cloudEnabled = true)) // Unknown transport.
        assertFalse(BackupChoices(cloud = false).permits(35, encrypted, cloudEnabled = true))
    }

    @Test fun transferFollowsOnlyTheTransferChoice() {
        val transferOnly = BackupChoices(cloud = false)
        assertTrue(transferOnly.permits(35, transfer))
        assertTrue(transferOnly.permits(35, transfer or encrypted)) // Encrypted direct transfer.
        assertTrue(transferOnly.permits(37, 8)) // Direct cross-platform transfer.
        assertFalse(transferOnly.permits(35, encrypted, cloudEnabled = true)) // Encryption is not transfer consent.
        val cloudOnly = BackupChoices(transfer = false)
        assertFalse(cloudOnly.permits(35, transfer, cloudEnabled = true))
        assertFalse(cloudOnly.permits(35, transfer or encrypted, cloudEnabled = true))
        assertFalse(cloudOnly.permits(37, 8, cloudEnabled = true))
        for (flags in listOf(0, 1, 2, 3, 8, 128)) assertFalse(BackupChoices.UNREADABLE.permits(35, flags, true))
    }

    @Test fun oldAndroidCannotTellTransportsApartAndRefusesBoth() {
        for (api in listOf(26, 27)) {
            for (flags in listOf(0, 1, 2, 3)) assertFalse(BackupChoices().permits(api, flags, cloudEnabled = true))
            assertFalse(BackupChoices().cloudActive(api, cloudEnabled = true))
            assertFalse(BackupChoices().transferActive(api))
            assertTrue(BackupChoices().phoneOnly(api, cloudEnabled = true))
            assertFalse(BackupChoices().showBackupCard(api, cloudEnabled = true))
        }
        assertTrue(BackupChoices().permits(28, transfer))
        assertTrue(BackupChoices().permits(28, encrypted, cloudEnabled = true))
    }

    @Test fun activeChannelsAndThePhoneOnlyStateFollowThePermitRules() {
        assertTrue(BackupChoices().transferActive(28))
        assertFalse(BackupChoices.UNREADABLE.transferActive(35))
        assertTrue(BackupChoices().cloudActive(35, cloudEnabled = true))
        assertFalse(BackupChoices(cloud = false).cloudActive(35, cloudEnabled = true))
        assertFalse(BackupChoices.UNREADABLE.cloudActive(35, cloudEnabled = true))
        assertFalse(BackupChoices().phoneOnly(35, cloudEnabled = true))
        assertFalse(BackupChoices(cloud = false).phoneOnly(35, cloudEnabled = true))
        assertFalse(BackupChoices(transfer = false).phoneOnly(35, cloudEnabled = true))
        assertTrue(BackupChoices(transfer = false, cloud = false).phoneOnly(35, cloudEnabled = true))
        assertTrue(BackupChoices.UNREADABLE.phoneOnly(35, cloudEnabled = true))
    }

    @Test fun theCardShowsOnceAndOnlyWhileCloudBackupIsOn() {
        assertFalse(BackupChoices(noticeAcknowledged = true).showBackupCard(35, cloudEnabled = true))
        assertFalse(BackupChoices(cloud = false).showBackupCard(35, cloudEnabled = true))
        assertFalse(BackupChoices.UNREADABLE.showBackupCard(35, cloudEnabled = true))
    }

    @Test fun transportClassificationNeedsApi28() {
        assertTrue(BackupChoices.isTransfer(28, transfer))
        assertTrue(BackupChoices.isTransfer(37, 8))
        assertFalse(BackupChoices.isTransfer(27, transfer))
        assertFalse(BackupChoices.isTransfer(35, encrypted))
    }
}
