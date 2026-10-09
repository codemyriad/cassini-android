package org.cassini.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** The portable document is authoritative; Transcript is only a selected viewer projection. */
internal data class CassiniDocument(
    val state: String, val manifest: JSONObject? = null,
    val variants: List<Variant> = emptyList(), val comments: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    data class Variant(val id: String, val descriptor: JSONObject, val body: String?, val transcript: Transcript?, val error: String? = null)
    val defaultId get() = variants.firstOrNull { it.descriptor.optBoolean("default") }?.id ?: variants.firstOrNull()?.id
    val title get() = manifest?.optJSONObject("meeting")?.optString("title").orEmpty()
    fun selected(id: String?) = variants.firstOrNull { it.id == (id ?: defaultId) }
    fun speakerLabel(id: String): String? {
        val speakers = manifest?.optJSONArray("speakers") ?: return null
        for (i in 0 until speakers.length()) speakers.getJSONObject(i).let { if (it.optString("id") == id) return it.optString("label") }
        return null
    }
    companion object {
        const val FORMAT = "org.cassini.portable-meeting/1"
        private const val MANIFEST_PREFIX = "CASSINI_PAYLOAD_"
        private val integrityKeys = setOf("matchPolicy", "opusAudioSha256", "sampleRate", "channels", "sampleCount", "durationMs")
        private val refKeys = setOf("prefix", "mime", "encoding", "chunkCount", "sha256", "rawBytes", "gzipBytes")
        private val idPattern = Regex("[a-z0-9][a-z0-9-]{0,31}")
        private val fields = linkedMapOf("mime" to "MIME", "encoding" to "ENCODING", "chunkCount" to "CHUNK_COUNT",
            "sha256" to "SHA256", "rawBytes" to "RAW_BYTES", "gzipBytes" to "GZIP_BYTES")
        fun read(bytes: ByteArray) = read { ByteArrayInputStream(bytes) }
        /** Streams [file]; without [verify] only the headers are read and the audio stays unverified. */
        fun read(file: File, verify: Boolean = true) = read(verify) { file.inputStream().buffered() }
        private fun read(verify: Boolean = true, open: () -> InputStream): CassiniDocument {
            // Audio corruption must not conceal an otherwise intact manifest/transcript.
            val (head, tagPacket) = try { open().use(OggOpus::headers) } catch (_: Exception) { return CassiniDocument("plain-audio") }
            val comments = try { readComments(tagPacket) } catch (_: Exception) { return CassiniDocument("invalid-cassini-metadata") }
            val tags = linkedMapOf<String, String>()
            val duplicates = mutableListOf<String>()
            comments.forEach { comment ->
                val equals = comment.indexOf('=')
                if (equals > 0) {
                    val name = comment.substring(0, equals).uppercase(Locale.ROOT)
                    if (tags.put(name, comment.substring(equals + 1)) != null &&
                        (name == "CASSINI_FORMAT" || name.startsWith(MANIFEST_PREFIX) || name.startsWith("CASSINI_TX_"))) duplicates += name
                }
            }
            val format = tags["CASSINI_FORMAT"] ?: return CassiniDocument("plain-audio", comments = comments)
            if (duplicates.isNotEmpty()) return CassiniDocument("invalid-cassini-metadata", comments = comments, warnings = duplicates)
            if (format != FORMAT) return CassiniDocument("unknown-cassini-format", comments = comments)
            val warnings = mutableListOf<String>()
            val manifest: JSONObject
            try {
                require(tags["CASSINI_PROFILE"] == "ogg-opus" && tags["CASSINI_PAYLOAD_MIME"] == CassiniPayload.MIME)
                require(tags["CASSINI_PAYLOAD_SCHEMA"] == "https://format.gocassini.com/schema/cassini-portable-meeting-manifest-v1.schema.json")
                manifest = CassiniPayload.json(payload(tags, manifestRef(tags)))
                validateManifest(manifest)
            } catch (error: Exception) {
                return CassiniDocument("invalid-cassini-metadata", comments = comments, warnings = listOf(error.message.orEmpty()))
            }
            val descriptors = manifest.getJSONArray("transcripts")
            val variants = (0 until descriptors.length()).map { i ->
                val descriptor = descriptors.getJSONObject(i)
                val id = descriptor.getString("id")
                val ref = descriptor.getJSONObject("payloadRef")
                val prefix = ref.getString("prefix").uppercase(Locale.ROOT)
                fields.forEach { (field, suffix) ->
                    if (tags[prefix + suffix] != ref.get(field).toString()) warnings += "tag-manifest-disagreement: $prefix$suffix"
                }
                try {
                    require(ref.keys().asSequence().toSet() == refKeys && descriptor.getString("format") == "cassini.words.v1")
                    require(ref.getString("mime") == CassiniPayload.WORD_MIME)
                    val body = payload(tags, ref)
                    Variant(id, descriptor, body, Transcript.fromJson(body))
                } catch (error: Exception) { Variant(id, descriptor, null, null, error.message ?: "Unavailable transcript") }
            }
            val default = variants.firstOrNull { it.descriptor.optBoolean("default") }?.id ?: variants.first().id
            if (tags["CASSINI_TRANSCRIPT_IDS"] != variants.map { it.id }.sorted().joinToString(",")) warnings += "tag-manifest-disagreement: CASSINI_TRANSCRIPT_IDS"
            if (tags["CASSINI_TRANSCRIPT_DEFAULT"] != default) warnings += "tag-manifest-disagreement: CASSINI_TRANSCRIPT_DEFAULT"
            val integrity = manifest.getJSONObject("integrity")
            val audio = manifest.getJSONObject("audio")
            val mirrors = mapOf("SAMPLE_RATE" to "sampleRate", "CHANNELS" to "channels", "SAMPLE_COUNT" to "sampleCount", "DURATION_MS" to "durationMs",
                "MATCH_POLICY" to "matchPolicy", "OPUS_SHA256" to "opusAudioSha256")
            mirrors.forEach { (suffix, field) ->
                if (tags["CASSINI_AUDIO_$suffix"] != integrity.opt(field)?.toString()) warnings += "tag-manifest-disagreement: CASSINI_AUDIO_$suffix"
            }
            var state = "unverified"
            if (verify && integrity.keys().asSequence().toSet() == integrityKeys && integrity.optString("matchPolicy") == "exact-opus-audio-v1" && warnings.isEmpty()) {
                try {
                    val stream = open().use(OggOpus::scan)
                    require(stream.head.contentEquals(head))
                    val digest = stream.digest()
                    val shapeMatches = number(audio, "sampleRate") == 48000L && number(audio, "channels") == stream.channels.toLong() &&
                        number(audio, "sampleCount") == stream.sampleCount && number(audio, "durationMs") == stream.durationMs &&
                        number(manifest.getJSONObject("meeting"), "durationMs") == stream.durationMs &&
                        listOf("sampleRate", "channels", "sampleCount", "durationMs").all { number(integrity, it) == number(audio, it) }
                    state = if (digest == integrity.getString("opusAudioSha256") && shapeMatches) "ok" else "stale-audio"
                } catch (_: Exception) { warnings += "audio-verification-unavailable" }
            }
            return CassiniDocument(state, manifest, variants, comments, warnings)
        }
        private fun validateManifest(m: JSONObject) {
            require(m.getString("kind") == "cassini-portable-meeting" && number(m, "version") == 1L && m.getString("profile") == "ogg-opus")
            val meeting = m.getJSONObject("meeting")
            nonempty(meeting, "id"); nonempty(meeting, "title"); number(meeting, "durationMs")
            require(meeting.getString("createdAtUtc").endsWith('Z')); Instant.parse(meeting.getString("createdAtUtc"))
            val audio = m.getJSONObject("audio")
            require(audio.getString("container") == "ogg" && audio.getString("codec") == "opus" && number(audio, "sampleRate") == 48000L)
            require(number(audio, "channels") in 1..2); number(audio, "sampleCount"); number(audio, "durationMs")
            val integrity = m.getJSONObject("integrity")
            nonempty(integrity, "matchPolicy"); require(integrity.getString("opusAudioSha256").matches(Regex("[0-9a-f]{64}")))
            for (key in listOf("sampleRate", "channels", "sampleCount", "durationMs")) number(integrity, key)
            val speakers = m.getJSONArray("speakers"); val speakerIds = mutableSetOf<String>()
            for (i in 0 until speakers.length()) { val s = speakers.getJSONObject(i); nonempty(s, "id"); nonempty(s, "label"); require(speakerIds.add(s.getString("id"))) }
            val entries = m.getJSONArray("transcripts"); require(entries.length() in 1..128)
            val ids = mutableSetOf<String>(); val prefixes = mutableSetOf<String>()
            for (i in 0 until entries.length()) {
                val tx = entries.getJSONObject(i); val id = tx.getString("id")
                require(idPattern.matches(id) && ids.add(id)); nonempty(tx, "format")
                if (tx.has("default")) require(tx.get("default") is Boolean)
                val ref = tx.getJSONObject("payloadRef"); val prefix = ref.getString("prefix").uppercase(Locale.ROOT)
                require(prefix.matches(Regex("CASSINI_TX_[A-Z0-9_]+_PAYLOAD_")) && prefixes.add(prefix))
                nonempty(ref, "mime"); nonempty(ref, "encoding"); nonempty(ref, "sha256")
                require(number(ref, "chunkCount") in 1..4096); number(ref, "rawBytes"); number(ref, "gzipBytes")
            }
        }
        private fun nonempty(o: JSONObject, key: String) { require(o.get(key) is String && o.getString(key).isNotEmpty()) }
        internal fun number(o: JSONObject, key: String): Long {
            val value = o.get(key); require(value is Number)
            val n = value.toDouble(); require(n.isFinite() && n >= 0 && n <= 9007199254740991.0 && n % 1 == 0.0)
            return value.toLong()
        }
        private fun decimal(value: String?): Long {
            require(value != null && value.matches(Regex("0|[1-9][0-9]*")))
            return value.toLong().also { require(it in 0..9007199254740991L) }
        }
        private fun manifestRef(tags: Map<String, String>): JSONObject = JSONObject().put("prefix", MANIFEST_PREFIX).also { ref ->
            fields.forEach { (key, suffix) ->
                val tag = tags[MANIFEST_PREFIX + suffix] ?: error("Missing manifest descriptor")
                ref.put(key, if (key in listOf("chunkCount", "rawBytes", "gzipBytes")) decimal(tag) else tag)
            }
        }
        private fun payload(tags: Map<String, String>, ref: JSONObject): String {
            require(ref.getString("encoding") == CassiniPayload.ENCODING)
            val prefix = ref.getString("prefix").uppercase(Locale.ROOT)
            val count = number(ref, "chunkCount"); require(count in 1..4096)
            val chunks = (0 until count.toInt()).map { tags[prefix + it.toString().padStart(3, '0')] ?: error("Missing chunk $prefix$it") }
            return CassiniPayload.decode(chunks, number(ref, "rawBytes"), number(ref, "gzipBytes"), ref.getString("sha256"))
        }
        internal fun readComments(packet: ByteArray): List<String> {
            require(packet.size <= OggOpus.MAX_HEADER_BYTES)
            var pos = 8
            fun bytes(): ByteArray {
                require(pos + 4 <= packet.size)
                val size = OggOpus.u32(packet, pos); pos += 4
                require(size >= 0 && size <= packet.size - pos)
                return packet.copyOfRange(pos, pos + size).also { pos += size }
            }
            bytes() // Vendor is descriptive, never authoritative.
            require(pos + 4 <= packet.size)
            val count = OggOpus.u32(packet, pos); pos += 4; require(count in 0..20000)
            return List(count) { CassiniPayload.utf8(bytes()) }
        }
        internal fun tagsPacket(comments: List<String>): ByteArray {
            val out = ByteArrayOutputStream(); out.write("OpusTags".toByteArray())
            fun length(n: Int) { val b = ByteArray(4); OggOpus.put32(b, 0, n); out.write(b) }
            val vendor = "cassini-android/0.3".toByteArray(); length(vendor.size); out.write(vendor); length(comments.size)
            comments.forEach { val bytes = it.toByteArray(Charsets.UTF_8); length(bytes.size); out.write(bytes) }
            return out.toByteArray()
        }
        /** Adds a variant while retaining existing descriptors, bodies, extensions and unrelated tags. */
        fun create(audioBytes: ByteArray, transcript: Transcript, title: String, processing: JSONObject,
                   existing: CassiniDocument? = null, speakerLabels: Map<String, String> = emptyMap()): ByteArray {
            val (comments, digest) = seal(OggOpus.scan(ByteArrayInputStream(audioBytes)), transcript, title, processing, existing, speakerLabels)
            val out = ByteArrayOutputStream()
            require(OggOpus.rewrite(ByteArrayInputStream(audioBytes), tagsPacket(comments), out).digest() == digest)
            return out.toByteArray().also { verify(read(it), transcript) }
        }
        /** Streams [audio] into [target] behind the new tags and verifies the written file from disk. */
        fun create(audio: File, transcript: Transcript, title: String, processing: JSONObject, existing: CassiniDocument?,
                   speakerLabels: Map<String, String>, target: File): CassiniDocument {
            val (comments, digest) = seal(OggOpus.scan(audio), transcript, title, processing, existing, speakerLabels)
            writeWithTags(audio, comments, digest, target)
            return read(target).also { verify(it, transcript) }
        }
        /** Copies [source]'s audio packet for packet behind [comments]; the audio must still hash to [digest]. */
        private fun writeWithTags(source: File, comments: List<String>, digest: String, target: File) =
            require(OggOpus.rewrite(source, tagsPacket(comments), target).digest() == digest)
        /**
         * Renames speakers only: bodies, variants, unknown members and unrelated tags stay as they are, and the
         * manifest tags are re-encoded. The written file must read back identical apart from those labels.
         */
        fun relabel(source: File, existing: CassiniDocument, labels: Map<String, String>, target: File): CassiniDocument {
            require(existing.state == "ok" && existing.manifest != null)
            val manifest = JSONObject(existing.manifest.toString())
            val speakers = manifest.getJSONArray("speakers")
            val byId = (0 until speakers.length()).associate { speakers.getJSONObject(it).let { s -> s.getString("id") to s } }
            labels.forEach { (id, label) -> require(label.isNotBlank()) { "Blank label" }; (byId[id] ?: throw IllegalArgumentException("Unknown speaker $id")).put("label", label.trim()) }
            val digest = manifest.getJSONObject("integrity").getString("opusAudioSha256")
            val comments = existing.comments.filterNot { isManifestTag(it.substringBefore('=').uppercase(Locale.ROOT)) } +
                CassiniPayload.encode(MANIFEST_PREFIX, CassiniPayload.MIME, manifest.toString()).second
            try {
                writeWithTags(source, comments, digest, target)
                val sealed = read(target)
                require(sealed.state == "ok" && same(sealed.manifest, manifest)) { "Relabelled document does not read back" }
                require(sealed.variants.map { it.id to it.body } == existing.variants.map { it.id to it.body })
                return sealed
            } catch (error: Throwable) { target.delete(); throw error }
        }
        private fun isManifestTag(name: String) = name in fields.values.map { MANIFEST_PREFIX + it } || name.matches(Regex("CASSINI_PAYLOAD_[0-9]+"))
        /** Order-insensitive structural equality; Android's org.json has no `similar`. */
        private fun same(a: Any?, b: Any?): Boolean = when {
            a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().all { same(a.get(it), b.get(it)) }
            a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.get(it), b.get(it)) }
            a is Number && b is Number -> a.toString() == b.toString() || a.toDouble() == b.toDouble()
            else -> a == b
        }
        private fun verify(sealed: CassiniDocument, transcript: Transcript) =
            require(sealed.state == "ok" && sealed.selected(null)?.transcript == transcript)
        private fun seal(stream: OggOpus.Info, transcript: Transcript, title: String, processing: JSONObject,
                         existing: CassiniDocument?, speakerLabels: Map<String, String>): Pair<List<String>, String> {
            val digest = stream.digest()
            require(existing == null || existing.state == "ok") // Never rebind stale transcripts to different audio.
            val now = Instant.now().toString()
            val manifest = existing?.manifest?.let { JSONObject(it.toString()) } ?: JSONObject()
                .put("kind", "cassini-portable-meeting").put("version", 1).put("profile", "ogg-opus")
                .put("meeting", JSONObject().put("id", "mtg_${UUID.randomUUID()}").put("title", title.ifBlank { "Recording" })
                    .put("createdAtUtc", now).put("durationMs", stream.durationMs))
                .put("audio", JSONObject().put("container", "ogg").put("codec", "opus").put("sampleRate", 48000)
                    .put("channels", stream.channels).put("sampleCount", stream.sampleCount).put("durationMs", stream.durationMs))
                .put("integrity", JSONObject().put("matchPolicy", "exact-opus-audio-v1").put("opusAudioSha256", digest)
                    .put("sampleRate", 48000).put("channels", stream.channels).put("sampleCount", stream.sampleCount).put("durationMs", stream.durationMs))
                .put("speakers", JSONArray()).put("transcripts", JSONArray())
            val descriptors = manifest.getJSONArray("transcripts")
            val id = if (descriptors.length() == 0) "words" else "words-${UUID.randomUUID().toString().take(8)}"
            for (i in 0 until descriptors.length()) descriptors.getJSONObject(i).put("default", false)
            val prefix = "CASSINI_TX_${id.uppercase(Locale.ROOT).replace('-', '_')}_PAYLOAD_"
            val (ref, bodyTags) = CassiniPayload.encode(prefix, CassiniPayload.WORD_MIME, transcript.json())
            descriptors.put(JSONObject().put("id", id).put("format", "cassini.words.v1").put("default", true)
                .put("language", transcript.language).put("wordCount", transcript.words.size).put("createdAtUtc", now).put("payloadRef", ref))
            val speakers = manifest.getJSONArray("speakers")
            val known = (0 until speakers.length()).map { speakers.getJSONObject(it).getString("id") }.toSet()
            transcript.words.map { it.speaker }.distinct().filterNot { it in known }.forEach { speakers.put(JSONObject().put("id", it).put("label", speakerLabels[it] ?: it)) }
            val provenance = manifest.optJSONObject("provenance") ?: JSONObject().also { manifest.put("provenance", it) }
            val speech = provenance.optJSONObject("speechToText") ?: JSONObject().also { provenance.put("speechToText", it) }
            speech.put(id, processing)
            // This document-wide record describes cross-track crosstalk auditing, not
            // clustering a mixed recording. Preserve an imported audit exactly as supplied.
            if (existing == null) provenance.put("attribution", JSONObject().put("ran", false).put("mode", "single-source")
                .put("reason", "Single mixed recording; no separate speaker tracks")
                .put("wordsMeasured", 0).put("wordsFlagged", 0).put("wordsDropped", 0))
            val comments = (existing?.comments ?: readComments(stream.tags)).filterNot {
                val name = it.substringBefore('=').uppercase(Locale.ROOT)
                name == "CASSINI_FORMAT" || name == "CASSINI_PROFILE" || name == "CASSINI_PAYLOAD_SCHEMA" ||
                    isManifestTag(name) ||
                    name in setOf("CASSINI_TRANSCRIPT_IDS", "CASSINI_TRANSCRIPT_DEFAULT", "CASSINI_DECODE_HINT", "CASSINI_SPEAKER_COUNT", "CASSINI_MEETING_ID", "CASSINI_CREATED_AT") || name in setOf("CASSINI_AUDIO_SAMPLE_RATE", "CASSINI_AUDIO_CHANNELS", "CASSINI_AUDIO_SAMPLE_COUNT", "CASSINI_AUDIO_DURATION_MS", "CASSINI_AUDIO_MATCH_POLICY", "CASSINI_AUDIO_OPUS_SHA256")
            }.toMutableList()
            comments += listOf("CASSINI_FORMAT=$FORMAT", "CASSINI_PROFILE=ogg-opus",
                "CASSINI_PAYLOAD_SCHEMA=https://format.gocassini.com/schema/cassini-portable-meeting-manifest-v1.schema.json",
                "CASSINI_TRANSCRIPT_IDS=${(0 until descriptors.length()).map { descriptors.getJSONObject(it).getString("id") }.sorted().joinToString(",")}",
                "CASSINI_TRANSCRIPT_DEFAULT=$id", "CASSINI_SPEAKER_COUNT=${speakers.length()}",
                "CASSINI_MEETING_ID=${manifest.getJSONObject("meeting").getString("id")}", "CASSINI_CREATED_AT=${manifest.getJSONObject("meeting").getString("createdAtUtc")}",
                "CASSINI_DECODE_HINT=Concatenate CASSINI_PAYLOAD_000..N for the manifest; for a transcript body concatenate CASSINI_TX_<ID>_PAYLOAD_000..N. Each chunk set: base64url decode, gzip decompress, parse UTF-8 JSON.")
            mapOf("SAMPLE_RATE" to "48000", "CHANNELS" to stream.channels.toString(), "SAMPLE_COUNT" to stream.sampleCount.toString(),
                "DURATION_MS" to stream.durationMs.toString(), "MATCH_POLICY" to "exact-opus-audio-v1", "OPUS_SHA256" to digest)
                .forEach { (key, value) -> comments += "CASSINI_AUDIO_$key=$value" }
            if (existing == null && comments.none { it.substringBefore('=').equals("TITLE", true) }) comments += "TITLE=${manifest.getJSONObject("meeting").getString("title")}" 
            comments += bodyTags
            comments += CassiniPayload.encode(MANIFEST_PREFIX, CassiniPayload.MIME, manifest.toString()).second
            return comments to digest
        }
    }
}
