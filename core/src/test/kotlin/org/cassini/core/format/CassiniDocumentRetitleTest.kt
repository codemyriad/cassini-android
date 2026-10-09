package org.cassini.core.format

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CassiniDocumentRetitleTest {
    @get:Rule val folder = TemporaryFolder()

    private val transcript = Transcript(
        listOf(Word("spk_1", 0, 20, "Ciao"), Word("spk_2", 20, 40, "Salve")),
        "it",
    )

    /** One packet of Opus silence behind [tags], as in CassiniDocumentTest. */
    private fun silence(tags: List<String>): ByteArray {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0, 0, -128, -69, 0, 0, 0, 0, 0)
        val packet = CassiniDocument.tagsPacket(tags)
        return OggOpus.mux(OggOpus.Stream(head, packet, listOf(byteArrayOf(-8, -1, -2)), 960, true), packet)
    }

    private fun document(
        sourceTags: List<String> = listOf("X_CUSTOM=keep=me", "ARTIST=Anna"),
        title: String = "Riunione",
    ): File {
        val processing = JSONObject().put("engine", "sherpa-onnx")
        val bytes = CassiniDocument.create(silence(sourceTags), transcript, title, processing)
        return File(folder.root, "source.opus").apply { writeBytes(bytes) }
    }

    private fun retitle(source: File, title: String, existing: CassiniDocument = CassiniDocument.read(source)) =
        CassiniDocument.retitle(source, existing, title, File(folder.root, "target.opus"))

    private fun titleTags(doc: CassiniDocument) = doc.comments.filter { it.substringBefore('=').equals("TITLE", true) }

    private fun manifestTag(comment: String) = comment.startsWith("CASSINI_PAYLOAD_") &&
        comment.substringBefore('=') !in setOf("CASSINI_PAYLOAD_SCHEMA", "CASSINI_PAYLOAD_MIME")

    @Test fun theTitleChangesInTheManifestAndTheTag() {
        val source = document()
        val before = CassiniDocument.read(source)
        assertEquals(listOf("TITLE=Riunione"), titleTags(before))
        val after = retitle(source, "Standup del lunedì")
        assertEquals("ok", after.state)
        assertTrue(after.warnings.isEmpty())
        assertEquals("Standup del lunedì", after.title)
        assertEquals(listOf("TITLE=Standup del lunedì"), titleTags(after))
    }

    @Test fun theAudioAndItsDigestAreUnchanged() {
        val source = document()
        val before = CassiniDocument.read(source)
        val after = retitle(source, "Nuovo titolo")
        val target = File(folder.root, "target.opus")
        assertEquals(OggOpus.read(source.readBytes()).digest(), OggOpus.read(target.readBytes()).digest())
        val integrity = { doc: CassiniDocument -> doc.manifest!!.getJSONObject("integrity").toString() }
        assertEquals(integrity(before), integrity(after))
        val audio = { doc: CassiniDocument -> doc.manifest!!.getJSONObject("audio").toString() }
        assertEquals(audio(before), audio(after))
    }

    @Test fun everythingButTheTitleIsKept() {
        val source = document()
        val base = CassiniDocument.read(source)
        base.manifest!!.put("x", JSONObject().put("keep", true))
        base.manifest.getJSONArray("speakers").getJSONObject(0).put("pronouns", "she")
        val bytes = CassiniDocument.create(source.readBytes(), transcript, "Ignored", JSONObject(), base)
        val extended = File(folder.root, "extended.opus").apply { writeBytes(bytes) }
        val before = CassiniDocument.read(extended)
        val after = retitle(extended, "Altro")
        assertEquals(before.variants.map { it.id to it.body }, after.variants.map { it.id to it.body })
        assertEquals(before.defaultId, after.defaultId)
        val expected = before.comments.filterNot(::manifestTag)
            .map { if (it.startsWith("TITLE=")) "TITLE=Altro" else it }
        assertEquals(expected, after.comments.filterNot(::manifestTag))
        assertTrue(after.comments.contains("X_CUSTOM=keep=me"))
        assertTrue(after.comments.contains("ARTIST=Anna"))
        before.manifest!!.getJSONObject("meeting").put("title", "Altro")
        assertEquals(before.manifest.toString(), after.manifest.toString())
    }

    @Test fun accentsAndQuotesAreKeptExactlyAndTheTitleIsTrimmed() {
        val title = "Riunione “caffè” con l'équipe \"Q3\" — 日本 = ok"
        val after = retitle(document(), "  $title \n")
        assertEquals(title, after.title)
        assertEquals(listOf("TITLE=$title"), titleTags(after))
    }

    @Test fun anImportedTitleTagThatDiffersEndsEqualToTheManifest() {
        // A fresh seal keeps a TITLE the source already had, so the tag and meeting.title can differ.
        val tags = listOf("ALBUM=Calls", "TITLE=voice-memo-0412", "X_CUSTOM=keep=me")
        val source = document(tags, title = "Call with Luca")
        val before = CassiniDocument.read(source)
        assertEquals("Call with Luca", before.title)
        assertEquals(listOf("TITLE=voice-memo-0412"), titleTags(before))
        val after = retitle(source, "Call with Luca")
        assertEquals("Call with Luca", after.title)
        assertEquals(listOf("TITLE=Call with Luca"), titleTags(after))
        // The tag stays where it was.
        assertEquals(listOf("ALBUM=Calls", "TITLE=Call with Luca", "X_CUSTOM=keep=me"), after.comments.take(3))
    }

    @Test fun extraTitleTagsAreDroppedAndAMissingOneIsAdded() {
        val source = document(listOf("TITLE=one", "title=two", "X_CUSTOM=keep=me"))
        val after = retitle(source, "Uno")
        assertEquals(listOf("TITLE=Uno"), titleTags(after))
        val read = CassiniDocument.read(source)
        val untitled = read.copy(comments = read.comments.filterNot { it.substringBefore('=').equals("TITLE", true) })
        val added = retitle(source, "Due", untitled)
        assertEquals(listOf("TITLE=Due"), titleTags(added))
        assertEquals("Due", added.title)
    }

    @Test fun aBlankTitleOrADocumentNotOkIsRefusedAndWritesNothing() {
        val source = document()
        for (attempt in listOf<() -> Unit>(
            { retitle(source, "  ") },
            { retitle(source, "Titolo", CassiniDocument.read(source, verify = false)) },
            { retitle(source, "Titolo", CassiniDocument("plain-audio")) },
        )) {
            try {
                attempt()
                fail("Expected a refusal")
            } catch (_: IllegalArgumentException) {
            }
            assertFalse(File(folder.root, "target.opus").exists())
        }
    }

    @Test fun theConformanceReaderAcceptsTheResult() {
        val after = retitle(document(), "Verifica")
        val reread = CassiniDocument.readChecked(File(folder.root, "target.opus"))
        assertEquals(DocumentState.OK, reread.documentState)
        assertTrue(reread.warnings.isEmpty())
        assertEquals(listOf("words"), reread.variants.map { it.id })
        assertEquals(transcript, reread.selected(null)?.transcript)
        assertEquals(after.manifest.toString(), reread.manifest.toString())
        // A relabel after a retitle keeps the new title.
        val relabelled = CassiniDocument.relabel(
            File(folder.root, "target.opus"),
            reread,
            mapOf("spk_1" to "Anna"),
            File(folder.root, "c.opus"),
        )
        assertEquals("Verifica", relabelled.title)
        assertEquals(listOf("TITLE=Verifica"), titleTags(relabelled))
    }

    @Test fun aConformanceVectorWithUnknownMembersKeepsThem() {
        val directory = System.getProperty("cassini.conformance", "../third_party/cassini-format/spec/conformance")!!
        val vector = File(directory, "opus/014-unknown-manifest-member.opus")
        assertTrue("Cassini conformance vector is missing: $vector. Run git submodule update --init.", vector.isFile)
        val source = File(folder.root, "vector.opus").apply { writeBytes(vector.readBytes()) }
        val before = CassiniDocument.read(source)
        val after = retitle(source, "Renamed vector")
        assertEquals("ok", after.state)
        assertEquals("Renamed vector", after.title)
        assertTrue(after.manifest!!.has("cassiniFutureField"))
        assertEquals(before.variants.map { it.id to it.body }, after.variants.map { it.id to it.body })
        assertEquals(listOf("TITLE=Renamed vector"), titleTags(after))
    }
}
