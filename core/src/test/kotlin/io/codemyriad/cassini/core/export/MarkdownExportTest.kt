package io.codemyriad.cassini.core.export

import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

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
                input.getLong("startMs"),
                ZoneId.of(input.getString("tz")),
                Locale.forLanguageTag(language),
                locale,
                is24Hour = locale != Locale.US,
                duration = duration?.let { "${it / 60_000} min" },
            )
            val expected = case.getString("expected").replace("{DATE_LINE}", date)
            val transcript = ExportTranscript(input.getString("title"), duration ?: 0, words, labels)
            val actual = MarkdownExport.write(transcript, date, copy(language), personal)
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
            ExportTranscript("Review", 10_000, words, mapOf("ada" to "Ada", "ben" to "Ben")),
            "",
            copy("en"),
        )
        assertTrue(actual.contains("Speakers: Ada, Ben\n"))
        assertTrue(
            actual.contains(
                "The release candidate is ready for testing, (Ben: Sounds good.) and I will send the download link after this call.",
            ),
        )
        assertEquals(1, Regex("Sounds good\\.").findAll(actual).count())
        assertFalse(actual.contains("**Ben**"))
    }

    @Test fun unassignedLabelsAreUsedInInventoryHeadersOverlapAndInlineReplies() {
        val covered = mutableSetOf<String>()
        for (fixture in listOf("acknowledgment", "competing", "overlapping-turns")) {
            val original = Transcript.fromJson(interjection("$fixture.words.json")).words
            for (speaker in original.map { it.speaker }.distinct()) {
                val words = original.map { if (it.speaker == speaker) it.copy(speaker = "unassigned") else it }
                for (label in listOf("Voce sconosciuta", "Mira")) {
                    assertUnassignedLabels(words, label, covered)
                }
            }
        }
        assertEquals(setOf("header", "overlap", "inline"), covered)
    }

    private fun assertUnassignedLabels(words: List<io.codemyriad.cassini.core.format.Word>, label: String, covered: MutableSet<String>) {
        val labels = words.associate { it.speaker to if (it.speaker == "unassigned") label else it.speaker }
        val markdown = MarkdownExport.write(ExportTranscript("Meeting", 0, words, labels), "", copy("en"))
        assertTrue(markdown.substringBefore("## Transcript").contains(label))
        for (row in ReadingTurns.of(words).rows) {
            if (row.speaker == "unassigned" && !row.continuation) {
                covered += "header"
                assertTrue(markdown, "**$label**" in markdown)
            }
            if ("unassigned" in row.over) {
                covered += "overlap"
                val names = copy("en").speakerList(row.over.map { labels.getValue(it) }.distinct())
                assertTrue(markdown, copy("en").overLine.replace("{names}", names) in markdown)
            }
            if (row.members.any { it.interjectionSpeaker == "unassigned" }) {
                covered += "inline"
                assertTrue(markdown, "($label:" in markdown)
            }
        }
        assertFalse(markdown, "unassigned" in markdown)
    }

    @Test fun allSixD84VectorsKeepTheExpectedParagraphsHeadersAndInlineReplies() {
        val fixtures = listOf(
            "acknowledgment",
            "brief-reply",
            "competing",
            "monologue",
            "overlapping-turns",
            "seam-gap",
        )
        for (fixture in fixtures) {
            val words = Transcript.fromJson(interjection("$fixture.words.json")).words
            val expected = JSONObject(interjection("$fixture.expected.json")).getJSONArray("rows")
            val labels = words.associate { it.speaker to it.speaker }
            val markdown = MarkdownExport.write(ExportTranscript(fixture, 0, words, labels), "", copy("en"))
            val blocks = markdown.substringAfter("## Transcript\n\n").trimEnd().split("\n\n")
            assertEquals(fixture, expected.length(), blocks.size)
            for (index in 0 until expected.length()) {
                val row = expected.getJSONObject(index)
                val members = row.getJSONArray("members")
                val text = (0 until members.length()).joinToString(" ") {
                    val member = members.getJSONObject(it)
                    val stored = member.getString("text")
                    // These pinned fixtures contain no Markdown metacharacters; S-5 covers escaping separately.
                    assertFalse(stored.any { c -> c in "\\`*_[]<>" })
                    if (member.getString("kind") == "interjection") {
                        "(${member.getString("speaker")}: $stored)"
                    } else {
                        stored
                    }
                }
                val seconds = row.getLong("startMs") / 1000
                val stamp = String.format(
                    Locale.ROOT,
                    "%02d:%02d:%02d",
                    seconds / 3600,
                    seconds / 60 % 60,
                    seconds % 60,
                )
                val name = if (row.getBoolean("continuation")) "" else "**${row.getString("speaker")}** · "
                val over = row.getJSONArray("over")
                val names = (0 until over.length()).map(over::getString)
                val joined = if (names.size < 2) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()
                val suffix = if (names.isEmpty()) "" else " (over $joined)"
                assertEquals("$fixture row$index", "$name$stamp$suffix\n$text", blocks[index])
            }
        }
    }

    @Test fun escapingAndUnicodeSafeNamesFollowS5() {
        assertEquals("\\# heading", MarkdownExport.escape(" #   heading "))
        assertEquals("123\\. item", MarkdownExport.escape("123. item"))
        assertEquals(
            "\\- \\_one\\_ \\[two\\] \\<three\\> \\\\ \\`four\\`",
            MarkdownExport.escape("- _one_ [two] <three> \\ `four`"),
        )
        assertEquals("A-B-C.md", MarkdownExport.filename(" A/B\\C ", "Untitled meeting"))
        assertEquals("Untitled meeting.md", MarkdownExport.filename("  ", "Untitled meeting"))
        val name = MarkdownExport.filename("😀".repeat(121), "Untitled meeting").removeSuffix(".md")
        assertEquals(57, name.codePointCount(0, name.length))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 230)
        assertEquals("a".repeat(120) + ".md", MarkdownExport.filename("a".repeat(121), "Untitled meeting"))
    }

    @Test fun languageAndClockLocaleAreIndependentWithoutGlobalMutation() {
        val original = Locale.getDefault()
        val date = MarkdownDate.line(
            1793088000000,
            ZoneId.of("Europe/Rome"),
            Locale.FRENCH,
            Locale.US,
            false,
            null,
        )
        assertTrue(date.startsWith("mardi 27 octobre 2026, "))
        assertTrue(date.endsWith("9:00 AM"))
        assertEquals(original, Locale.getDefault())
    }

    @Test fun androidHeadingsAndLabelsMatchAllSixPinnedTranslations() {
        val ids = mapOf(
            "ui_actions_markdown" to "export.markdown.label",
            "ui_markdown_description" to "export.markdown.description",
            "ui_markdown_speakers" to "export.markdown.speakersLine",
            "ui_markdown_transcript" to "export.markdown.transcriptHeading",
            "ui_markdown_notes" to "export.markdown.notesHeading",
            "ui_markdown_over" to "meeting.turn.over",
            "ui_meeting_duration_hours" to "format.meetingDuration.hours",
            "ui_meeting_duration_minutes" to "format.meetingDuration.minutes",
            "ui_duration_under_minute" to "format.meetingDuration.underMinute",
        )
        val catalog = JSONObject(resource("strings.json")).getJSONArray("entries")
        val entries = (0 until catalog.length()).map(catalog::getJSONObject).associateBy { it.getString("id") }
        val languages = mapOf(
            "values" to "en",
            "values-it" to "it",
            "values-fr" to "fr",
            "values-es" to "es",
            "values-pt-rBR" to "pt-BR",
            "values-pt-rPT" to "pt-PT",
        )
        for ((directory, language) in languages) {
            val file = File(System.getProperty("cassini.appResources"), "$directory/strings.xml")
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val elements = document.getElementsByTagName("string")
            val strings = (0 until elements.length).associate {
                val element = elements.item(it) as org.w3c.dom.Element
                element.getAttribute("name") to element.textContent.replace("\\'", "'")
            }
            for ((name, id) in ids) {
                val entry = entries.getValue(id)
                val expected = if (language == "en") {
                    entry.getString("en")
                } else {
                    entry.getJSONObject("l10n").getJSONObject(language).getString("text")
                }
                assertEquals("$language/$id", expected, strings[name])
            }
        }
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

    private fun interjection(name: String): String =
        requireNotNull(javaClass.getResource("/interjections/$name")).readText()

    private fun resource(name: String): String = requireNotNull(javaClass.getResource("/markdown/$name")).readText()

    private fun copy(language: String): MarkdownCopy {
        val entries = JSONObject(resource("strings.json")).getJSONArray("entries")
        fun text(id: String): String {
            val entry = (0 until entries.length()).map(entries::getJSONObject).single { it.getString("id") == id }
            return if (language == "en") {
                entry.getString("en")
            } else {
                entry.getJSONObject("l10n").getJSONObject(language).getString("text")
            }
        }
        return MarkdownCopy(
            text("export.markdown.speakersLine"),
            text("export.markdown.transcriptHeading"),
            text("export.markdown.notesHeading"),
            text("meeting.turn.over"),
        ) { labels ->
            if (labels.size < 2) labels.joinToString() else labels.dropLast(1).joinToString(", ") + " and " + labels.last()
        }
    }
}
