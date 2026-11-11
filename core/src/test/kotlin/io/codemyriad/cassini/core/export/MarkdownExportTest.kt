package io.codemyriad.cassini.core.export

import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Locale

class MarkdownExportTest {
    @Test fun sharedS5CasesMatchExactly() {
        val cases = JSONObject(resource("markdown-cases.json")).getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val input = case.getJSONObject("input")
            val items = input.getJSONArray("items")
            val words = (0 until items.length()).map {
                val item = items.getJSONObject(it)
                Word(item.getString("speaker"), item.getLong("startMs"), item.getLong("endMs"), item.getString("text"))
            }
            val speakers = input.getJSONArray("speakers")
            val labels = (0 until speakers.length()).associate {
                speakers.getJSONObject(it).let { speaker -> speaker.getString("id") to speaker.getString("name") }
            }
            val notes = input.getJSONArray("notes")
            val personal = (0 until notes.length()).map {
                notes.getJSONObject(it).let { note -> MarkdownNote(note.getLong("atMs"), note.getString("text")) }
            }
            val duration = if (input.isNull("durationMs")) null else input.getLong("durationMs")
            val locale = Locale.forLanguageTag(input.getString("formattingLocale").replace('_', '-'))
            val language = input.getString("uiLanguage")
            val date = MarkdownDate.line(
                input.getLong("startMs"), ZoneId.of(input.getString("tz")), Locale.forLanguageTag(language), locale,
                is24Hour = locale != Locale.US, duration = duration?.let { "${it / 60_000} min" },
            )
            val expected = case.getString("expected").replace("{DATE_LINE}", date)
            val actual = MarkdownExport.write(
                ExportTranscript(input.getString("title"), duration ?: 0, words, labels), date, copy(language), personal,
            )
            assertEquals(case.getString("name"), expected, actual)
            assertFalse(actual.startsWith("\uFEFF"))
            assertFalse(actual.endsWith("\n\n"))
        }
    }

    @Test fun chipOnlySpeakerAndStoredWordsAreExportedExactlyOnce() {
        val words = Transcript.fromJson(
            requireNotNull(javaClass.getResource("/interjections/acknowledgment.words.json")).readText(),
        ).words
        val actual = MarkdownExport.write(
            ExportTranscript("Review", 10_000, words, mapOf("ada" to "Ada", "ben" to "Ben")), "", copy("en"),
        )
        assertTrue(actual.contains("Speakers: Ada, Ben\n"))
        assertTrue(actual.contains("The release candidate is ready for testing, (Ben: Sounds good.) and I will send the download link after this call."))
        assertEquals(1, Regex("Sounds good\\.").findAll(actual).count())
        assertFalse(actual.contains("**Ben**"))
    }

    @Test fun escapingAndUnicodeSafeNamesFollowS5() {
        assertEquals("\\# heading", MarkdownExport.escape(" #   heading "))
        assertEquals("123\\. item", MarkdownExport.escape("123. item"))
        assertEquals("\\- \\_one\\_ \\[two\\] \\<three\\> \\\\ \\`four\\`", MarkdownExport.escape("- _one_ [two] <three> \\ `four`"))
        assertEquals("A-B-C.md", MarkdownExport.filename(" A/B\\C ", "Untitled meeting"))
        assertEquals("Untitled meeting.md", MarkdownExport.filename("  ", "Untitled meeting"))
        val name = MarkdownExport.filename("😀".repeat(121), "Untitled meeting").removeSuffix(".md")
        assertEquals(120, name.codePointCount(0, name.length))
    }

    @Test fun languageAndClockLocaleAreIndependentWithoutGlobalMutation() {
        val original = Locale.getDefault()
        val date = MarkdownDate.line(1793088000000, ZoneId.of("Europe/Rome"), Locale.FRENCH, Locale.US, false, null)
        assertTrue(date.startsWith("mardi 27 octobre 2026, "))
        assertTrue(date.endsWith("9:00 AM"))
        assertEquals(original, Locale.getDefault())
    }

    @Test fun vendoredFilesMatchTheirCassiniUxPin() {
        val pin = JSONObject(resource("UPSTREAM.json"))
        assertEquals("https://github.com/codemyriad/cassini-ux", pin.getString("repository"))
        val files = pin.getJSONObject("files")
        for (name in files.keys()) {
            val bytes = requireNotNull(javaClass.getResourceAsStream("/markdown/$name")).use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(name, files.getJSONObject(name).getString("sha256"), digest)
        }
    }

    private fun resource(name: String): String = requireNotNull(javaClass.getResource("/markdown/$name")).readText()

    private fun copy(language: String): MarkdownCopy {
        val entries = JSONObject(resource("strings.json")).getJSONArray("entries")
        fun text(id: String): String {
            val entry = (0 until entries.length()).map(entries::getJSONObject).single { it.getString("id") == id }
            return if (language == "en") entry.getString("en") else entry.getJSONObject("l10n").getJSONObject(language).getString("text")
        }
        return MarkdownCopy(
            text("export.markdown.speakersLine"), text("export.markdown.transcriptHeading"),
            text("export.markdown.notesHeading"), text("meeting.turn.over"),
        ) { labels -> labels.dropLast(1).joinToString(", ") + " and " + labels.last() }
    }
}
