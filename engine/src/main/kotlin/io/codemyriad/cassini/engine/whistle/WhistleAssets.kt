package io.codemyriad.cassini.engine.whistle

import android.content.Context
import io.codemyriad.cassini.core.models.WhistleCapability
import io.codemyriad.cassini.engine.asr.WhistleDeviceCapability
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Only private, hash-verified APK entries. No system-library lookup or runtime download. */
class WhistleAssets private constructor(
    val needle: String,
    val ort: String,
    val model: ByteBuffer,
    val vad: ByteBuffer,
) {
    companion object {
        const val MODEL_SHA = "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb"
        const val VAD_SHA = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
        private const val MODEL_BYTES = 16919407L
        private const val VAD_BYTES = 2327524L

        fun verified(context: Context): WhistleAssets {
            val capability = WhistleDeviceCapability.current()
            check(capability == WhistleCapability.ELIGIBLE) { "Whistle is unavailable on this device" }
            val manifest = context.assets.open("whistle/manifest.json").use {
                JSONObject(it.readBytes().toString(Charsets.UTF_8))
            }
            check(manifest.getInt("schemaVersion") == 1)
            check(manifest.getString("engineRevision") == "93e88ede867e4311c39151285e74df513e4b835b")
            check(manifest.getString("modelRevision") == "ca5287601bef25af26dcf2e1b2bdc0843a7c19e5")
            val libs = manifest.getJSONObject("libraries")
            val weights = library(context, WhistleArtifactPins.WEIGHTS, libs.getJSONObject(WhistleArtifactPins.WEIGHTS))
            return WhistleAssets(
                library(context, "libcassini_needle.so", libs.getJSONObject("libcassini_needle.so")).path,
                library(context, "libcassini_whistle_ort.so", libs.getJSONObject("libcassini_whistle_ort.so")).path,
                model(weights, WhistleArtifactPins.MODEL_OFFSET, MODEL_BYTES, MODEL_SHA),
                model(weights, WhistleArtifactPins.VAD_OFFSET, VAD_BYTES, VAD_SHA),
            )
        }

        private data class Artifact(val apk: String, val entry: String, val size: Long) {
            val path: String get() = "$apk!/$entry"
        }

        private fun model(weights: Artifact, offset: Long, size: Long, sha: String): ByteBuffer {
            val start = ApkStoredEntry.offset(File(weights.apk), weights.entry, weights.size)
            return RandomAccessFile(weights.apk, "r").use { input ->
                val mapped = input.channel.map(FileChannel.MapMode.READ_ONLY, start + offset, size).asReadOnlyBuffer()
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(mapped.duplicate())
                check(hex(digest.digest()) == sha) { "Whistle model integrity mismatch" }
                mapped
            }
        }

        private fun library(context: Context, name: String, pin: JSONObject): Artifact {
            val info = context.applicationInfo
            val apks = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
            val entry = "lib/arm64-v8a/$name"
            for (path in apks) {
                ZipFile(path).use { zip ->
                    val member = zip.getEntry(entry) ?: return@use
                    check(member.size == pin.getLong("size")) { "Whistle runtime size mismatch" }
                    val actual = zip.getInputStream(member).use(::sha)
                    check(actual == pin.getString("sha256")) { "Whistle runtime integrity mismatch" }
                    WhistleArtifactPins.requireHash(name, actual)
                    return Artifact(path, entry, member.size)
                }
            }
            error("Verified private Whistle runtime missing")
        }

        private fun sha(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            return hex(digest.digest())
        }

        private fun hex(data: ByteArray) = data.joinToString("") { "%02x".format(it) }
    }
}
