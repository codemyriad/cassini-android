package io.codemyriad.cassini.core.audio

import java.util.Locale

/**
 * The audio files Cassini imports (L-25, W0.7): the decided entries of the shared `cassini-ux/import-types@1`
 * contract, vendored unchanged as `third_party/cassini-ux/contract/import-types.json` and pinned in
 * `third_party/cassini-ux/UPSTREAM.json`. `ImportTypesTest` fails when these values and the vendored file disagree,
 * and `scripts/check-import-types.py` checks the manifest's Open with and Share filters against the same file.
 *
 * Entries with `status == "open"` (none since version `2026-11-14.3`) are never offered and are rejected here, as are
 * Ogg video and FLAC-in-Ogg arriving as `.ogg` or under a compatibility MIME type ([contentValidated]). Native FLAC
 * (D-938, D102) is decided: a `.flac` must pass the contract's `headerValidation` ([headerValidated]) before it is
 * copied, and Ogg FLAC (`audio/x-flac+ogg`, `audio/x-oggflac`) stays unsupported.
 */
object ImportTypes {
    /** One decided entry. Extensions keep their leading dot, as in the contract; everything is lower case. */
    data class Type(
        val id: String,
        val extensions: List<String>,
        val mimeTypes: List<String>,
        val compatibilityMimeTypes: List<String>,
        val androidMimeTypes: List<String>,
    )

    val OPUS_OGG = Type(
        id = "opus-ogg",
        extensions = listOf(".opus", ".ogg"),
        mimeTypes = listOf("audio/opus", "audio/x-opus+ogg"),
        compatibilityMimeTypes = listOf("audio/ogg", "application/ogg"),
        androidMimeTypes = listOf("audio/opus", "audio/ogg"),
    )

    val MP3 = Type(
        id = "mp3",
        extensions = listOf(".mp3"),
        mimeTypes = listOf("audio/mpeg", "audio/mp3"),
        compatibilityMimeTypes = emptyList(),
        androidMimeTypes = listOf("audio/mpeg"),
    )

    val M4A = Type(
        id = "m4a",
        extensions = listOf(".m4a"),
        mimeTypes = listOf("audio/mp4", "audio/x-m4a", "audio/m4a", "audio/mp4a-latm"),
        compatibilityMimeTypes = emptyList(),
        androidMimeTypes = listOf("audio/mp4", "audio/x-m4a", "audio/mp4a-latm"),
    )

    val WAV = Type(
        id = "wav",
        extensions = listOf(".wav"),
        mimeTypes = listOf("audio/wav", "audio/x-wav", "audio/vnd.wave"),
        compatibilityMimeTypes = emptyList(),
        androidMimeTypes = listOf("audio/wav", "audio/x-wav"),
    )

    /** Native FLAC only (D-938, D102): decoded into an Opus portable meeting like MP3, M4A and WAV. */
    val FLAC = Type(
        id = "flac",
        extensions = listOf(".flac"),
        mimeTypes = listOf("audio/flac", "audio/x-flac"),
        compatibilityMimeTypes = emptyList(),
        androidMimeTypes = listOf("audio/flac", "audio/x-flac"),
    )

    /** The `status == "decided"` entries, in contract order. */
    val DECIDED: List<Type> = listOf(OPUS_OGG, MP3, M4A, WAV, FLAC)

    /** Extensions and MIME types of the `status == "open"` entries: tracked, never accepted (none at present). */
    val OPEN_EXTENSIONS: Set<String> = emptySet()
    val OPEN_MIME_TYPES: Set<String> = emptySet()

    /** Ogg-encapsulated FLAC: unsupported on every platform, whatever the extension says (D102 point 2). */
    val OGG_FLAC_MIME_TYPES: Set<String> = setOf("audio/x-flac+ogg", "audio/x-oggflac")

    /** The system picker's filter and the manifest's Open with / Share types: every decided `androidMimeTypes`. */
    val ANDROID_MIME_TYPES: List<String> = DECIDED.flatMap { it.androidMimeTypes }.distinct()

    // contentValidation of opus-ogg. ----------------------------------------------------------------------------

    /** `.ogg` may hold anything Ogg can (video, FLAC, Vorbis): it always needs the stream check. */
    val AMBIGUOUS_EXTENSIONS: Set<String> = setOf(".ogg")
    val REJECTED_EXTENSIONS: Set<String> = setOf(".ogv", ".oga", ".ogx", ".spx")
    val REJECTED_MIME_PREFIXES: List<String> = listOf("video/")

    /** Bytes of the head [contentValidated] reads (or the whole file when it is shorter). */
    const val MIN_PROBE_BYTES = 1024

    private val OGG_CAPTURE = bytes("OggS")
    private val OPUS_HEAD = bytes("OpusHead")
    private val OPUS_TAGS = bytes("OpusTags")

    // headerValidation of flac (RFC 9639). ----------------------------------------------------------------------

