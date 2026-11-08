package io.codemyriad.cassini.bench

import android.os.Build
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.codemyriad.cassini.whistle.WhistleWorker
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.math.abs

/** Mandatory assets when selected: run only on an owned eligible arm64 emulator, never a user's app/device. */
@Suppress("TooManyFunctions") // Shared immutable fixture checks for the worker and the production job.
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
@RequiresApi(Build.VERSION_CODES.Q)
class WhistleWorkerParityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val assets get() = instrumentation.context.assets
    private data class Fixture(
        val name: String,
        val source: String,
        val filtered: String,
        val expected: List<JSONObject>,
    )

    @Test
    fun includedDefaultWritesAPortableTranscriptWithoutAnyModelDownload() {
        val fixture = fixture()
        assertEquals(fixture.source, sourceHash(fixture.name))
        WhistleIncludedJobCase.run(fixture.name, fixture.expected)
    }

    @Test
    fun fullDcTrackUsesPrivateWorkerAndExactDesktopSegments() {
        val fixture = fixture()
        assertEquals(6, fixture.expected.size)
        assertEquals(fixture.source, sourceHash(fixture.name))
        val report = runWorker(fixture.name)
        val output = File(instrumentation.targetContext.filesDir, "whistle-worker-${fixture.name}.json")
        output.writeText(report.toString(2))
        // Publish measured failures first; no golden or tolerance changes.
        assertEquals(fixture.source, report.getString("sourceWavSha256"))
        assertEquals(fixture.filtered, report.getString("vadSha256"))
        assertNotEquals(android.os.Process.myPid(), report.getInt("workerPid"))
        assertNotEquals(android.os.Process.myUid(), report.getInt("workerUid"))
        compareSegments(fixture.expected, report.getJSONArray("segments"))
    }

    private fun fixture(): Fixture {
        val name = InstrumentationRegistry.getArguments().getString("whistleCase") ?: "dc-quiet-jfk-v1"
        require(name == "dc-quiet-jfk-v1" || name == "dc-quiet-jfk-v1-control")
        val source = if (name == "dc-quiet-jfk-v1") {
            "071f051241d394587c85463272e9414f133ee18c4bb835d1382b662e4154efda"
        } else {
            "e31ed958621a39baf1d33dab6d10798695b8319e78fcaa1cb672cdb43c5f7830"
        }
        val filtered = if (name == "dc-quiet-jfk-v1") {
            "eeaeee543bc328acf6f0c4557e8aba86652e0144d9763264e1e5d62a3d13f968"
        } else {
            "6ece4c5cacfc2456cb79d0aa1b16f4aa3912fef6697912e633886d7b0da545ab"
        }
        val reference = assets.open("whistle-probe/mac-reference.json").use {
            JSONObject(it.readBytes().toString(Charsets.UTF_8)).getJSONArray("fixtures")
        }
        val expected = (0 until reference.length()).map { reference.getJSONObject(it) }
            .filter { it.optString("sourceWavSha256") == source }
        return Fixture(name, source, filtered, expected)
    }

    private fun sourceHash(name: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        assets.open("whistle-probe/$name.wav").use { source ->
            val block = ByteArray(8192)
            while (true) {
                val count = source.read(block)
                if (count < 0) break
                digest.update(block, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun runWorker(name: String): JSONObject {
        val actual = ArrayList<JSONObject>()
        val digest = MessageDigest.getInstance("SHA-256")
        var hashes = Bundle.EMPTY
        var pid = 0
        var uid = 0
        val began = System.nanoTime()
        val worker = WhistleWorker(instrumentation.targetContext, 48000) { actual.add(segment(it)) }
        worker.use {
            pid = it.pid
            uid = it.uid
            feedWav(it, name, digest)
            hashes = it.finish()
        }
        return JSONObject().apply {
            put("case", name)
            put("sdk", Build.VERSION.SDK_INT)
            put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
            put("workerPid", pid)
            put("workerUid", uid)
            put("callerUid", android.os.Process.myUid())
            put("callerPid", android.os.Process.myPid())
            put("elapsedMs", (System.nanoTime() - began) / 1_000_000)
            put("sourceWavSha256", hex(digest.digest()))
            put("rawSha256", hashes.getString("rawSha256"))
            put("vadSha256", hashes.getString("vadSha256"))
            put("vadFrames", hashes.getInt("vadFrames"))
            put("voicedFrames", hashes.getInt("voicedFrames"))
            put("maxProbability", hashes.getFloat("maxProbability"))
            put("segments", JSONArray(actual))
            put("referenceScope", "unchanged Android #78 pinned Mac candidate; GNOME discrepancy retained separately")
        }
    }

    private fun segment(message: Bundle): JSONObject = JSONObject().apply {
        put("startSample", message.getLong("start"))
        put("endSample", message.getLong("end"))
        put("reason", message.getString("reason"))
        put("inputSha256", message.getString("pcmSha256"))
        val result = requireNotNull(message.getByteArray("json")).toString(Charsets.UTF_8)
        put("result", JSONObject(result))
    }

    private fun feedWav(worker: WhistleWorker, name: String, digest: MessageDigest) {
        DigestInputStream(assets.open("whistle-probe/$name.wav"), digest).use { input ->
            val header = ByteArray(44)
            java.io.DataInputStream(input).readFully(header)
            val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1, fields.getShort(22).toInt())
            assertEquals(48000, fields.getInt(24))
            assertEquals(16, fields.getShort(34).toInt())
            val block = ByteArray(16384)
            while (true) {
                val count = readBlock(input, block)
                if (count == 0) break
                require(count % 2 == 0)
                val bytes = ByteBuffer.wrap(block, 0, count).order(ByteOrder.LITTLE_ENDIAN)
                worker.accept(FloatArray(count / 2) { bytes.short / 32768f })
            }
        }
    }

    private fun readBlock(input: InputStream, block: ByteArray): Int {
        var count = 0
        while (count < block.size) {
            val read = input.read(block, count, block.size - count)
            if (read < 0) break
            count += read
        }
        return count
    }

    private fun compareSegments(expected: List<JSONObject>, actual: JSONArray) {
        assertEquals(expected.size, actual.length())
        for (i in expected.indices) {
            val want = expected[i]
            val found = actual.getJSONObject(i)
            assertEquals(want.getLong("startSample"), found.getLong("startSample"))
            assertEquals(want.getLong("endSample"), found.getLong("endSample"))
            assertEquals("silence", found.getString("reason"))
            assertEquals(want.getString("inputSha256"), found.getString("inputSha256"))
            compareWords(
                want.getJSONObject("macResult").getJSONArray("words"),
                found.getJSONObject("result").getJSONArray("words"),
            )
        }
    }

    private fun compareWords(wanted: JSONArray, found: JSONArray) {
        assertEquals(
            (0 until wanted.length()).map { wanted.getJSONObject(it).getString("word") },
            (0 until found.length()).map { found.getJSONObject(it).getString("word") },
        )
        for (word in 0 until wanted.length()) {
            val expected = wanted.getJSONObject(word)
            val actual = found.getJSONObject(word)
            for (boundary in listOf("start", "end")) {
                assertTrue(abs(expected.getDouble(boundary) - actual.getDouble(boundary)) <= 0.080000000001)
            }
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
