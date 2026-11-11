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

@RunWith(RobolectricTestRunner::class)
class BackupChoicesStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun missingChoicesDefaultToTransferOnlyAndWritesSurviveANewInstance() = runTest {
        val file = File(folder.root, "choices.json")
        val store = BackupChoicesStore(file, Dispatchers.IO)
        assertEquals(BackupChoices(), store.choices.first())
        store.setCloud(true)
        store.setTransfer(false)
        val restored = BackupChoicesStore(file, Dispatchers.IO).choices.first()
        assertFalse(restored.transfer)
        assertTrue(restored.cloud)
        assertTrue(restored.noticeAcknowledged)
        val anotherDevice = BackupChoicesStore(File(folder.root, "other-device.json"), Dispatchers.IO)
        assertEquals(BackupChoices(), anotherDevice.choices.first())
    }

    @Test fun corruptUnsupportedOrOversizedChoicesNeverEnableAnUpload() = runTest {
        val file = File(folder.root, "choices.json")
        for (text in listOf("{", "x".repeat(5000), """{"version":99}""", """
            {"version":1,"transfer":true,"cloud":"true","noticeAcknowledged":false,"quotaExceeded":false}
        """)) {
            file.writeText(text)
            val value = BackupChoicesStore(file, Dispatchers.IO).choices.first()
            assertEquals(BackupChoices.UNREADABLE, value)
            assertFalse(value.permits(35, 0))
            assertFalse(value.permits(35, 2))
        }
    }

    @Test fun quotaStateAndAcknowledgementAreLocalAndPersisted() = runTest {
        val file = File(folder.root, "choices.json")
        val store = BackupChoicesStore(file, Dispatchers.IO)
        store.acknowledgeNotice()
        store.recordQuotaExceeded(true)
        assertFalse(store.choices.first().showTransferNotice)
        assertTrue(BackupChoicesStore(file, Dispatchers.IO).choices.first().quotaExceeded)
        store.setCloud(true)
        assertFalse(store.choices.first().quotaExceeded)
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