    /** `fLaC`, the 4-byte metadata block header and the 34-byte STREAMINFO: the bytes [headerValidated] reads. */
    const val FLAC_MIN_PROBE_BYTES = 42
    const val FLAC_STREAMINFO_BLOCK_TYPE = 0
    const val FLAC_STREAMINFO_LENGTH = 34

    /** MIME types under which a `.flac` is rejected: Ogg FLAC and the broad Ogg types. */
    val FLAC_REJECTED_MIME_TYPES: Set<String> =
        setOf("audio/x-flac+ogg", "audio/x-oggflac", "audio/ogg", "application/ogg")

    private val FLAC_MAGIC = bytes("fLaC")

    /** What an import does with a file, from its display name and MIME type alone. */
    enum class Verdict {
        /** A decided type; import it. */
        ACCEPT,

        /** Possibly a decided type: import it only when [contentValidated] passes on its head. */
        VALIDATE,

        /** Possibly native FLAC: import it only when [headerValidated] passes on its head. */
        VALIDATE_HEADER,

        /** Not a decided type (unknown, open, video or a rejected Ogg extension). */
        REJECT,
    }

    /**
     * Decides from the file's [displayName] (its extension) and the [mimeType] its provider reports; either may be
     * null. Opus/Ogg needs the extension `.opus` or `.ogg` (contract rule) and always the stream check: `.ogg` is
     * ambiguous and `audio/ogg` is a compatibility type, so validating every Opus/Ogg input is the simple safe case.
     * Any input reported under a compatibility MIME type is validated, whatever its extension. A file without an
     * extension is accepted on an MP3, M4A or WAV MIME type. On Android a `content://` file often has no extension at
     * all (a messaging app's voice note, `content://…/part/1234`): one reported under an Opus/Ogg MIME type is
     * validated, so the stream check alone decides; a wrong extension (`.ogv`, `.oga`, `.aac`…) is still rejected.
     * Native FLAC (`.flac`, or no extension under `audio/flac` / `audio/x-flac`) always needs the header check, and a
     * `.flac` reported under an Ogg FLAC or broad Ogg MIME type is rejected.
     */
    fun verdict(displayName: String?, mimeType: String?): Verdict {
        val extension = extensionOf(displayName)
        val mime = normalizedMime(mimeType)
        val type = extension?.let { ext -> DECIDED.firstOrNull { ext in it.extensions } }
        return when {
            rejected(extension, mime, type) -> Verdict.REJECT
            // Not rejected: a FLAC MIME type here comes without an extension.
            type == FLAC || mime in FLAC_MIME_TYPES -> Verdict.VALIDATE_HEADER
            type == OPUS_OGG -> Verdict.VALIDATE
            type != null -> if (mime in OPUS_OGG.compatibilityMimeTypes) Verdict.VALIDATE else Verdict.ACCEPT
            mime != null && DECIDED.any { it != OPUS_OGG && mime in it.allMimeTypes } -> Verdict.ACCEPT
            mime in OPUS_OGG_MIME_TYPES -> Verdict.VALIDATE
            else -> Verdict.REJECT
        }
    }

    /**
     * A rejected or open MIME type, or an extension that is rejected, open or not of a decided [type], whatever else
     * the input reports; or a case the FLAC rules reject ([flacRejected]).
     */
    private fun rejected(extension: String?, mime: String?, type: Type?): Boolean {
        val badMime = mime != null && (REJECTED_MIME_PREFIXES.any { mime.startsWith(it) } || mime in OPEN_MIME_TYPES)
        val badExtension = extension != null &&
            (extension in REJECTED_EXTENSIONS || extension in OPEN_EXTENSIONS || type == null)
        return badMime || badExtension || flacRejected(extension, mime, type)
    }

    /**
     * The FLAC rules of D102: an Ogg FLAC MIME type whatever the extension; a `.flac` under a rejected MIME type
     * (`headerValidation.rejectedMimeTypes`: Ogg FLAC and the broad Ogg types); and a FLAC MIME type on another
     * extension, since native FLAC is accepted only as `.flac` (or without an extension), never as `.ogg` or `.opus`.
     */
    private fun flacRejected(extension: String?, mime: String?, type: Type?): Boolean =
        mime in OGG_FLAC_MIME_TYPES ||
            (type == FLAC && mime in FLAC_REJECTED_MIME_TYPES) ||
            (extension != null && type != FLAC && mime in FLAC_MIME_TYPES)

    /** Every MIME type native FLAC may arrive under. */
    private val FLAC_MIME_TYPES: Set<String> = FLAC.allMimeTypes

    /** The type's picker and Android MIME types (not its compatibility types). */
    private val Type.allMimeTypes: Set<String> get() = (mimeTypes + androidMimeTypes).toSet()

