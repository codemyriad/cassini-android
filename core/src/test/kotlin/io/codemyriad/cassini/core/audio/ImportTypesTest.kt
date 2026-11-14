package io.codemyriad.cassini.core.audio

import io.codemyriad.cassini.core.audio.ImportTypes.Verdict
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** [ImportTypes] against the vendored cassini-ux `import-types@1` contract (L-25, W0.7; D-1030). */
class ImportTypesTest {
    private val vendor = File(checkNotNull(System.getProperty("cassini.uxVendor")))
    private val contract = JSONObject(File(vendor, "contract/import-types.json").readText())
    private val types = contract.getJSONArray("types").objects()
    private val opus = types.single { it.getString("id") == "opus-ogg" }
    private val validation = opus.getJSONObject("contentValidation")

    @Test fun vendoredContractMatchesItsPin() {
        val pin = JSONObject(File(vendor, "UPSTREAM.json").readText()).getJSONObject("files")
            .getJSONObject("contract/import-types.json")
        val bytes = File(vendor, "contract/import-types.json").readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(pin.getString("sha256"), digest)
        assertEquals("contract/import-types.json", pin.getString("source"))
        assertEquals("cassini-ux/import-types@1", contract.getString("schema"))
        assertTrue("android" in contract.getJSONArray("platforms").strings())
    }

    @Test fun decidedEntriesAreExactlyTheContractsDecidedEntries() {
        val decided = types.filter { it.getString("status") == "decided" }
        assertEquals(decided.map { it.getString("id") }, ImportTypes.DECIDED.map { it.id })
        for ((entry, type) in decided.zip(ImportTypes.DECIDED)) {
            assertEquals(type.id, entry.getJSONArray("extensions").strings(), type.extensions)
            assertEquals(type.id, entry.getJSONArray("mimeTypes").strings(), type.mimeTypes)
            assertEquals(type.id, entry.getJSONArray("compatibilityMimeTypes").strings(), type.compatibilityMimeTypes)
            assertEquals(type.id, entry.getJSONArray("androidMimeTypes").strings(), type.androidMimeTypes)
        }
        assertEquals(
            decided.flatMap { it.getJSONArray("androidMimeTypes").strings() }.distinct(),
            ImportTypes.ANDROID_MIME_TYPES
        )
    }

    @Test fun openEntriesAreTrackedAndNeverAccepted() {
        val open = types.filter { it.getString("status") == "open" }
        assertTrue("FLAC stays open until L-09 / D-938", open.any { it.getString("id") == "flac" })
        val extensions = open.flatMap { it.getJSONArray("extensions").strings() }.toSet()
        val mimes = open.flatMap { it.getJSONArray("mimeTypes").strings() + it.getJSONArray("androidMimeTypes").strings() }
        assertEquals(extensions, ImportTypes.OPEN_EXTENSIONS)
        assertEquals(mimes.toSet(), ImportTypes.OPEN_MIME_TYPES)
        for (mime in mimes) assertFalse(mime, mime in ImportTypes.ANDROID_MIME_TYPES)
        for (extension in extensions) {
            assertEquals(Verdict.REJECT, ImportTypes.verdict("take$extension", null))
            assertEquals(Verdict.REJECT, ImportTypes.verdict("take$extension", "audio/ogg"))
        }
        for (mime in mimes) assertEquals(mime, Verdict.REJECT, ImportTypes.verdict("take.ogg", mime))
    }

    @Test fun contentValidationParametersMatchTheContract() {
        assertEquals(validation.getJSONArray("ambiguousExtensions").strings().toSet(), ImportTypes.AMBIGUOUS_EXTENSIONS)
        assertEquals(validation.getJSONArray("rejectedExtensions").strings().toSet(), ImportTypes.REJECTED_EXTENSIONS)
        assertEquals(validation.getJSONArray("rejectedMimePrefixes").strings(), ImportTypes.REJECTED_MIME_PREFIXES)
        assertEquals(validation.getInt("minProbeBytes"), ImportTypes.MIN_PROBE_BYTES)
        assertEquals("OggS", ascii(validation.getString("containerMagicHex")))
        assertEquals(listOf("OpusHead"), validation.getJSONArray("acceptedBosMagicHex").strings().map(::ascii))
        assertEquals("OpusTags", ascii(validation.getString("secondPageMagicHex")))
    }

    @Test fun verdictsFollowExtensionsAndMimeTypes() {
        val cases = listOf(
            Triple("Weekly.opus", "audio/ogg", Verdict.VALIDATE),
            Triple("Weekly.OPUS", null, Verdict.VALIDATE),
            Triple("voice.ogg", "audio/ogg", Verdict.VALIDATE),
            Triple("voice.ogg", "application/ogg", Verdict.VALIDATE),
            Triple("call.mp3", "audio/mpeg", Verdict.ACCEPT),
            Triple("call.mp3", null, Verdict.ACCEPT),
            Triple("call.m4a", "audio/mp4", Verdict.ACCEPT),
            Triple("call.wav", "audio/x-wav", Verdict.ACCEPT),
            Triple("call.wav", "audio/wav; charset=binary", Verdict.ACCEPT),
            // A compatibility MIME type always needs the stream check, whatever the extension says.
            Triple("call.mp3", "application/ogg", Verdict.VALIDATE),
            Triple(null, "audio/mpeg", Verdict.ACCEPT),
            Triple(null, "audio/x-m4a", Verdict.ACCEPT),
            Triple(null, "audio/vnd.wave", Verdict.ACCEPT),
            // Opus/Ogg requires its extension.
            Triple(null, "audio/ogg", Verdict.REJECT),
            Triple(null, "audio/opus", Verdict.REJECT),
            Triple(null, null, Verdict.REJECT),
            Triple("clip.ogv", "audio/ogg", Verdict.REJECT),
            Triple("clip.oga", null, Verdict.REJECT),
            Triple("clip.spx", null, Verdict.REJECT),
            Triple("clip.ogx", null, Verdict.REJECT),
            Triple("clip.ogg", "video/ogg", Verdict.REJECT),
            Triple("clip.m4a", "video/mp4", Verdict.REJECT),
            Triple("clip.aac", "audio/aac", Verdict.REJECT),
            Triple("clip.mp4", "audio/mp4", Verdict.REJECT),
            Triple("notes.txt", "audio/mpeg", Verdict.REJECT),
            Triple("noextension.", "audio/mpeg", Verdict.ACCEPT),
            Triple(".mp3", null, Verdict.REJECT),
        )
        for ((name, mime, expected) in cases) assertEquals("$name $mime", expected, ImportTypes.verdict(name, mime))
    }

