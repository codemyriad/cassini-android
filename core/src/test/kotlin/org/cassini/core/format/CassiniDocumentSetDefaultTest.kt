package org.cassini.core.format

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Choosing a version writes it into the file as the default (coordination D53, D53.3), so every Cassini app opens the
 * file, and any copy of it, on that version. The shared vector `two-versions-default-switched.opus` is conformance
 * vector 016 with the second version chosen, built by the format's own tools; Android must open it on that version
 * and write the same thing itself.
 */
class CassiniDocumentSetDefaultTest {
    @get:Rule val folder = TemporaryFolder()

    private val conformance = File(
        System.getProperty("cassini.conformance", "../third_party/cassini-format/spec/conformance")!!,
    )

    /** Conformance vector 016: "raw-asr" (default) and "second-pass", three words each. */
    private fun twoVersions(): File {
        val vector = File(conformance, "opus/016-two-transcripts.opus")
        assertTrue("Missing $vector: run git submodule update --init", vector.isFile)
        return vector.copyTo(File(folder.root, "016.opus"))
    }

    private fun sharedVector(): File {
        val stream = checkNotNull(javaClass.getResourceAsStream("/cross-app/two-versions-default-switched.opus"))
        return File(folder.root, "shared.opus").apply { writeBytes(stream.use { it.readBytes() }) }
    }

    private fun setDefault(source: File, variantId: String, name: String = "target.opus"): CassiniDocument =
        CassiniDocument.setDefault(source, CassiniDocument.read(source), variantId, File(folder.root, name))

    private fun manifestComment(comment: String) = comment.startsWith("CASSINI_PAYLOAD_") &&
        comment.substringBefore('=') != "CASSINI_PAYLOAD_SCHEMA"

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun theSharedVectorOpensOnTheChosenVersion() {
        val shared = sharedVector()
        assertEquals("d4bffa94eec9481f19668e53ee247f8e5a107402a0740094a0cc29b3252b9f6d", sha256(shared))
        val document = CassiniDocument.read(shared)
        assertEquals("ok", document.state)
        assertTrue(document.warnings.toString(), document.warnings.isEmpty())
        assertEquals(listOf("raw-asr", "second-pass"), document.variants.map { it.id })
        assertEquals("second-pass", document.defaultId)
        assertEquals(listOf("ONE", "TWO.", "THREE."), document.selected(null)!!.transcript!!.words.map { it.text })
    }

    @Test fun choosingTheSecondVersionWritesWhatTheSharedVectorHolds() {
        val source = twoVersions()
        val before = CassiniDocument.read(source)
        assertEquals("raw-asr", before.defaultId)

        val after = setDefault(source, "second-pass")
        assertEquals("ok", after.state)
        assertTrue(after.warnings.toString(), after.warnings.isEmpty())
        assertEquals("second-pass", after.defaultId)
        val bodies = { document: CassiniDocument -> document.variants.map { it.id to it.body } }
        assertEquals("the versions, their order and bodies stay", bodies(before), bodies(after))
        assertEquals(1, after.comments.count { it == "CASSINI_TRANSCRIPT_DEFAULT=second-pass" })

        // The same manifest (default flags included) and the same other tags as the independently built vector.
        val shared = CassiniDocument.read(sharedVector())
        assertEquals(canonical(shared.manifest), canonical(after.manifest))
        val otherTags = { document: CassiniDocument -> document.comments.filterNot(::manifestComment).toSet() }
        assertEquals(otherTags(shared), otherTags(after))
    }

    @Test fun choosingTheFirstVersionAgainRestoresIt() {
        val switched = File(folder.root, "switched.opus")
        setDefault(twoVersions(), "second-pass", switched.name)
        val back = setDefault(switched, "raw-asr", "back.opus")
        assertEquals("raw-asr", back.defaultId)
        val flags = (0 until back.manifest!!.getJSONArray("transcripts").length()).map {
            back.manifest.getJSONArray("transcripts").getJSONObject(it).optBoolean("default")
        }
        assertEquals("exactly one default", listOf(true, false), flags)
    }

    @Test fun theExtensionsStayAsTheyAre() {
        val audio = File(folder.root, "audio.opus").apply { writeBytes(silence()) }
        val notes = JSONArray().put(JSONObject().put("text", "Ricorda"))
        val x = JSONObject().put("gocassini.com", JSONObject().put("notes", notes))
            .put("codemyriad.io", JSONObject().put("future", JSONObject().put("kept", 1)))
        val words = Transcript(listOf(Word("s1", 0, 20, "Ciao")), "und")
        val first = File(folder.root, "first.opus")
        val one = CassiniDocument.create(audio, words, "Note", JSONObject(), null, emptyMap(), first, x)
        val second = File(folder.root, "second.opus")
        val again = Transcript(listOf(Word("s1", 0, 20, "Salve")), "und")
        val two = CassiniDocument.create(first, again, "Note", JSONObject(), one, emptyMap(), second)
        assertEquals(two.variants[1].id, two.defaultId)

        val after = setDefault(second, two.variants[0].id)
        assertEquals(two.variants[0].id, after.defaultId)
        assertEquals(canonical(x), canonical(after.manifest!!.getJSONObject("x")))
    }

    @Test fun anUnknownVersionIsRefusedAndNothingIsWritten() {
        val source = twoVersions()
        val refused = runCatching { setDefault(source, "ghost") }
        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
        assertFalse(File(folder.root, "target.opus").exists())
    }

    @Test fun theIndependentPythonReaderOpensTheWrittenFileOnTheChosenVersion() {
        // CassiniDocument.read checks inside setDefault, so a fault shared by Android's writer and reader would pass
        // the round trip above. The format's standard-library reader is a separate implementation.
        val tool = File(conformance, "../../tools/cassini-read-pure.py").canonicalFile
        assertTrue("Cassini format reader is missing: $tool. Run git submodule update --init.", tool.isFile)
        val python = listOf("python3").firstOrNull { command ->
            runCatching { ProcessBuilder(command, "--version").start().waitFor() == 0 }.getOrDefault(false)
        } ?: return assumeTrue("SKIPPED: no python3 on PATH, so the independent reader check did not run", false)
        setDefault(twoVersions(), "second-pass")
        val process = ProcessBuilder(python, "-I", "-X", "utf8", tool.path, File(folder.root, "target.opus").path)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        assertEquals(output, 0, process.waitFor())
        assertTrue(output, output.lines()[1].endsWith("default transcript 'second-pass'"))
        assertFalse("the tag and the flag agree:\n$output", "CASSINI_TRANSCRIPT_DEFAULT says" in output)
        assertTrue(output, output.lines().any { Regex("""\s+0 ms\s+spk_a\s+ONE""").matches(it) })
    }

    /** One packet of Opus silence, as in CassiniDocumentTest. */
    private fun silence(): ByteArray {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0, 0, -128, -69, 0, 0, 0, 0, 0)
        val packet = CassiniDocument.tagsPacket(emptyList())
        return OggOpus.mux(OggOpus.Stream(head, packet, listOf(byteArrayOf(-8, -1, -2)), 960, true), packet)
    }

    /** [value] as JSON text with every object's keys sorted, so two parses compare whatever their key order. */
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number -> if (value.toDouble() == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        else -> value.toString()
    }
}
