package io.codemyriad.cassini.data.notes

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Random
import java.util.zip.Inflater

/** Refusals must occur before consume: it publishes a note that the user can immediately edit. */
class ArchiveAdmissionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun ignoredMembersCannotHideForgedSizeOrRatioBombsEvenWithoutAudio() = runTest {
        for (name in listOf("ignored.txt", "__MACOSX/._Meeting.opus", "nested.zip")) {
            for (withAudio in listOf(false, true)) {
                val entries = listOfNotNull(("good.opus" to "audio").takeIf { withAudio }, name to "x".repeat(200_000))
                val bytes = zipBytes(*entries.toTypedArray())
                bytes.putInt(bytes.recordOffsets(0x02014b50).last() + 24, 8)
                refuses(bytes, ArchiveFailure.TOO_LARGE)
                refuses(bytes, ArchiveFailure.TOO_LARGE, ArchiveLimits(expandedBytes = 20_000, ratio = Int.MAX_VALUE))
            }
        }
    }

    @Test fun directoryPayloadRefusesTheArchiveEvenWithAForgedZeroSize() = runTest {
        for (forged in listOf(false, true)) {
            val bytes = zipBytes("good.opus" to "audio", "folder/" to "hidden payload")
            if (forged) bytes.putInt(bytes.recordOffsets(0x02014b50).last() + 24, 0)
            refuses(bytes, ArchiveFailure.DAMAGED)
        }
        val result = NoteArchives().read(
            zipBytes("folder/" to "", "good.opus" to "audio").inputStream(),
            folder.newFolder(),
        ) { _, name -> ImportOutcome.Created(name) }
        assertEquals(ImportSummary(imported = 1), result)
    }

    @Test fun directoryAttributesCannotHideAPayloadBehindAnAudioFilename() = runTest {
        for (attributes in listOf(0x10, 0x41ed0000)) {
            for (forged in listOf(false, true)) {
                val bytes = zipBytes("good.opus" to "audio", "folder.opus" to "hidden payload")
                val central = bytes.recordOffsets(0x02014b50).last()
                bytes.putInt(central + 38, attributes)
                if (forged) bytes.putInt(central + 24, 0)
                refuses(bytes, ArchiveFailure.DAMAGED)
            }
        }
    }

    @Test fun compressionMethodMismatchInALateLocalHeaderRefusesBeforeAnyImport() = runTest {
        val bytes = zipBytes("good.opus" to "audio", "other.opus" to "other audio")
        bytes.putShort(bytes.recordOffsets(0x04034b50).last() + 8, 0)
        refuses(bytes, ArchiveFailure.DAMAGED)
    }

    @Test fun ratioUsesCompletedConsumptionAndAcceptsAMixedCompressibilityMember() = runTest {
        val random = Random(1)
        val body = "x".repeat(200_000) + CharArray(20_000) { (' '.code + random.nextInt(90)).toChar() }.concatToString()
        val bytes = zipBytes("Meeting.opus" to body)
        val inflater = Inflater(true)
        try {
            val localNameBytes = "Meeting.opus".toByteArray().size
            inflater.setInput(bytes, 30 + localNameBytes, bytes.size - 30 - localNameBytes)
            var expanded = 0L
            var highPrefixRatio = false
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                expanded += inflater.inflate(buffer)
                highPrefixRatio = highPrefixRatio || overRatio(expanded, inflater.bytesRead, 100)
            }
            assertTrue("The valid member has a prefix above 100:1", highPrefixRatio)
            assertTrue("The completed member is below 100:1", !overRatio(expanded, inflater.bytesRead, 100))
        } finally {
            inflater.end()
        }
        val result = NoteArchives().read(bytes.inputStream(), folder.newFolder()) { file, name ->
            assertEquals(body, file.readText())
            ImportOutcome.Created(name)
        }
        assertEquals(ImportSummary(imported = 1), result)
    }

    @Test fun runtimeRefusalOfALateSupportedMemberNeverPublishesAnEarlierNote() = runTest {
        val bytes = zipBytes("good.opus" to "audio", "late.opus" to "x".repeat(200_000))
        bytes.putInt(bytes.recordOffsets(0x02014b50).last() + 24, 8)
        refuses(bytes, ArchiveFailure.TOO_LARGE)
    }

    private suspend fun refuses(bytes: ByteArray, reason: ArchiveFailure, limits: ArchiveLimits = ArchiveLimits()) {
        val scratch = folder.newFolder()
        var published = 0
        try {
            NoteArchives(limits).read(bytes.inputStream(), scratch) { _, name ->
                published++
                ImportOutcome.Created(name)
            }
            error("Archive was accepted")
        } catch (error: ArchiveRejected) {
            assertEquals(reason, error.reason)
        }
        assertEquals("No visible/editable note exists before whole-archive admission", 0, published)
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }
}