    /**
     * The contract's Ogg Opus stream check on the first [length] bytes of [head] (at least [MIN_PROBE_BYTES], or
     * the whole file): page 0 is an OggS beginning-of-stream page whose packet starts with `OpusHead`, and page 1 is
     * a non-BOS OggS page of the same stream whose packet starts with `OpusTags`. Ogg video (Theora, OGM, Dirac,
     * Daala, Skeleton), Vorbis, Speex and FLAC-in-Ogg fail on page 0; a multiplexed file fails on page 1, whose
     * second BOS page belongs to another stream.
     */
    fun contentValidated(head: ByteArray, length: Int = head.size): Boolean {
        val end = length.coerceIn(0, head.size)
        val first = page(head, 0, end)?.takeIf { it.bos && startsWith(head, it.payload, end, OPUS_HEAD) }
        val second = first?.let { page(head, it.next, end) }
        return first != null && second != null && !second.bos && second.serial == first.serial &&
            startsWith(head, second.payload, end, OPUS_TAGS)
    }

    /**
     * The contract's FLAC `headerValidation` on the first [length] bytes of [head] (at least [FLAC_MIN_PROBE_BYTES]):
     * the stream marker `fLaC`, then a metadata block header whose type (low 7 bits of byte 4) is STREAMINFO (0) and
     * whose 24-bit length (bytes 5 to 7) is 34, then a STREAMINFO whose 20-bit sample rate (bytes 18 to 20, top 20
     * bits) is not zero. Shorter input, Ogg FLAC (`OggS`) or any other container renamed to `.flac`, and an
     * ID3v2-prefixed (non-conformant) stream fail.
     */
    @Suppress("MagicNumber") // RFC 9639 header layout.
    fun headerValidated(head: ByteArray, length: Int = head.size): Boolean {
        val end = length.coerceIn(0, head.size)
        if (end < FLAC_MIN_PROBE_BYTES || !startsWith(head, 0, end, FLAC_MAGIC)) return false
        fun at(i: Int) = head[i].toInt() and 0xff
        val blockType = at(4) and 0x7f
        val blockLength = (at(5) shl 16) or (at(6) shl 8) or at(7)
        val sampleRate = (at(18) shl 12) or (at(19) shl 4) or (at(20) ushr 4)
        return blockType == FLAC_STREAMINFO_BLOCK_TYPE && blockLength == FLAC_STREAMINFO_LENGTH && sampleRate != 0
    }

    /**
     * The lower-case extension of [displayName] with its dot, or null when the name has none. Only a plausible file
     * extension counts: one to five ASCII letters or digits with at least one letter. A provider's title without an
     * extension may still have a dot ("Recording 14.11.2026", "Voice 08.15", "Dr. Smith"); that is no extension.
     */
    fun extensionOf(displayName: String?): String? {
        val name = displayName?.trim()?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
        val dot = name.lastIndexOf('.')
        val extension = if (dot <= 0) null else name.substring(dot + 1).lowercase(Locale.ROOT)
        return extension?.takeIf { EXTENSION.matches(it) }?.let { ".$it" }
    }

    /** Every MIME type an Opus/Ogg input may arrive under, compatibility types included. */
    private val OPUS_OGG_MIME_TYPES: Set<String> =
        (OPUS_OGG.mimeTypes + OPUS_OGG.androidMimeTypes + OPUS_OGG.compatibilityMimeTypes).toSet()

    private val EXTENSION = Regex("(?=[a-z0-9]*[a-z])[a-z0-9]{1,5}")

    private fun normalizedMime(mimeType: String?): String? =
        mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    private class Page(val bos: Boolean, val serial: Int, val payload: Int, val next: Int)

    /** The Ogg page header at [offset] (RFC 3533 section 6), or null when it is not one or does not fit in [end]. */
    @Suppress("MagicNumber") // RFC 3533 header layout.
    private fun page(bytes: ByteArray, offset: Int, end: Int): Page? {
        // Short-circuit order: each byte read is inside the bounds checked before it.
        val header = offset >= 0 && offset + 27 <= end && startsWith(bytes, offset, end, OGG_CAPTURE) &&
            bytes[offset + 4].toInt() == 0 && offset + 27 + (bytes[offset + 26].toInt() and 0xff) <= end
        if (!header) return null
        val segments = bytes[offset + 26].toInt() and 0xff
        val payload = offset + 27 + segments
        var size = 0
        for (i in 0 until segments) size += bytes[offset + 27 + i].toInt() and 0xff
        val serial = (bytes[offset + 14].toInt() and 0xff) or
            ((bytes[offset + 15].toInt() and 0xff) shl 8) or
            ((bytes[offset + 16].toInt() and 0xff) shl 16) or
            ((bytes[offset + 17].toInt() and 0xff) shl 24)
        return Page(
            bos = bytes[offset + 5].toInt() and 0x02 != 0,
            serial = serial,
            payload = payload,
            next = payload + size
        )
    }

    private fun startsWith(bytes: ByteArray, offset: Int, end: Int, prefix: ByteArray): Boolean {
        return offset >= 0 && offset + prefix.size <= end && prefix.indices.all { bytes[offset + it] == prefix[it] }
    }

    private fun bytes(text: String) = text.toByteArray(Charsets.US_ASCII)
}
