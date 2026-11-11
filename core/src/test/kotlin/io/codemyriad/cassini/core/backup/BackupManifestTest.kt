package io.codemyriad.cassini.core.backup

import io.codemyriad.cassini.core.speakers.Voice
import io.codemyriad.cassini.core.speakers.VoiceCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class BackupManifestTest {
    @get:Rule val folder = TemporaryFolder()

    private fun put(root: File, path: String, text: String): File = File(root, path).also {
        Files.createDirectories(it.parentFile.toPath())
        it.writeText(text)
    }

    private fun staged(root: File, manifest: BackupManifest): File = folder.newFolder().also { target ->
        manifest.files.forEach { entry ->
            val file = File(target, entry.path)
            Files.createDirectories(file.parentFile.toPath())
            File(root, entry.path).copyTo(file)
        }
        put(target, BackupManifest.MANIFEST, manifest.json())
    }

    @Test fun aCompleteSnapshotKeepsNotesCatalogueAndSavedVoiceVectorsButNoModelsOrConsent() {
        val source = folder.newFolder()
        put(source, "cassini.db", "synthetic catalogue")
        put(source, "notes/note-1/document.opus", "synthetic note")
        val voice = Voice("voice-1", "Ada", "synthetic-model", floatArrayOf(1f, 0f, 0f), 1, 4.0, 1, 1)
        put(source, "voices/people.json", VoiceCodec.encode(listOf(voice)))
        put(source, "voices/generation", "1")
        put(source, "models/encoder.onnx", "model must stay behind")
        put(source, "settings.preferences_pb", "download consent must stay behind")
        put(source, "notes/note-1/work/checkpoint.json", "unfinished inference must stay behind")
        val manifest = BackupManifest.create(source)
        assertEquals(
            setOf("cassini.db", "notes/note-1/document.opus", "voices/people.json", "voices/generation"),
            manifest.files.mapTo(HashSet()) { it.path },
        )
        val target = staged(source, manifest)
        BackupManifest.parse(manifest.json()).verify(target)
        val restored = VoiceCodec.decode(File(target, "voices/people.json").readText()).single()
        assertEquals("Ada", restored.name)
        assertArrayEquals(voice.mean, restored.mean, 0f)
        assertFalse(File(target, "models").exists())
        assertFalse(File(target, "settings.preferences_pb").exists())
    }

    @Test fun missingTruncatedOrExtraFilesNeverPassCompletenessValidation() {
        val source = folder.newFolder()
        put(source, "notes/n/document.opus", "original audio")
        val manifest = BackupManifest.create(source)
        val missing = staged(source, manifest)
        assertTrue(File(missing, "notes/n/document.opus").delete())
        assertThrows(IllegalArgumentException::class.java) { manifest.verify(missing) }
        val truncated = staged(source, manifest)
        File(truncated, "notes/n/document.opus").writeText("original")
        assertThrows(IllegalArgumentException::class.java) { manifest.verify(truncated) }
        val changed = staged(source, manifest)
        File(changed, "notes/n/document.opus").writeText("altered! audio")
        assertThrows(IllegalArgumentException::class.java) { manifest.verify(changed) }
        val extra = staged(source, manifest)
        put(extra, "notes/n/work/model.onnx", "must not be promoted with the notes directory")
        assertThrows(IllegalArgumentException::class.java) { manifest.verify(extra) }
    }

    @Test fun traversalDuplicatesModelPathsAndInvalidSizesAreRefused() {
        fun entry(path: String, size: Any = 1) = JSONObject()
            .put("path", path).put("bytes", size).put("sha256", "0".repeat(64))
        for (path in listOf("../outside", "/notes/n/document.opus", "models/weights.onnx", "notes/n/../source.opus")) {
            val json = JSONObject().put("version", 1).put("files", JSONArray().put(entry(path)))
            assertThrows(IllegalArgumentException::class.java) { BackupManifest.parse(json.toString()) }
        }
        for (size in listOf(-1, BackupManifest.MAX_FILE_BYTES + 1, 1.5, "1")) {
            val json = JSONObject().put("version", 1).put("files", JSONArray().put(entry("cassini.db", size)))
            assertThrows(IllegalArgumentException::class.java) { BackupManifest.parse(json.toString()) }
        }
        val duplicate = JSONObject().put("version", 1).put(
            "files",
            JSONArray().put(entry("cassini.db")).put(entry("cassini.db")),
        )
        assertThrows(IllegalArgumentException::class.java) { BackupManifest.parse(duplicate.toString()) }
    }

    @Test fun linksCannotEscapeTheOwnedSnapshotTree() {
        val source = folder.newFolder()
        val outside = folder.newFile().also { it.writeText("outside the app") }
        Files.createDirectories(File(source, "notes/n").toPath())
        Files.createSymbolicLink(File(source, "notes/n/document.opus").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { BackupManifest.create(source) }
    }
}
