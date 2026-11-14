package io.codemyriad.cassini.core.audio

import java.util.Locale

/**
 * The audio files Cassini imports (L-25, W0.7): the decided entries of the shared `cassini-ux/import-types@1`
 * contract, vendored unchanged as `third_party/cassini-ux/contract/import-types.json` and pinned in
 * `third_party/cassini-ux/UPSTREAM.json`. `ImportTypesTest` fails when these values and the vendored file disagree,
 * and `scripts/check-import-types.py` checks the manifest's Open with and Share filters against the same file.
 *
 * Native FLAC requires [flacHeaderValidated]. Ogg video is rejected, as is
 * FLAC-in-Ogg arriving as `.ogg` or under a compatibility MIME type ([contentValidated]).
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

    val FLAC = Type(
        id = "flac",
        extensions = listOf(".flac"),
        mimeTypes = listOf("audio/flac", "audio/x-flac"),
        compatibilityMimeTypes = emptyList(),
        androidMimeTypes = listOf("audio/flac", "audio/x-flac"),
    )

    /** The `status == "decided"` entries, in contract order. */
    val DECIDED: List<Type> = listOf(OPUS_OGG, MP3, M4A, WAV, FLAC)

    /** Extensions and MIME types of the `status == "open"` entries: tracked, never accepted. */
    val OPEN_EXTENSIONS: Set<String> = emptySet()
    val OPEN_MIME_TYPES: Set<String> = emptySet()

    /** The system picker's filter and the manifest's Open with / Share types: every decided `androidMimeTypes`. */
    val ANDROID_MIME_TYPES: List<String> = DECIDED.flatMap { it.androidMimeTypes }.distinct()

    // contentValidation of opus-ogg. ----------------------------------------------------------------------------

    /** `.ogg` may hold anything Ogg can (video, FLAC, Vorbis): it always needs the stream check. */
    val AMBIGUOUS_EXTENSIONS: Set<String> = setOf(".ogg")
    val REJECTED_EXTENSIONS: Set<String> = setOf(".ogv", ".oga", ".ogx", ".spx")
    val REJECTED_MIME_PREFIXES: List<String> = listOf("video/")

    /** Bytes of the head [contentValidated] reads (or the whole file when it is shorter). */
    const val MIN_PROBE_BYTES = 1024

    const val FLAC_MIN_PROBE_BYTES = 42
    val FLAC_REJECTED_MIME_TYPES: Set<String> = setOf("audio/x-flac+ogg", "audio/x-oggflac", "audio/ogg", "application/ogg")
    private val OGG_FLAC_MIME_TYPES = setOf("audio/x-flac+ogg", "audio/x-oggflac")

    /** D102 / RFC 9639: native marker, first STREAMINFO block of length 34, nonzero 20-bit sample rate. */
    @Suppress("MagicNumber") // RFC 9639 STREAMINFO layout.
    fun flacHeaderValidated(head: ByteArray): Boolean =
        head.size >= FLAC_MIN_PROBE_BYTES && startsWith(head, 0, head.size, bytes("fLaC")) &&
            (head[4].toInt() and 0x7f) == 0 && head[5].toInt() == 0 && head[6].toInt() == 0 && head[7].toInt() == 34 &&
            ((head[18].toInt() and 0xff) != 0 || (head[19].toInt() and 0xff) != 0 || (head[20].toInt() and 0xf0) != 0)

    private val OGG_CAPTURE = bytes("OggS")
    private val OPUS_HEAD = bytes("OpusHead")
    private val OPUS_TAGS = bytes("OpusTags")

    /** What an import does with a file, from its display name and MIME type alone. */
    enum class Verdict {
        /** A decided type; import it. */
        ACCEPT,

        /** Possibly a decided type: import it only when [contentValidated] passes on its head. */
        VALIDATE,

        /** Native FLAC: require [flacHeaderValidated] before decoding. */
        VALIDATE_FLAC,

        /** Not a decided type (unknown, open, video or a rejected Ogg extension). */
        REJECT,
    }

    /**
     * Decides from the file's [displayName] (its extension) and the [mimeType] its provider reports; either may be
     * null. Opus/Ogg needs the extension `.opus` or `.ogg` (contract rule) and always the stream check: `.ogg` is
     * ambiguous and `audio/ogg` is a compatibility type, so validating every Opus/Ogg input is the simple safe case.
     * Native FLAC requires `.flac` and its header check, and rejects broad Ogg MIME types.
     * Other inputs reported under a compatibility MIME type need the Ogg check. A file without an
     * extension is accepted on an MP3, M4A or WAV MIME type. On Android a `content://` file often has no extension at
     * all (a messaging app's voice note, `content://…/part/1234`): one reported under an Opus/Ogg MIME type is
     * validated, so the stream check alone decides; a wrong extension (`.ogv`, `.oga`…) is still rejected.
     */
    fun verdict(displayName: String?, mimeType: String?): Verdict {
        val extension = extensionOf(displayName)
        val mime = normalizedMime(mimeType)
        val type = extension?.let { ext -> DECIDED.firstOrNull { ext in it.extensions } }
        return when {
            rejected(extension, mime, type) -> Verdict.REJECT
            type == FLAC -> if (mime in FLAC_REJECTED_MIME_TYPES) Verdict.REJECT else Verdict.VALIDATE_FLAC
            mime in FLAC.mimeTypes -> Verdict.REJECT // Native FLAC requires the .flac extension.
            type == OPUS_OGG -> Verdict.VALIDATE
            type != null -> if (mime in OPUS_OGG.compatibilityMimeTypes) Verdict.VALIDATE else Verdict.ACCEPT
            mime != null && DECIDED.any { it != OPUS_OGG && (mime in it.mimeTypes || mime in it.androidMimeTypes) } ->
                Verdict.ACCEPT
            mime != null && mime in OPUS_OGG_MIME_TYPES -> Verdict.VALIDATE
            else -> Verdict.REJECT
        }
    }

    /**
     * A rejected or open MIME type, or an extension that is rejected, open or not of a decided [type], whatever else
     * the input reports.
     */
    private fun rejected(extension: String?, mime: String?, type: Type?): Boolean {
        val badMime = mime != null &&
            (REJECTED_MIME_PREFIXES.any { mime.startsWith(it) } || mime in OPEN_MIME_TYPES || mime in OGG_FLAC_MIME_TYPES)
        val badExtension = extension != null &&
            (extension in REJECTED_EXTENSIONS || extension in OPEN_EXTENSIONS || type == null)
        return badMime || badExtension
    }

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
