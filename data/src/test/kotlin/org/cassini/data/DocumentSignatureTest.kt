package org.cassini.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class DocumentSignatureTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file(size: Int): File = folder.newFile().apply { writeBytes(ByteArray(size) { (it % 251).toByte() }) }

    private fun overwrite(file: File, at: Long, value: Int) {
        val mtime = file.lastModified()
        RandomAccessFile(file, "rw").use {
            it.seek(at)
            it.write(value)
        }
        file.setLastModified(mtime)
    }

    @Test fun missingFileHasNoSignature() {
        assertNull(DocumentSignature.of(File(folder.root, "absent.opus")))
    }

    @Test fun signatureStartsWithLengthAndMtime() {
        val file = file(1000)
        val signature = DocumentSignature.of(file)!!
        val parts = signature.split(':')
        assertEquals(listOf("1000", file.lastModified().toString()), parts.take(2))
        assertEquals(64, parts[2].length)
        assertEquals(signature, DocumentSignature.of(file))
    }

    @Test fun sameSizeRewriteWithRestoredMtimeChangesTheSignatureAtEitherEnd() {
        val file = file(300_000)
        val original = DocumentSignature.of(file)
        overwrite(file, at = 100, value = 0x55)
        val headChanged = DocumentSignature.of(file)
        assertNotEquals(original, headChanged)
        overwrite(file, at = 299_990, value = 0x55)
        assertNotEquals(headChanged, DocumentSignature.of(file))
    }

    @Test fun smallFilesAreHashedWhole() {
        val file = file(10)
        val original = DocumentSignature.of(file)
        overwrite(file, at = 5, value = 0x7f)
        assertNotEquals(original, DocumentSignature.of(file))
    }
}
