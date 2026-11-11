package io.codemyriad.cassini.data.notes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class NoteArchivesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun supportedEntriesAreDeliveredAndOtherFilesCounted() = runTest {
        val scratch = folder.newFolder()
        val delivered = mutableListOf<String>()
        val bytes = archive(
            "notes/Meeting.opus" to "first",
            "Again.OPUS" to "first",
            "info.txt" to "ignored",
            "nested.zip" to "PK",
        )
        val seen = HashSet<String>()
        val summary = NoteArchives().read(ByteArrayInputStream(bytes), scratch) { file, name ->
            delivered += name
            if (seen.add(file.readText())) ImportOutcome.Created(name) else ImportOutcome.Existing(name)
        }
        assertEquals(listOf("Meeting.opus", "Again.OPUS"), delivered)
        assertEquals(ImportSummary(imported = 1, duplicates = 1, ignored = 2), summary)
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }

    @Test fun unsafePathsAreRefusedWithoutWritingOutsideScratch() = runTest {
        val unsafe = listOf(
            "../outside.opus",
            "/absolute.opus",
            "a/../../outside.opus",
            "a\\outside.opus",
            "C:/outside.opus",
        )
        for (name in unsafe) {
            val scratch = folder.newFolder()
            rejected {
                NoteArchives().read(ByteArrayInputStream(archive(name to "data")), scratch) { _, _ ->
                    error("Consumed unsafe entry")
                }
            }
            assertTrue(scratch.listFiles().orEmpty().isEmpty())
        }
        assertFalse(File(folder.root, "outside.opus").exists())
    }

    @Test fun expandedBoundsAlsoCoverHighlyCompressedIgnoredEntries() = runTest {
        val scratch = folder.newFolder()
        rejected {
            NoteArchives(ArchiveLimits(expandedBytes = 100)).read(
                ByteArrayInputStream(archive("ignored.txt" to "x".repeat(10_000))),
                scratch,
            ) { _, _ -> error("Ignored file delivered") }
        }
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }

    @Test fun entryAndInputBoundsAreEnforced() = runTest {
        val bytes = archive("a.opus" to "one", "b.opus" to "two")
        for (limits in listOf(ArchiveLimits(entries = 1), ArchiveLimits(inputBytes = 10))) {
            rejected {
                NoteArchives(limits).read(ByteArrayInputStream(bytes), folder.newFolder()) { _, name ->
                    ImportOutcome.Created(name)
                }
            }
        }
    }

    @Test fun cancellationAndFailedConsumptionRemoveTheEntry() = runTest {
        val scratch = folder.newFolder()
        try {
            NoteArchives().read(ByteArrayInputStream(archive("meeting.wav" to "audio")), scratch) { _, _ ->
                throw CancellationException("Cancel transfer")
            }
            error("Cancellation swallowed")
        } catch (_: CancellationException) {
            assertTrue(scratch.listFiles().orEmpty().isEmpty())
        }
        rejected {
            NoteArchives().read(ByteArrayInputStream(archive("meeting.wav" to "audio")), scratch) { _, _ ->
                throw IOException("Cannot import")
            }
        }
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }

    @Test fun exportsAreStoredByteIdenticalAndHaveCollisionSuffixes() = runTest {
        val first = folder.newFile().apply { writeText("complete document") }
        val second = folder.newFile().apply { writeText("original audio") }
        val output = folder.newFile()
        NoteArchives().write(
            output.outputStream(),
            listOf("Meeting.opus" to first, "meeting.opus" to first, "Audio.wav" to second),
        )
        ZipFile(output).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(listOf("Meeting.opus", "meeting (2).opus", "Audio.wav"), entries.map { it.name })
            entries.forEach { assertEquals(ZipEntry.STORED, it.method) }
            assertArrayEquals(first.readBytes(), zip.getInputStream(entries[0]).readBytes())
            assertArrayEquals(second.readBytes(), zip.getInputStream(entries[2]).readBytes())
        }
    }

    private suspend fun rejected(block: suspend () -> Unit) {
        try {
            block()
            error("Unsafe archive accepted")
        } catch (_: IOException) { /* Expected bound or malformed archive. */ }
    }

    private fun archive(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
