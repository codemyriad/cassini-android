package org.cassini.core.models

import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** The once-per-process check of an installed model against its pins before its first native load (D16 section 4). */
class ModelCheckTest {
    @get:Rule val folder = TemporaryFolder()

    private val encoder = ByteArray(10_000) { (it * 7).toByte() }
    private val tokens = "a 0\nb 1\n".toByteArray()

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val model = ModelDescriptor(
        "test-model",
        "c".repeat(64),
        listOf(
            Artifact("encoder.onnx", encoder.size.toLong(), sha256(encoder)),
            Artifact("tokens.txt", tokens.size.toLong(), sha256(tokens)),
        ),
    )

    private val hashed = mutableListOf<String>()
    private val check = ModelCheck { file ->
        hashed += file.name
        ArtifactFetcher.digest(file)
    }

    private fun install(root: File): File = model.directory(root).apply {
        mkdirs()
        File(this, "encoder.onnx").writeBytes(encoder)
        File(this, "tokens.txt").writeBytes(tokens)
        File(this, model.markerFile).writeText(model.markerValue)
    }

    @Test fun anIntactModelIsHashedOnceAProcess() {
        val root = folder.root
        install(root)
        check.require(model, root)
        check.require(model, root)
        assertEquals(listOf("encoder.onnx", "tokens.txt"), hashed)
        assertTrue(check.passed(model, root))
    }

    @Test fun aDamagedFileLosesTheMarkerAndTheFileAndFailsTheCheck() {
        val root = folder.root
        val home = install(root)
        // Same size, other bytes: the marker and the sizes still say installed.
        File(home, "encoder.onnx").writeBytes(encoder.copyOf().also { it[0] = (it[0] + 1).toByte() })
        assertTrue(model.ready(root))
        try {
            check.require(model, root)
            fail("A damaged model must not reach the native runtime")
        } catch (error: UserFacingException) {
            assertEquals(Failure.VERIFY, error.failure)
        }
        assertFalse(File(home, model.markerFile).exists())
        assertFalse(File(home, "encoder.onnx").exists())
        assertTrue("an intact file stays", File(home, "tokens.txt").isFile)
        assertFalse(model.ready(root))
        assertFalse(check.passed(model, root))

        // Installed again, the model passes.
        install(root)
        check.require(model, root)
        assertTrue(check.passed(model, root))
    }

    @Test fun aModelWithoutItsMarkerIsLeftToTheSeam() {
        val root = folder.root
        File(install(root), model.markerFile).delete()
        check.require(model, root)
        assertTrue("nothing is hashed for a model that is not installed", hashed.isEmpty())
        assertFalse(check.passed(model, root))
    }
}
