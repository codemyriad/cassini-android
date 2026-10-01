package org.cassini.android

import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater

/** Strict payload boundary: single gzip member, declared lengths, checksums, UTF-8 and duplicate keys. */
internal object CassiniPayload {
    const val MIME = "application/vnd.cassini.portable-meeting+json"
    const val WORD_MIME = "application/vnd.cassini.transcript-words+json"
    const val ENCODING = "base64url+gzip+utf8json"
    const val MAX_RAW = 16 * 1024 * 1024
    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    fun sha(bytes: ByteArray) = OggOpus.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun json(text: String): JSONObject { JsonBoundary(text).check(); return JSONObject(text) }
    fun decode(chunks: List<String>, rawBytes: Long, gzipBytes: Long, sha256: String): String {
        require(rawBytes in 0..MAX_RAW.toLong() && gzipBytes in 18..MAX_RAW.toLong())
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        val encoded = chunks.joinToString("").filterNot { it in " \t\r\n" }
        require(encoded.matches(Regex("[A-Za-z0-9_-]+={0,2}")))
        val compressed = Base64.getUrlDecoder().decode(encoded)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(compressed) == encoded.trimEnd('='))
        require(compressed.size.toLong() == gzipBytes)
        val raw = gunzip(compressed, rawBytes.toInt())
        require(raw.size.toLong() == rawBytes && sha(raw) == sha256)
        return utf8(raw).also { json(it) }
    }
    fun encode(prefix: String, mime: String, text: String): Pair<JSONObject, List<String>> {
        json(text)
        val raw = text.toByteArray(Charsets.UTF_8)
        require(raw.size <= MAX_RAW)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        val compressed = out.toByteArray()
        val chunks = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed).chunked(4096)
        val ref = JSONObject().put("prefix", prefix).put("mime", mime).put("encoding", ENCODING)
            .put("chunkCount", chunks.size).put("sha256", sha(raw)).put("rawBytes", raw.size).put("gzipBytes", compressed.size)
        val tags = mutableListOf("${prefix}MIME=$mime", "${prefix}ENCODING=$ENCODING",
            "${prefix}CHUNK_COUNT=${chunks.size}", "${prefix}SHA256=${sha(raw)}",
            "${prefix}RAW_BYTES=${raw.size}", "${prefix}GZIP_BYTES=${compressed.size}")
        chunks.forEachIndexed { i, chunk -> tags += "$prefix${i.toString().padStart(3, '0')}=$chunk" }
        return ref to tags
    }
    private fun gunzip(b: ByteArray, limit: Int): ByteArray {
        require(b.size >= 18 && b[0] == 31.toByte() && b[1] == 139.toByte() && b[2] == 8.toByte())
        val flags = b[3].toInt() and 255
        require(flags and 224 == 0)
        var pos = 10
        if (flags and 4 != 0) { require(pos + 2 <= b.size); val n = OggOpus.u16(b, pos); pos += 2 + n }
        for (flag in listOf(8, 16)) if (flags and flag != 0) {
            while (pos < b.size && b[pos] != 0.toByte()) pos++
            pos++; require(pos <= b.size)
        }
        if (flags and 2 != 0) {
            require(pos + 2 <= b.size)
            val headerCrc = CRC32().also { it.update(b, 0, pos) }.value.toInt() and 65535
            require(headerCrc == OggOpus.u16(b, pos)); pos += 2
        }
        require(pos <= b.size - 8)
        val inflater = Inflater(true)
        try {
            inflater.setInput(b, pos, b.size - pos)
            val out = ByteArrayOutputStream(minOf(limit, 65536)); val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                require(out.size() + n <= limit)
                require(n > 0 || inflater.finished())
                out.write(buffer, 0, n)
            }
            val trailer = b.size - inflater.remaining
            require(trailer + 8 == b.size) // No concatenated members or ignored trailing bytes.
            val raw = out.toByteArray()
            require((OggOpus.u32(b, trailer).toLong() and 0xffffffffL) == CRC32().also { it.update(raw) }.value)
            require((OggOpus.u32(b, trailer + 4).toLong() and 0xffffffffL) == raw.size.toLong())
            return raw
        } finally { inflater.end() }
    }
    private class JsonBoundary(private val text: String) {
        var p = 0
        fun check() { value(0); space(); require(p == text.length) }
        fun space() { while (p < text.length && text[p] in " \r\n\t") p++ }
        fun take(c: Char): Boolean { space(); if (p < text.length && text[p] == c) { p++; return true }; return false }
        fun string(): String {
            space(); require(p < text.length && text[p] == '"'); val start = p++
            while (p < text.length) {
                val c = text[p++]
                require(c.code >= 32)
                if (c == '"') return JSONTokener(text.substring(start, p)).nextValue() as String
                if (c == '\\') {
                    require(p < text.length)
                    when (text[p++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> { require(p + 4 <= text.length && text.substring(p, p + 4).matches(Regex("[0-9a-fA-F]{4}"))); p += 4 }
                        else -> error("Invalid JSON escape")
                    }
                }
            }
            error("Unterminated JSON string")
        }
        fun value(depth: Int) {
            require(depth <= 64); space(); require(p < text.length)
            when (text[p]) {
                '{' -> { p++; val keys = mutableSetOf<String>(); if (take('}')) return
                    do { require(keys.add(string())); require(take(':')); value(depth + 1) } while (take(','))
                    require(take('}')) }
                '[' -> { p++; if (take(']')) return
                    do { value(depth + 1) } while (take(',')); require(take(']')) }
                '"' -> string()
                else -> {
                    val start = p
                    while (p < text.length && text[p] !in " \r\n\t,]}:") p++
                    val token = text.substring(start, p)
                    require(token in listOf("null", "true", "false") || token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                }
            }
        }
    }
}
