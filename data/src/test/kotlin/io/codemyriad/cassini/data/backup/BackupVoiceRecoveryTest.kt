package io.codemyriad.cassini.data.backup

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.core.speakers.NoteVoiceCodec
import io.codemyriad.cassini.core.speakers.SpeakerPrint
import io.codemyriad.cassini.core.speakers.Voice
import io.codemyriad.cassini.core.speakers.VoiceCodec
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.db.offMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29])
class BackupVoiceRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun legacyInterruptedWritesWithMissingOrTruncatedBasesKeepTheirCommittedDataInTheInventory() {
        assertTrue(Build.VERSION.SDK_INT in 28..29)
        for (missing in listOf(false, true)) {
            val paths = AppPaths(folder.newFolder(), folder.newFolder())
            val voice = Voice("ada", "Ada", "synthetic", floatArrayOf(1f, 0f, 0f), 1, 4.0, 1, 1)
            val prints = mapOf("v1/S1" to SpeakerPrint(floatArrayOf(1f, 0f, 0f), 4.0, null))
            val expected = mapOf(
                "voices/people.json" to VoiceCodec.encode(listOf(voice)),
                "voices/generation" to "7",
                "voices/notes/n.json" to NoteVoiceCodec.encode("synthetic", prints),
            )
            for ((path, text) in expected) {
                val file = File(paths.root, path)
                Files.createDirectories(file.parentFile!!.toPath())
                file.writeText(text)
                val output = AtomicFile(file).startWrite()
                output.write("Truncated".toByteArray())
                output.close() // Simulate process death, deliberately without finishWrite/failWrite.
                assertTrue(File(file.path + ".bak").isFile)
                if (missing) Files.delete(file.toPath())
            }
            val library = LibraryBackup(context, paths, folder.newFolder())
            val manifest = offMain { library.prepare().first }
            assertEquals(expected.keys, manifest.files.mapTo(HashSet()) { it.path })
            val target = AppPaths(folder.newFolder(), folder.newFolder())
            val receiver = LibraryBackup(context, target, folder.newFolder())
            offMain {
                for (path in listOf(BackupManifest.MANIFEST) + manifest.files.map { it.path }) {
                    val file = File(paths.root, path)
                    file.inputStream().use {
                        receiver.receive(path, it, file.length(), file.lastModified() / 1000, Long.MAX_VALUE)
                    }
                }
                receiver.finishRestore()
                receiver.recoverRestore()
            }
            assertEquals("Ada", VoiceCodec.decode(target.people.readText()).single().name)
            for ((path, text) in expected) {
                val file = File(paths.root, path)
                assertEquals(text, file.readText())
                assertEquals(text, File(target.root, path).readText())
                assertFalse(File(file.path + ".bak").exists())
                assertEquals(BackupManifest.sha256(file), manifest.files.single { it.path == path }.sha256)
            }
        }
    }

    @Test fun aLinkedCommittedBackupIsRefusedWithoutTouchingItsTarget() {
        val paths = AppPaths(folder.newFolder(), folder.newFolder())
        val outside = folder.newFile().also { it.writeText("Outside the library") }
        Files.createDirectories(paths.voices.toPath())
        Files.createSymbolicLink(File(paths.voices, "people.json.bak").toPath(), outside.toPath())
        offMain {
            val library = LibraryBackup(context, paths, folder.newFolder())
            assertThrows(IllegalArgumentException::class.java) { library.prepare() }
        }
        assertEquals("Outside the library", outside.readText())
        assertFalse(File(paths.root, BackupManifest.MANIFEST).exists())
    }
}
