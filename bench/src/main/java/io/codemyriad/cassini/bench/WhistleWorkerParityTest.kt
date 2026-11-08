package io.codemyriad.cassini.bench

import android.os.Build
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.math.abs

/** Mandatory assets when selected: run only on an owned eligible arm64 emulator, never a user's app/device. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
@RequiresApi(Build.VERSION_CODES.Q)
class WhistleWorkerParityTest {
    @Test
    fun fullDcTrackUsesPrivateWorkerAndExactDesktopSegments() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val case = InstrumentationRegistry.getArguments().getString("whistleCase") ?: "dc-quiet-jfk-v1"
        require(case == "dc-quiet-jfk-v1" || case == "dc-quiet-jfk-v1-control")
        val sourceHash = if (case == "dc-quiet-jfk-v1") {
            "071f051241d394587c85463272e9414f133ee18c4bb835d1382b662e4154efda"
        } else {
            "e31ed958621a39baf1d33dab6d10798695b8319e78fcaa1cb672cdb43c5f7830"
        }
        val filteredHash = if (case == "dc-quiet-jfk-v1") {
            "eeaeee543bc328acf6f0c4557e8aba86652e0144d9763264e1e5d62a3d13f968"
        } else {
            "6ece4c5cacfc2456cb79d0aa1b16f4aa3912fef6697912e633886d7b0da545ab"
        }
        val reference = assets.open("whistle-probe/mac-reference.json").use {
            JSONObject(it.readBytes().toString(Charsets.UTF_8)).getJSONArray("fixtures")
        }
        val expected = (0 until reference.length()).map { reference.getJSONObject(it) }
            .filter { it.optString("sourceWavSha256") == sourceHash }
        assertEquals(6, expected.size)
        val verifiedSource = MessageDigest.getInstance("SHA-256")
        assets.open("whistle-probe/$case.wav").use { source ->
            val block = ByteArray(8192)
            while (true) {
                val count = source.read(block)
                if (count < 0) break
                verifiedSource.update(block, 0, count)
            }
        }
        assertEquals(sourceHash, verifiedSource.digest().joinToString("") { "%02x".format(it) })
        val actual = ArrayList<JSONObject>()
        val digest = MessageDigest.getInstance("SHA-256")
        var hashes = android.os.Bundle.EMPTY
        var pid = 0
        val began = System.nanoTime()
        WhistleWorker(instrumentation.targetContext, 48000) { segment ->
            actual.add(JSONObject().apply {
                put("startSample", segment.getLong("start"))
                put("endSample", segment.getLong("end"))
                put("reason", segment.getString("reason"))
                put("inputSha256", segment.getString("pcmSha256"))
                put("result", JSONObject(requireNotNull(segment.getByteArray("json")).toString(Charsets.UTF_8)))
            })
        }.use { worker ->
            pid = worker.pid
            DigestInputStream(assets.open("whistle-probe/$case.wav"), digest).use { input ->
                val header = ByteArray(44)
                java.io.DataInputStream(input).readFully(header)
                val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(1, fields.getShort(22).toInt())
                assertEquals(48000, fields.getInt(24))
                assertEquals(16, fields.getShort(34).toInt())
                val block = ByteArray(16384)
                while (true) {
                    var count = 0
                    while (count < block.size) {
                        val read = input.read(block, count, block.size - count)
                        if (read < 0) break
                        count += read
                    }
                    if (count == 0) break
                    require(count % 2 == 0)
                    val bytes = ByteBuffer.wrap(block, 0, count).order(ByteOrder.LITTLE_ENDIAN)
                    worker.accept(FloatArray(count / 2) { bytes.short / 32768f })
                }
            }
            hashes = worker.finish()
        }
        val report = JSONObject().apply {
            put("case", case)
            put("sdk", Build.VERSION.SDK_INT)
            put("workerPid", pid)
            put("callerPid", android.os.Process.myPid())
            put("elapsedMs", (System.nanoTime() - began) / 1_000_000)
            put("sourceWavSha256", digest.digest().joinToString("") { "%02x".format(it) })
            put("rawSha256", hashes.getString("rawSha256"))
            put("vadSha256", hashes.getString("vadSha256"))
            put("vadFrames", hashes.getInt("vadFrames"))
            put("voicedFrames", hashes.getInt("voicedFrames"))
            put("maxProbability", hashes.getFloat("maxProbability"))
            put("segments", JSONArray(actual))
            put("referenceScope", "unchanged Android #78 pinned Mac candidate; GNOME discrepancy retained separately")
        }
        File(instrumentation.targetContext.filesDir, "whistle-worker-$case.json").writeText(report.toString(2))
        // Publish measured failures first; no golden or tolerance changes.
        assertEquals(sourceHash, report.getString("sourceWavSha256"))
        assertEquals(filteredHash, hashes.getString("vadSha256"))
        assertNotEquals(android.os.Process.myPid(), pid)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            val want = expected[i]
            val found = actual[i]
            assertEquals(want.getLong("startSample"), found.getLong("startSample"))
            assertEquals(want.getLong("endSample"), found.getLong("endSample"))
            assertEquals("silence", found.getString("reason"))
            assertEquals(want.getString("inputSha256"), found.getString("inputSha256"))
            val wantedWords = want.getJSONObject("macResult").getJSONArray("words")
            val foundWords = found.getJSONObject("result").getJSONArray("words")
            assertEquals("segment $i word count", wantedWords.length(), foundWords.length())
            assertEquals(
                (0 until wantedWords.length()).map { wantedWords.getJSONObject(it).getString("word") },
                (0 until foundWords.length()).map { foundWords.getJSONObject(it).getString("word") },
            )
            for (word in 0 until wantedWords.length()) {
                val expectedWord = wantedWords.getJSONObject(word)
                val actualWord = foundWords.getJSONObject(word)
                assertEquals(expectedWord.getString("word"), actualWord.getString("word"))
                for (boundary in listOf("start", "end")) {
                    assertTrue(abs(expectedWord.getDouble(boundary) - actualWord.getDouble(boundary)) <= 0.080000000001)
                }
            }
        }
    }
}
