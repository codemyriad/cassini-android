package io.codemyriad.cassini.data.backup

import io.codemyriad.cassini.core.backup.BackupChoices
import io.codemyriad.cassini.data.db.offMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

@RunWith(RobolectricTestRunner::class)
class BackupChoicesStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun missingChoicesDefaultToEverythingOnAndWritesSurviveANewInstance() = runTest {
        val file = File(folder.root, "choices.json")
        val store = BackupChoicesStore(file, Dispatchers.IO)
        assertEquals(BackupChoices(), store.choices.first())
        assertTrue(store.choices.first().cloud)
        store.setVoices(false)
        store.setTransfer(false)
        val restored = BackupChoicesStore(file, Dispatchers.IO).choices.first()
        assertFalse(restored.transfer)
        assertTrue(restored.cloud)
        assertFalse(restored.voices)
        assertTrue(restored.noticeAcknowledged)
        val anotherDevice = BackupChoicesStore(File(folder.root, "other-device.json"), Dispatchers.IO)
        assertEquals(BackupChoices(), anotherDevice.choices.first())
    }

    @Test fun unchangedChoicesDoNotCreateOrReplaceAnAtomicFile() = runTest {
        val file = File(folder.root, "choices.json")
        val store = BackupChoicesStore(file, Dispatchers.IO)
        store.recordQuotaExceeded(false)
        assertFalse(file.exists())
        store.acknowledgeNotice()
        val identity = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()
        val bytes = file.readText()
        store.acknowledgeNotice()
        store.recordQuotaExceeded(false)
        assertEquals(identity, Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey())
        assertEquals(bytes, file.readText())
        assertFalse(File(file.path + ".new").exists())
        assertFalse(File(file.path + ".bak").exists())
    }

    @Test fun corruptUnsupportedOrOversizedChoicesNeverEnableAnUpload() = runTest {
        val file = File(folder.root, "choices.json")
        for (text in listOf(
            "{",
            "x".repeat(5000),
            """{"version":99}""",
            """{"version":2,"transfer":true,"cloud":true,"noticeAcknowledged":false,"quotaExceeded":false}""",
            """
            {"version":1,"transfer":true,"cloud":"true","noticeAcknowledged":false,"quotaExceeded":false}
        """
        )) {
            file.writeText(text)
            val value = BackupChoicesStore(file, Dispatchers.IO).choices.first()
            assertEquals(BackupChoices.UNREADABLE, value)
            assertFalse(value.permits(35, 0))
            assertFalse(value.permits(35, 2))
        }
    }

    @Test fun quotaStateAndAcknowledgementAreLocalPersistedAndPerChannel() = runTest {
        val file = File(folder.root, "choices.json")
        val store = BackupChoicesStore(file, Dispatchers.IO)
        store.acknowledgeNotice()
        store.recordQuotaExceeded(true)
        store.recordQuotaExceeded(true, cloud = true)
        assertFalse(store.choices.first().showBackupCard(35, cloudEnabled = true))
        val restored = BackupChoicesStore(file, Dispatchers.IO).choices.first()
        assertTrue(restored.quotaExceeded)
        assertTrue(restored.cloudQuotaExceeded)
        store.setCloud(true)
        assertTrue(store.choices.first().quotaExceeded)
        assertFalse(store.choices.first().cloudQuotaExceeded)
    }

    @Test fun keepOnPhoneOnlyTurnsOffBothAutomaticChannelsAndLeavesTheSwitchesIndependent() = runTest {
        val file = File(folder.root, "choices.json")
        val store = BackupChoicesStore(file, Dispatchers.IO)
        store.recordQuotaExceeded(true, cloud = true)
        store.keepOnPhoneOnly()
        val value = BackupChoicesStore(file, Dispatchers.IO).choices.first()
        assertEquals(
            BackupChoices(transfer = false, cloud = false, voices = true, noticeAcknowledged = true),
            value,
        )
        assertTrue(value.phoneOnly(35, cloudEnabled = true))
        assertFalse(value.permits(35, 1, cloudEnabled = true))
        assertFalse(value.permits(35, 2, cloudEnabled = true))
        store.setTransfer(true)
        assertFalse(store.choices.first().cloud)
        assertTrue(store.choices.first().transfer)
    }

    @Test fun aVersion1FileKeepsItsCloudChoiceAndGainsSavedVoices() = runTest {
        val file = File(folder.root, "choices.json")
        file.writeText(
            """{"version":1,"transfer":true,"cloud":false,"noticeAcknowledged":true,"quotaExceeded":true}""",
        )
        val store = BackupChoicesStore(file, Dispatchers.IO)
        assertEquals(
            BackupChoices(
                transfer = true,
                cloud = false,
                voices = true,
                noticeAcknowledged = true,
                quotaExceeded = true,
            ),
            store.choices.first(),
        )
        store.setVoices(false)
        val text = file.readText()
        assertTrue(text, text.contains("\"version\":2"))
        assertFalse(BackupChoicesStore(file, Dispatchers.IO).choices.first().cloud)
    }

    @Test fun turningTransferOffClearsThePersistedQuotaWarning() = runTest {
        val file = File(folder.root, "choices.json")
        val store = BackupChoicesStore(file, Dispatchers.IO)
        store.recordQuotaExceeded(true)
        store.setTransfer(false)
        val restored = BackupChoicesStore(file, Dispatchers.IO).choices.first()
        assertFalse(restored.transfer)
        assertFalse(restored.quotaExceeded)
        assertTrue(restored.noticeAcknowledged)
    }

    @Test fun anUnwritableParentDoesNotInventAPersistedChoice() {
        val parent = folder.newFile("not-a-directory")
        val store = BackupChoicesStore(File(parent, "choices.json"), Dispatchers.IO)
        assertEquals(BackupChoices.UNREADABLE, offMain { store.readBlocking() })
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { store.setCloud(true) }
        }
        assertEquals(BackupChoices.UNREADABLE, offMain { store.readBlocking() })
    }
}
