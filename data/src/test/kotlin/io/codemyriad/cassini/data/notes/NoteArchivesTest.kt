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
        assertEquals(ImportSummary(imported = 1, duplicates = 1, unsupported = 2), summary)
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }

    @Test fun unsafePathsAreRefusedWithoutWritingOutsideScratch() = runTest {
        val unsafe = listOf(
            "../outside.opus",
            "/absolute.opus",
            "a/../../outside.opus",
            "a\\..\\outside.opus",
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
        refuses(ArchiveFailure.UNSAFE_PATHS, unicodePathZip("../outside.opus", "Meeting.opus"))
        refuses(ArchiveFailure.UNSAFE_PATHS, unicodePathZip("Meeting.opus", "../outside.opus"))
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

    @Test fun aLateUnsafeOrEncryptedEntryPreventsAllImports() = runTest {
        refuses(ArchiveFailure.UNSAFE_PATHS, zipBytes("good.opus" to "audio", "../bad.opus" to "audio"))
        val original = zipBytes("good.opus" to "audio", "bad.opus" to "audio")
        for (header in listOf(0x02014b50, 0x04034b50)) {
            val encrypted = original.copyOf()
            encrypted.putShort(encrypted.recordOffsets(header).last() + if (header == 0x02014b50) 8 else 6, 1)
            refuses(ArchiveFailure.ENCRYPTED, encrypted)
        }
    }

    @Test fun limitsMatchD96AndZip64EntriesAreRead() = runTest {
        assertEquals(2_000, ArchiveLimits().entries)
        assertEquals(20_000_000_000L, ArchiveLimits().expandedBytes)
        assertEquals(100, ArchiveLimits().ratio)
        val directory = folder.newFile().apply { writeBytes(zip64Bytes()) }
        assertEquals(1, ZipDirectory.inspect(directory, ArchiveLimits()).size)
        val result = NoteArchives().read(
            ByteArrayInputStream(directory.readBytes()),
            folder.newFolder(),
        ) { file, name ->
            assertEquals("Audio", file.readText())
            assertEquals("Meeting.opus", name)
            ImportOutcome.Created(name)
        }
        assertEquals(ImportSummary(imported = 1), result)
        val boundary = folder.newFile().apply { writeBytes(zipBytes(*Array(2_000) { "note-$it.opus" to "audio" })) }
        assertEquals(2_000, ZipDirectory.inspect(boundary, ArchiveLimits()).size)
        val many = Array(2_001) { "note-$it.opus" to "audio" }
        refuses(ArchiveFailure.TOO_LARGE, zipBytes(*many))
    }

    @Test fun emptyAndDamagedArchivesHaveDistinctReasons() = runTest {
        refuses(ArchiveFailure.EMPTY, zipBytes())
        refuses(ArchiveFailure.EMPTY, zipBytes("nested.zip" to "ignored", "readme.txt" to "ignored"))
        refuses(ArchiveFailure.DAMAGED, "This is not ZIP".toByteArray())
        val damaged = zipBytes("Meeting.opus" to "audio").dropLast(10).toByteArray()
        refuses(ArchiveFailure.DAMAGED, damaged)
    }

    @Test fun ratiosAreCheckedAgainstDirectoryAndActualInflaterConsumption() = runTest {
        val source = zipBytes("Meeting.opus" to "x".repeat(20_000))
        refuses(ArchiveFailure.TOO_LARGE, source)
        val central = source.recordOffsets(0x02014b50).single()
        val padded = source.copyOfRange(0, central) + ByteArray(20_001) + source.copyOfRange(central, source.size)
        val newCentral = central + 20_001
        padded.putInt(newCentral + 20, 20_001)
        padded.putInt(padded.recordOffsets(0x06054b50).last() + 16, newCentral)
        refuses(ArchiveFailure.TOO_LARGE, padded)
    }

    @Test fun actualExpandedBytesCannotBypassAForgedDirectorySize() = runTest {
        val source = zipBytes("Meeting.opus" to "x".repeat(20_000))
        source.putInt(source.recordOffsets(0x02014b50).single() + 24, 8)
        refuses(ArchiveFailure.TOO_LARGE, source, ArchiveLimits(expandedBytes = 100))
    }

    @Test fun anOversizedOrDamagedSingleEntryDoesNotRejectTheOtherNotes() = runTest {
        val source = zipBytes("large.opus" to "too large", "good.opus" to "ok", stored = true)
        val result = NoteArchives(ArchiveLimits(fileBytes = 3)).read(
            ByteArrayInputStream(source),
            folder.newFolder(),
        ) { _, name ->
            assertEquals("good.opus", name)
            ImportOutcome.Created(name)
        }
        assertEquals(ImportSummary(imported = 1, failed = 1), result)
        val damaged = zipBytes("bad.opus" to "bad", "good.opus" to "ok", stored = true)
        damaged[30 + "bad.opus".length] = '!'.code.toByte()
        val repaired = NoteArchives().read(ByteArrayInputStream(damaged), folder.newFolder()) { _, name ->
            assertEquals("good.opus", name)
            ImportOutcome.Created(name)
        }
        assertEquals(ImportSummary(imported = 1, failed = 1), repaired)
    }

    @Test fun relativeWindowsPathsAreReducedToTheirBaseName() = runTest {
        val result = NoteArchives().read(
            ByteArrayInputStream(zipBytes("folder\\Meeting.opus" to "audio")),
            folder.newFolder(),
        ) { _, name ->
            assertEquals("Meeting.opus", name)
            ImportOutcome.Created(name)
        }
        assertEquals(ImportSummary(imported = 1), result)
    }

    private suspend fun refuses(reason: ArchiveFailure, bytes: ByteArray, limits: ArchiveLimits = ArchiveLimits()) {
        val scratch = folder.newFolder()
        var consumed = 0
        try {
            NoteArchives(limits).read(ByteArrayInputStream(bytes), scratch) { _, name ->
                consumed++
                ImportOutcome.Created(name)
            }
            error("Rejected archive was accepted")
        } catch (error: ArchiveRejected) {
            assertEquals(reason, error.reason)
        }
        assertEquals("Whole-archive rejection occurs before any import", 0, consumed)
        assertTrue("No private staging remains", scratch.listFiles().orEmpty().isEmpty())
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
