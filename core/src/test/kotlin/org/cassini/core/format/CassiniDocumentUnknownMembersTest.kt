package org.cassini.core.format

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Every write of a document keeps what this app does not know, verbatim (coordination D47, D56): a speaker's
 * `voiceSource`, which only a diarizing writer sets, any other member of a speaker, of `meeting` and of the manifest,
 * and `x`. Each writer changes only its own fields. A new transcription adds its speakers under fresh ids and never
 * stamps them, so an earlier stamp never lands on another person.
 */
class CassiniDocumentUnknownMembersTest {
    @get:Rule val folder = TemporaryFolder()

    private val first = Transcript(listOf(Word("diar_a_1", 0, 20, "Ciao"), Word("diar_a_2", 20, 40, "Salve")), "it")

    private val stamp = JSONObject().put("inputSHA256", "a".repeat(64)).put("turnsSHA256", "b".repeat(64))

    /** One packet of Opus silence, as in CassiniDocumentTest. */
    private fun silence(): ByteArray {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0, 0, -128, -69, 0, 0, 0, 0, 0)
        val packet = CassiniDocument.tagsPacket(emptyList())
        return OggOpus.mux(OggOpus.Stream(head, packet, listOf(byteArrayOf(-8, -1, -2)), 960, true), packet)
    }

    /**
     * A document with two versions, written by another app: a stamped speaker with a member of a later format, and
     * unknown members in `meeting`, at the top level and in `x`.
     */
    private fun foreign(): File {
        val audio = silence()
        val plain = CassiniDocument.read(CassiniDocument.create(audio, first, "Riunione", JSONObject()))
        val manifest = plain.manifest!!
        manifest.getJSONArray("speakers").getJSONObject(0)
            .put("voiceSource", stamp)
            .put("laterMember", JSONArray().put(1).put("two"))
        manifest.getJSONObject("meeting").put("laterMeetingMember", JSONObject().put("nested", true))
        manifest.put("laterTopLevel", "kept").put("x", JSONObject().put("org.example", JSONObject().put("keep", true)))
        val bytes = CassiniDocument.create(audio, first, "Ignored", JSONObject(), plain)
        return File(folder.root, "foreign.opus").apply { writeBytes(bytes) }
    }

    private fun target(name: String) = File(folder.root, name)

    /** [before]'s manifest after [change] must be [after]'s, member for member. */
    private fun assertOnly(before: CassiniDocument, after: CassiniDocument, change: (JSONObject) -> Unit) {
        val expected = JSONObject(before.manifest!!.toString()).also(change)
        assertEquals(expected.toString(), after.manifest!!.toString())
    }

    private fun speakers(document: CassiniDocument): List<JSONObject> {
        val list = document.manifest!!.getJSONArray("speakers")
        return (0 until list.length()).map(list::getJSONObject)
    }

    @Test fun theForeignDocumentCarriesTheStamp() {
        val stamped = speakers(CassiniDocument.read(foreign())).first()
        assertEquals(stamp.toString(), stamped.getJSONObject("voiceSource").toString())
    }

    @Test fun aRenameChangesOnlyTheLabel() {
        val source = foreign()
        val before = CassiniDocument.read(source)
        val after = CassiniDocument.relabel(source, before, mapOf("diar_a_1" to "Anna"), target("renamed.opus"))
        assertOnly(before, after) { it.getJSONArray("speakers").getJSONObject(0).put("label", "Anna") }
    }

    @Test fun aNewTitleChangesOnlyTheTitle() {
        val source = foreign()
        val before = CassiniDocument.read(source)
        val after = CassiniDocument.retitle(source, before, "Standup", target("retitled.opus"))
        assertOnly(before, after) { it.getJSONObject("meeting").put("title", "Standup") }
    }

    @Test fun choosingAVersionChangesOnlyTheDefaultFlags() {
        val source = foreign()
        val before = CassiniDocument.read(source)
        val earlier = before.variants.first { it.id != before.defaultId }.id
        val after = CassiniDocument.setDefault(source, before, earlier, target("default.opus"))
        assertOnly(before, after) { manifest ->
            val descriptors = manifest.getJSONArray("transcripts")
            for (i in 0 until descriptors.length()) {
                val descriptor = descriptors.getJSONObject(i)
                descriptor.put("default", descriptor.getString("id") == earlier)
            }
        }
    }

    /** As a finished transcription or a speaker merge writes: a new version over the document. */
    @Test fun aNewVersionKeepsEverySpeakerAsItIsAndStampsNoNewOne() {
        val source = foreign()
        val before = CassiniDocument.read(source)
        val words = listOf(Word("diar_b_1", 0, 20, "Ciao"), Word("diar_a_1", 20, 40, "Salve"))
        val again = Transcript(words, "it")
        val after = CassiniDocument.create(
            source,
            again,
            "Ignored",
            JSONObject(),
            before,
            emptyMap(),
            target("v3.opus"),
        )

        val kept = speakers(before)
        assertEquals(kept.map(JSONObject::toString), speakers(after).take(kept.size).map(JSONObject::toString))
        val added = speakers(after).drop(kept.size)
        assertEquals(listOf("diar_b_1"), added.map { it.getString("id") })
        assertFalse("a writer that does not compute the stamp never invents it", added.single().has("voiceSource"))

        val unchanged = listOf("meeting", "laterTopLevel", "x", "audio", "integrity")
        for (member in unchanged) {
            assertEquals(member, before.manifest!!.get(member).toString(), after.manifest!!.get(member).toString())
        }
        assertNotEquals(before.defaultId, after.defaultId)
    }
}
