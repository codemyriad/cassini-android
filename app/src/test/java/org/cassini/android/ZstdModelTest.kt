package org.cassini.android

import com.github.luben.zstd.Zstd
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class ZstdModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val payload = ByteArray(600_000) { (it * 31 % 251).toByte() }
    private val artifact = ModelStore.Artifact("model.onnx", payload.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) })

    /** Two frames and a trailing skippable seek table, like the seekable files on the model host. */
    private fun seekable(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val half = data.size / 2
        out.write(Zstd.compress(data.copyOfRange(0, half)))
        out.write(Zstd.compress(data.copyOfRange(half, data.size)))
        val table = ByteArray(9 + 8)
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(0x184D2A5E).putInt(table.size).array())
        out.write(table)
        return out.toByteArray()
    }

    @Test fun seekableZstandardDecompressesToTheVerifiedArtifact() {
        val source = File(folder.root, "model.onnx.zst").apply { writeBytes(seekable(payload)) }
        ModelStore.decompress(source, artifact, folder.root)
        assertArrayEquals(payload, File(folder.root, artifact.name).readBytes())
        assertFalse(File(folder.root, "${artifact.name}.part").exists())
    }

    @Test fun wrongContentLeavesNoModel() {
        val other = payload.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        val source = File(folder.root, "model.onnx.zst").apply { writeBytes(seekable(other)) }
        try { ModelStore.decompress(source, artifact, folder.root); fail() } catch (e: UserFacingException) {
            assertEquals(Failure.VERIFY, e.failure)
        }
        assertEquals(listOf("model.onnx.zst"), folder.root.list()!!.toList())
    }

    @Test fun corruptStreamOrOversizedOutputLeavesNoModel() {
        val corrupt = seekable(payload).also { it[20] = (it[20] + 7).toByte() }
        val bigger = seekable(payload + ByteArray(10))
        for (bytes in listOf(corrupt, bigger, "not zstd".toByteArray())) {
            val source = File(folder.root, "model.onnx.zst").apply { writeBytes(bytes) }
            try { ModelStore.decompress(source, artifact, folder.root); fail() } catch (e: UserFacingException) {
                assertEquals(Failure.VERIFY, e.failure)
            }
            assertFalse(File(folder.root, artifact.name).exists())
            assertFalse(File(folder.root, "${artifact.name}.part").exists())
        }
    }
}
