@file:Suppress("MagicNumber") // Independently forged ZIP header/extra-field records.

package io.codemyriad.cassini.data.notes

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.CRC32

class ArchiveHeaderTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun localNamesAndUnicodePathsCannotDisagreeWithTheCentralEntry() = runTest {
        for (path in listOf("other.opus", "folder/", "__MACOSX/._late.opus")) {
            refuses(archive(localName = path))
            refuses(archive(centralExtra = unicode("late.opus", path)))
            refuses(archive(localExtra = unicode("late.opus", path)))
            refuses(archive(localExtra = unicode("late.opus", path), centralExtra = unicode("late.opus", "late.opus")))
        }
        for (raw in listOf("folder/", "__MACOSX/._late.opus")) {
            val disguised = unicode(raw, "late.opus")
            refuses(archive(localName = raw, centralName = raw, centralExtra = disguised))
            refuses(archive(localName = raw, centralName = raw, localExtra = disguised, centralExtra = disguised))
        }
        // The local legacy name cannot be hidden by a central Unicode metadata classification.
        refuses(archive(centralExtra = unicode("late.opus", "__MACOSX/._late.opus")))
    }

    @Test fun optionalUnicodeFieldsDoNotHaveToBePresentOnBothSides() = runTest {
        val same = unicode("late.opus", "late.opus")
        for ((local, central) in listOf(same to byteArrayOf(), byteArrayOf() to same, same to same)) {
            val input = archive(localExtra = local, centralExtra = central).inputStream()
            val summary = NoteArchives().read(input, folder.newFolder()) { _, name -> ImportOutcome.Created(name) }
            assertEquals(ImportSummary(imported = 2), summary)
        }
    }

    @Test fun securityExtrasWinOverDuplicatesAndTruncationInOneScan() = runTest {
        val safe = unicode("late.opus", "late.opus")
        val unsafe = unicode("late.opus", "../outside.opus")
        val duplicate = field(0xbeef, byteArrayOf()) + field(0xbeef, byteArrayOf())
        val truncated = byteArrayOf(1, 0, 20, 0)
        val aes = byteArrayOf(1, 0x99.toByte(), 20, 0) // AES id with a truncated body still means encrypted.
        for (local in listOf(false, true)) {
            for (extra in listOf(unsafe + safe, safe + unsafe, duplicate + unsafe, unsafe + truncated)) {
                refuses(withExtra(extra, local), ArchiveFailure.UNSAFE_PATHS)
            }
            refuses(withExtra(duplicate + aes, local), ArchiveFailure.ENCRYPTED)
            refuses(withExtra(duplicate + aes.copyOf(2), local), ArchiveFailure.ENCRYPTED)
            refuses(withExtra(safe + safe, local))
            refuses(withExtra(safe + truncated, local))
        }
    }

    @Test fun finderStyleDittoArchiveKeepsItsUnicodeNames() = runTest {
        val names = mutableListOf<String>()
        val input = requireNotNull(javaClass.getResourceAsStream("/zip/finder-ditto.zip"))
        val summary = NoteArchives().read(input, folder.newFolder()) { _, name ->
            names += name
            ImportOutcome.Created(name)
        }
        assertEquals(ImportSummary(imported = 2), summary)
        assertEquals(setOf("café.opus", "Meeting.opus"), names.toSet())
    }

    @Test fun unflaggedNamesAcceptUtf8AndStillDecodeLegacyCp437() = runTest {
        for (charset in listOf(Charsets.UTF_8, java.nio.charset.Charset.forName("CP437"))) {
            val input = archive(localName = "café.opus", centralName = "café.opus", encoding = charset, flags = 0)
            val names = mutableListOf<String>()
            NoteArchives().read(input.inputStream(), folder.newFolder()) { _, name ->
                names += name
                ImportOutcome.Created(name)
            }
            assertEquals(setOf("good.opus", "café.opus"), names.toSet())
        }
    }

    private fun withExtra(extra: ByteArray, local: Boolean): ByteArray =
        if (local) archive(localExtra = extra) else archive(centralExtra = extra)

    private suspend fun refuses(bytes: ByteArray, reason: ArchiveFailure = ArchiveFailure.DAMAGED) {
        val scratch = folder.newFolder()
        var published = 0
        try {
            NoteArchives().read(bytes.inputStream(), scratch) { _, name ->
                published++
                ImportOutcome.Created(name)
            }
            error("Archive accepted")
        } catch (error: ArchiveRejected) {
            assertEquals(reason, error.reason)
        }
        assertEquals("Even the first valid note must not be published", 0, published)
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }

    private fun unicode(raw: String, path: String): ByteArray = field(
        0x7075,
        ByteArray(5).apply {
            this[0] = 1
            putInt(1, CRC32().apply { update(raw.toByteArray()) }.value.toInt())
        } + path.toByteArray(),
    )

    private fun field(id: Int, data: ByteArray): ByteArray = ByteArray(4).apply {
        putShort(0, id)
        putShort(2, data.size)
    } + data

    @Suppress("LongParameterList") // Independent forged local/central fields and legacy filename encoding.
    private fun archive(
        localName: String = "late.opus",
        centralName: String = "late.opus",
        localExtra: ByteArray = byteArrayOf(),
        centralExtra: ByteArray = byteArrayOf(),
        encoding: java.nio.charset.Charset = Charsets.UTF_8,
        flags: Int = 0x800,
    ): ByteArray {
        var locals = byteArrayOf()
        var centrals = byteArrayOf()
        for (name in listOf("good.opus", "late.opus")) {
            val raw = (if (name == "late.opus") localName else name).toByteArray(encoding)
            val central = (if (name == "late.opus") centralName else name).toByteArray(encoding)
            val le = if (name == "late.opus") localExtra else byteArrayOf()
            val ce = if (name == "late.opus") centralExtra else byteArrayOf()
            val data = "audio".toByteArray()
            val crc = CRC32().apply { update(data) }.value.toInt()
            centrals += ByteArray(46).apply {
                putInt(0, 0x02014b50)
                putShort(8, flags)
                putInt(16, crc)
                putInt(20, data.size)
                putInt(24, data.size)
                putShort(28, central.size)
                putShort(30, ce.size)
                putInt(42, locals.size)
            } + central + ce
            locals += ByteArray(30).apply {
                putInt(0, 0x04034b50)
                putShort(6, flags)
                putInt(14, crc)
                putInt(18, data.size)
                putInt(22, data.size)
                putShort(26, raw.size)
                putShort(28, le.size)
            } + raw + le + data
        }
        return locals + centrals + ByteArray(22).apply {
            putInt(0, 0x06054b50)
            putShort(8, 2)
            putShort(10, 2)
            putInt(12, centrals.size)
            putInt(16, locals.size)
        }
    }
}
