package io.codemyriad.cassini.core.checkpoint

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Base64

/** One verified Whistle segment result keyed by its raw 16 kHz `<f4` PCM SHA-256 and sample length. */
class WhistleCachedSegment(
    val pcmSha256: String,
    val sampleCount: Int,
    val json: ByteArray,
)

/**
 * Verified per-segment Whistle recognition cache (`cassini.whistle-segment-cache.v1`, D-1124).
 *
 * Stores `(pipeline, segmentPcmSha256, sampleCount) -> segmentJson` inside `work/` so Whistle checkpoint resume
 * replays DSP/VAD/gate state in milliseconds while skipping `WhistleNative.transcribe` on every already-settled
 * segment. Corrupt, oversized, or mismatched-pipeline files are silently ignored as an empty cache.
 */
class WhistleSegmentCache(val pipeline: String) {
    private val map = LinkedHashMap<String, WhistleCachedSegment>()

    val size: Int
        get() = map.size

    fun put(pcmSha256: String, sampleCount: Int, json: ByteArray): Boolean {
        val canInsert = isValidEntry(pcmSha256, sampleCount, json) &&
            (map.size < MAX_ENTRIES || pcmSha256 in map)
        if (canInsert) map[pcmSha256] = WhistleCachedSegment(pcmSha256, sampleCount, json.copyOf())
        return canInsert
    }

    fun get(pcmSha256: String, sampleCount: Int): ByteArray? =
        map[pcmSha256]?.takeIf { it.sampleCount == sampleCount }?.json?.copyOf()

    fun entries(): List<WhistleCachedSegment> = map.values.map {
        WhistleCachedSegment(it.pcmSha256, it.sampleCount, it.json.copyOf())
    }

    fun encode(): JSONObject {
        val segments = JSONObject()
        for (entry in map.values) {
            segments.put(
                entry.pcmSha256,
                JSONObject()
                    .put("sampleCount", entry.sampleCount)
                    .put("jsonBase64", Base64.getEncoder().encodeToString(entry.json)),
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("pipeline", pipeline)
            .put("segments", segments)
    }

    /** Writes atomically to [file] via temp file + fsync + rename. */
    fun writeTo(file: File) {
        ensureParentDirectory(file.parentFile)
        val payload = encode().toString().toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_FILE_BYTES) throw IOException("Whistle segment cache exceeded size cap")
        val tmp = File(file.parentFile, file.name + TEMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(payload)
                out.fd.sync()
            }
            replaceAtomically(tmp, file)
        } catch (error: IOException) {
            tmp.delete()
            throw error
        }
    }

    private fun ensureParentDirectory(parent: File?) {
        if (parent != null && !parent.isDirectory) {
            val created = parent.mkdirs() || parent.isDirectory
            if (!created) throw IOException("Cannot create directory $parent")
        }
    }

    private fun replaceAtomically(tmp: File, target: File) {
        val replaced = tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))
        if (!replaced) throw IOException("Cannot replace $target")
    }

    companion object {
        const val FORMAT = "cassini.whistle-segment-cache.v1"
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
        const val MAX_ENTRIES = 100_000
        const val MAX_SEGMENT_SAMPLES = 240_512
        const val MAX_JSON_BYTES = 262_144
        private const val TEMP_SUFFIX = ".tmp"
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        fun isValidEntry(pcmSha256: String, sampleCount: Int, json: ByteArray): Boolean {
            val shapeOk = HEX64.matches(pcmSha256) &&
                sampleCount in 1..MAX_SEGMENT_SAMPLES &&
                json.size in 1..MAX_JSON_BYTES
            return shapeOk && hasWordsArray(json)
        }

        private fun hasWordsArray(json: ByteArray): Boolean = try {
            JSONObject(json.toString(Charsets.UTF_8)).optJSONArray("words") != null
        } catch (_: JSONException) {
            false
        }

        fun decode(json: JSONObject, expectedPipeline: String): WhistleSegmentCache {
            val cache = WhistleSegmentCache(expectedPipeline)
            val segments = json.optJSONObject("segments")
            if (segments != null && isValidEnvelope(json, expectedPipeline, segments)) {
                val keys = segments.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    decodeSegmentEntry(cache, key, segments.optJSONObject(key))
                }
            }
            return cache
        }

        private fun isValidEnvelope(
            json: JSONObject,
            expectedPipeline: String,
            segments: JSONObject,
        ): Boolean = json.optString("format") == FORMAT &&
            json.optString("pipeline") == expectedPipeline &&
            segments.length() <= MAX_ENTRIES

        private fun decodeSegmentEntry(cache: WhistleSegmentCache, key: String, item: JSONObject?) {
            val count = item?.let { exactInt(it.opt("sampleCount")) }
            val encoded = item?.opt("jsonBase64") as? String
            if (count == null || encoded == null) return
            val decoded = try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (decoded != null) cache.put(key, count, decoded)
        }

        /** Reads [file], returning an empty cache on absence, corruption, oversize, or pipeline mismatch. */
        fun readFrom(file: File, expectedPipeline: String): WhistleSegmentCache {
            if (!file.isFile || file.length() <= 0L || file.length() > MAX_FILE_BYTES) {
                return WhistleSegmentCache(expectedPipeline)
            }
            return try {
                val text = file.readText(Charsets.UTF_8)
                decode(JSONObject(text), expectedPipeline)
            } catch (_: IOException) {
                WhistleSegmentCache(expectedPipeline)
            } catch (_: JSONException) {
                WhistleSegmentCache(expectedPipeline)
            }
        }
    }
}
