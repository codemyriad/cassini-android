package io.codemyriad.cassini.engine.whistle

import android.content.Context
import io.codemyriad.cassini.core.models.WhistleCapability
import io.codemyriad.cassini.engine.asr.WhistleDeviceCapability
import org.json.JSONObject
import java.io.InputStream
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
        private const val ORT_SHA = "e40f09d07dc53726b8bfbf48a7907673b8f86718a057655a62790a39874a7302"
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
            val libs = manifest.getJSONObject("libraries")
            check(libs.getJSONObject("libcassini_whistle_ort.so").getString("sha256") == ORT_SHA)
            return WhistleAssets(
                library(context, "libcassini_needle.so", libs.getJSONObject("libcassini_needle.so")),
                library(context, "libcassini_whistle_ort.so", libs.getJSONObject("libcassini_whistle_ort.so")),
                model(context, "whistle.cact", MODEL_BYTES, MODEL_SHA),
                model(context, "silero_vad.onnx", VAD_BYTES, VAD_SHA),
            )
        }

        private fun model(context: Context, name: String, size: Long, sha: String): ByteBuffer =
            context.assets.openFd("whistle/$name").use { asset ->
                check(asset.length == size) { "Whistle model size mismatch" }
                asset.createInputStream().channel.use { channel ->
                    val mapped = channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, size).asReadOnlyBuffer()
                    val digest = MessageDigest.getInstance("SHA-256")
                    digest.update(mapped.duplicate())
                    check(hex(digest.digest()) == sha) { "Whistle model integrity mismatch" }
                    mapped
                }
            }

        private fun library(context: Context, name: String, pin: JSONObject): String {
            val info = context.applicationInfo
            val apks = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
            val entry = "lib/arm64-v8a/$name"
            for (path in apks) {
                ZipFile(path).use { zip ->
                    val member = zip.getEntry(entry) ?: return@use
                    check(member.size == pin.getLong("size")) { "Whistle runtime size mismatch" }
                    val actual = zip.getInputStream(member).use(::sha)
                    check(actual == pin.getString("sha256")) { "Whistle runtime integrity mismatch" }
                    return "$path!/$entry"
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