    @Test fun oggOpusPassesTheStreamCheck() {
        val file =
            ogg(page(bos = true, serial = 7, payload = opusHead()), page(bos = false, serial = 7, payload = opusTags()))
        assertTrue(ImportTypes.contentValidated(file))
        assertTrue("a longer file is read only to its head", ImportTypes.contentValidated(file + ByteArray(5000)))
    }

    @Test fun theCommittedOpusFixturePasses() {
        val fixture = File(vendor, "../../fixtures/audio/fleurs-it-short.opus")
        val head = fixture.inputStream().use { input -> input.readNBytes(ImportTypes.MIN_PROBE_BYTES) }
        assertTrue(ImportTypes.contentValidated(head))
    }

    @Test fun everyRejectedBeginningOfStreamFails() {
        val magics = validation.getJSONArray("rejectedBosMagicHex").strings().map(::hex)
        assertEquals(8, magics.size)
        for (magic in magics) {
            val payload = magic + ByteArray(40)
            val single =
                ogg(
                    page(bos = true, serial = 3, payload = payload),
                    page(bos = false, serial = 3, payload = opusTags())
                )
            assertFalse(magic.toHex(), ImportTypes.contentValidated(single))
            // Opus multiplexed with video: the second page is another stream's BOS page.
            val multiplexed =
                ogg(page(bos = true, serial = 3, payload = opusHead()), page(bos = true, serial = 4, payload = payload))
            assertFalse("multiplexed ${magic.toHex()}", ImportTypes.contentValidated(multiplexed))
        }
    }

    @Test fun malformedOrTruncatedHeadsFail() {
        val head = page(bos = true, serial = 1, payload = opusHead())
        val tags = page(bos = false, serial = 1, payload = opusTags())
        val good = ogg(head, tags)
        assertFalse("no second page", ImportTypes.contentValidated(head))
        assertFalse("empty", ImportTypes.contentValidated(ByteArray(0)))
        assertFalse("RIFF", ImportTypes.contentValidated("RIFF0000WAVEfmt ".toByteArray()))
        assertFalse(
            "first page without BOS",
            ImportTypes.contentValidated(ogg(page(bos = false, serial = 1, payload = opusHead()), tags))
        )
        assertFalse(
            "tags of another stream",
            ImportTypes.contentValidated(ogg(head, page(bos = false, serial = 2, payload = opusTags())))
        )
        assertFalse(
            "OpusHead twice",
            ImportTypes.contentValidated(ogg(head, page(bos = false, serial = 1, payload = opusHead())))
        )
        // The check needs every byte up to the end of the OpusTags magic, and no more.
        val needed = head.size + 27 + 1 + "OpusTags".length
        for (cut in 0 until needed) assertFalse("cut at $cut", ImportTypes.contentValidated(good, cut))
        assertTrue(ImportTypes.contentValidated(good, needed))
        val corrupt = good.copyOf().also { it[4] = 1 }
        assertFalse("unknown Ogg version", ImportTypes.contentValidated(corrupt))
    }

    @Test fun extensionsComeFromTheDisplayName() {
        assertEquals(".opus", ImportTypes.extensionOf(" Meeting.Opus "))
        assertEquals(".ogg", ImportTypes.extensionOf("dir/sub/a.b.ogg"))
        assertEquals(null, ImportTypes.extensionOf("README"))
        assertEquals(null, ImportTypes.extensionOf(".hidden"))
        assertEquals(null, ImportTypes.extensionOf(null))
    }

    private fun opusHead(): ByteArray = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0x38, 1, 0x80.toByte(), 0xbb.toByte(), 0, 0, 0, 0, 0)

    private fun opusTags(): ByteArray = "OpusTags".toByteArray() + byteArrayOf(4, 0, 0, 0) + "test".toByteArray() + ByteArray(4)

    private fun page(bos: Boolean, serial: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray())
        out.write(0)
        out.write(if (bos) 2 else 0)
        out.write(ByteArray(8))
        for (shift in listOf(0, 8, 16, 24)) out.write(serial ushr shift and 0xff)
        out.write(ByteArray(8))
        val lacing = mutableListOf<Int>()
        var left = payload.size
        while (left >= 255) {
            lacing += 255
            left -= 255
        }
        lacing += left
        out.write(lacing.size)
        lacing.forEach(out::write)
        out.write(payload)
        return out.toByteArray()
    }

    private fun ogg(vararg pages: ByteArray): ByteArray = pages.fold(ByteArray(0)) { all, page -> all + page }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ascii(text: String): String = String(hex(text), Charsets.US_ASCII)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
