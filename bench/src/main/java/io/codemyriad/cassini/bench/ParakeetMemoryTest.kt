package io.codemyriad.cassini.bench

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.adapters.NetworkCheck
import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.models.ModelFetcher
import io.codemyriad.cassini.engine.asr.Parakeet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Explicit owned-emulator measurement, with public English input and separately verified pinned lab models. */
class ParakeetMemoryTest {
    @Test
    fun coldParakeetAndVadPeakOnTheEnglishDcFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        require(InstrumentationRegistry.getArguments().getString("ownedWhistleLab") == "true")
        val context = instrumentation.targetContext
        val models = InstalledModels(File(context.filesDir, "parakeet-memory"))
        check(models.ready(ModelCatalog.PARAKEET) && models.ready(ModelCatalog.VAD))
        verifiedDefaultAndExplicitChoice(context, File(context.filesDir, "parakeet-memory"))
        val file = File(context.cacheDir, "parakeet-memory-english.wav")
        instrumentation.context.assets.open("whistle-probe/dc-quiet-jfk-v1.wav").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        try {
            val sourceHash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val audio = audioDecoder(context).decode(Uri.fromFile(file))
            val samples = PssSamples()
            val transcript = samples.use {
                Parakeet.transcribe(audio, models.parakeet(), models.path(ModelCatalog.VAD))
            }
            val highWater = File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }
                .split(Regex("\\s+")).getOrNull(1)?.toLong()
            val report = JSONObject()
                .put("sdk", Build.VERSION.SDK_INT).put("pid", Process.myPid())
                .put("model", ModelCatalog.PARAKEET_REVISION).put("vad", ModelCatalog.VAD.sourceSha256)
                .put("verifiedDefaultAndExplicitChoiceAfterRestart", true)
                .put("inputSha256", sourceHash).put("words", transcript.words.size)
                .put("peakPssKiB", samples.peakKiB).put("samples", samples.count)
                .put("maxSampleIntervalMs", samples.maxIntervalNs / 1_000_000.0)
                .put("processPeakRssKiB", highWater)
                .put("scope", "Cold app process; Parakeet + VAD on public English DC input; no optional speaker models")
            File(context.filesDir, "parakeet-memory.json").writeText(report.toString(2))
            assertTrue(transcript.words.isNotEmpty())
            assertTrue(samples.count > 1 && samples.peakKiB > 0)
        } finally {
            file.delete()
        }
    }

    private fun verifiedDefaultAndExplicitChoice(context: Context, modelRoot: File) = runBlocking(Dispatchers.IO) {
        val root = File(context.cacheDir, "parakeet-choice-proof").apply { mkdirs() }
        val paths = AppPaths(root, File(root, "cache").apply { mkdirs() }, modelRoot)
        try {
            repeat(2) { round ->
                val graph = AppGraph(
                    context,
                    AppGraph.Config(
                        paths,
                        fetcher = ModelFetcher { _, _, _ -> error("Selection proof must not download") },
                        network = NetworkCheck { false },
                    ),
                )
                try {
                    graph.ready.await()
                    assertEquals(if (round == 0) SpeechModel.PARAKEET else SpeechModel.INCLUDED, graph.speech.selected())
                    graph.speech.choose(SpeechModel.INCLUDED)
                } finally {
                    graph.close()
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}

/** Continuous kernel PSS queries during inference; the first sample is an event, with no settling sleep. */
private class PssSamples : AutoCloseable {
    private val sampling = AtomicBoolean(true)
    private val ready = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>()
    var peakKiB = 0
        private set
    var count = 0
        private set
    var maxIntervalNs = 0L
        private set
    private val thread = Thread({
        try {
            val memory = Debug.MemoryInfo()
            var previous = System.nanoTime()
            while (sampling.get()) {
                Debug.getMemoryInfo(memory)
                val now = System.nanoTime()
                maxIntervalNs = maxOf(maxIntervalNs, now - previous)
                previous = now
                peakKiB = maxOf(peakKiB, memory.totalPss)
                count++
                ready.countDown()
            }
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            failure.set(error)
        } finally {
            ready.countDown()
        }
    }, "parakeet-pss")

    init {
        thread.start()
        ready.await()
        failure.get()?.let { throw it }
    }

    override fun close() {
        sampling.set(false)
        thread.join()
        failure.get()?.let { throw it }
    }
}
