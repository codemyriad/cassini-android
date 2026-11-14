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
    private val flac = types.single { it.getString("id") == "flac" }
    private val header = flac.getJSONObject("headerValidation")

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
        assertEquals("FLAC is decided (D-938, D102)", "decided", flac.getString("status"))
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

    @Test fun headerValidationParametersMatchTheContract() {
        assertEquals("D-938", flac.getJSONArray("decisions").strings().last())
        assertFalse("headerValidation replaces contentValidation", flac.has("contentValidation"))
        assertEquals(header.getInt("minProbeBytes"), ImportTypes.FLAC_MIN_PROBE_BYTES)
        assertEquals("fLaC", ascii(header.getString("magicHex")))
        assertEquals(header.getInt("firstMetadataBlockType"), ImportTypes.FLAC_STREAMINFO_BLOCK_TYPE)
        assertEquals(header.getInt("streamInfoLength"), ImportTypes.FLAC_STREAMINFO_LENGTH)
        assertEquals(header.getJSONArray("rejectedMimeTypes").strings().toSet(), ImportTypes.FLAC_REJECTED_MIME_TYPES)
        assertTrue(ImportTypes.OGG_FLAC_MIME_TYPES.all { it in ImportTypes.FLAC_REJECTED_MIME_TYPES })
        for (mime in ImportTypes.OGG_FLAC_MIME_TYPES) assertFalse(mime, mime in ImportTypes.ANDROID_MIME_TYPES)
        assertFalse(".flac left the Ogg reject list", ".flac" in ImportTypes.REJECTED_EXTENSIONS)
    }

    @Test fun nativeFlacNeedsTheHeaderCheckAndOggFlacIsRejected() {
        val cases = listOf(
            Triple("take.flac", null, Verdict.VALIDATE_HEADER),
            Triple("Take.FLAC", "audio/flac", Verdict.VALIDATE_HEADER),
            Triple("take.flac", "audio/x-flac", Verdict.VALIDATE_HEADER),
            Triple(null, "audio/flac", Verdict.VALIDATE_HEADER),
            Triple("Voice memo", "audio/x-flac", Verdict.VALIDATE_HEADER),
            // Ogg FLAC MIME types, whatever the extension.
            Triple("take.flac", "audio/x-flac+ogg", Verdict.REJECT),
            Triple("take.flac", "audio/x-oggflac", Verdict.REJECT),
            Triple("take.ogg", "audio/x-flac+ogg", Verdict.REJECT),
            Triple(null, "audio/x-oggflac", Verdict.REJECT),
            // A broad Ogg MIME type on a .flac.
            Triple("take.flac", "audio/ogg", Verdict.REJECT),
            Triple("take.flac", "application/ogg", Verdict.REJECT),
            Triple("take.flac", "video/ogg", Verdict.REJECT),
            // Native FLAC is accepted only as .flac (or without an extension).
            Triple("take.ogg", "audio/flac", Verdict.REJECT),
            Triple("take.opus", "audio/x-flac", Verdict.REJECT),
            Triple("take.mp3", "audio/flac", Verdict.REJECT),
            Triple("take.oga", "audio/flac", Verdict.REJECT),
        )
        for ((name, mime, expected) in cases) assertEquals("$name $mime", expected, ImportTypes.verdict(name, mime))
    }

    @Test fun aNativeFlacHeaderPasses() {
        assertTrue(ImportTypes.headerValidated(flacHeader()))
        val long = flacHeader() + ByteArray(5000)
        assertTrue("a longer file is read only to its head", ImportTypes.headerValidated(long))
        assertTrue("STREAMINFO as the last metadata block", ImportTypes.headerValidated(flacHeader(blockType = 0x80)))
        assertTrue("the lowest sample rate", ImportTypes.headerValidated(flacHeader(sampleRate = 1)))
        assertTrue("the highest sample rate", ImportTypes.headerValidated(flacHeader(sampleRate = 0xfffff)))
    }

    @Test fun theCommittedFlacFixturePasses() {
        val fixture = File(vendor, "../../fixtures/audio/fleurs-it-short.flac")
        val head = fixture.inputStream().use { input -> input.readNBytes(ImportTypes.FLAC_MIN_PROBE_BYTES) }
        assertEquals(ImportTypes.FLAC_MIN_PROBE_BYTES, head.size)
        assertTrue(ImportTypes.headerValidated(head))
        assertFalse("not an Ogg Opus stream", ImportTypes.contentValidated(head))
    }

    @Test fun truncatedOrCorruptFlacHeadersFail() {
        val good = flacHeader()
        assertEquals(ImportTypes.FLAC_MIN_PROBE_BYTES, good.size)
        for (cut in 0 until good.size) assertFalse("cut at $cut", ImportTypes.headerValidated(good, cut))
        assertFalse("cut at 41", ImportTypes.headerValidated(good.copyOf(41)))
        assertFalse("empty", ImportTypes.headerValidated(ByteArray(0)))
        assertFalse("lower-case magic", ImportTypes.headerValidated(good.copyOf().also { it[0] = 'F'.code.toByte() }))
        assertFalse("zero sample rate", ImportTypes.headerValidated(flacHeader(sampleRate = 0)))
        assertFalse("first block is PADDING", ImportTypes.headerValidated(flacHeader(blockType = 1)))
        assertFalse("first block is VORBIS_COMMENT", ImportTypes.headerValidated(flacHeader(blockType = 4)))
        assertFalse("STREAMINFO of 33 bytes", ImportTypes.headerValidated(flacHeader(length = 33)))
        assertFalse("STREAMINFO of 35 bytes", ImportTypes.headerValidated(flacHeader(length = 35)))
        assertFalse("STREAMINFO length high byte", ImportTypes.headerValidated(flacHeader(length = 0x010022)))
        assertFalse("RIFF", ImportTypes.headerValidated("RIFF0000WAVEfmt ".toByteArray() + ByteArray(40)))
    }

    @Test fun oggFlacRenamedToFlacFails() {
        // Ogg FLAC (RFC 9639 appendix): an OggS BOS page whose packet is 0x7F "FLAC", version, header count, "fLaC".
        val mapping = byteArrayOf(0x7f) + "FLAC".toByteArray() + byteArrayOf(1, 0, 0, 1) + flacHeader()
        val oggFlac = ogg(page(bos = true, serial = 9, payload = mapping))
        assertTrue(oggFlac.size >= ImportTypes.FLAC_MIN_PROBE_BYTES)
        assertFalse(ImportTypes.headerValidated(oggFlac))
        assertFalse("nor is it Ogg Opus", ImportTypes.contentValidated(oggFlac))
    }

    @Test fun anId3PrefixedFlacFails() {
        // ID3v2.4 header with a 10-byte tag body, then a valid native stream: non-conformant, rejected.
        val id3 = "ID3".toByteArray() + byteArrayOf(4, 0, 0, 0, 0, 0, 10) + ByteArray(10)
        assertFalse(ImportTypes.headerValidated(id3 + flacHeader()))
    }

    /**
     * `fLaC`, a metadata block header ([blockType] byte, 24-bit [length]) and a 34-byte STREAMINFO with [sampleRate]
     * (20 bits), mono, 16 bits per sample, as RFC 9639 section 8.2 lays it out.
     */
    @Suppress("MagicNumber")
    private fun flacHeader(blockType: Int = 0, length: Int = 34, sampleRate: Int = 16_000): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        out.write(blockType)
        out.write(length ushr 16 and 0xff)
        out.write(length ushr 8 and 0xff)
        out.write(length and 0xff)
        val info = ByteArray(34)
        // Minimum and maximum block size 4608, frame sizes unknown.
        info[0] = 0x12
        info[2] = 0x12
        // Sample rate (20 bits), channels - 1 (3 bits), bits per sample - 1 (5 bits), total samples (36 bits).
        val channels = 0
        val bits = 15
        info[10] = (sampleRate ushr 12 and 0xff).toByte()
        info[11] = (sampleRate ushr 4 and 0xff).toByte()
        info[12] = ((sampleRate and 0x0f) shl 4 or (channels shl 1) or (bits ushr 4)).toByte()
        info[13] = ((bits and 0x0f) shl 4).toByte()
        out.write(info)
        return out.toByteArray()
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
            // An extensionless content:// file under an Opus/Ogg MIME type: the stream check decides.
            Triple(null, "audio/ogg", Verdict.VALIDATE),
            Triple(null, "audio/opus", Verdict.VALIDATE),
            Triple("Voice message", "audio/x-opus+ogg", Verdict.VALIDATE),
            Triple(null, "application/ogg", Verdict.VALIDATE),
            Triple(null, "video/ogg", Verdict.REJECT),
            Triple(null, null, Verdict.REJECT),
            // A title with a dot but no extension does not hide the MIME type.
            Triple("Recording 14.11.2026", "audio/mpeg", Verdict.ACCEPT),
            Triple("Voice 08.15", "audio/opus", Verdict.VALIDATE),
            Triple("Dr. Smith", "audio/mp4", Verdict.ACCEPT),
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
        assertEquals(".3gp", ImportTypes.extensionOf("clip.3gp"))
        assertEquals(null, ImportTypes.extensionOf("Recording 14.11.2026"))
        assertEquals(null, ImportTypes.extensionOf("Voice 08.15"))
        assertEquals(null, ImportTypes.extensionOf("Dr. Smith"))
        assertEquals(null, ImportTypes.extensionOf("archive.toolong"))
        assertEquals(null, ImportTypes.extensionOf("noextension."))
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
