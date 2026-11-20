package io.codemyriad.cassini.whistle

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.core.asr.whistle.WhistleSpeechSegment
import io.codemyriad.cassini.core.models.WhistleCapability
import io.codemyriad.cassini.engine.whistle.WhistlePrefixAudit
import io.codemyriad.cassini.engine.whistle.WhistleStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class WhistleWorkerTest {
    @Test
    fun failedBindUnbindsAndPreservesOriginalFailure() = offMain {
        val context = RefusingContext()
        val admission = Semaphore(1)
        val error = assertThrows(IllegalStateException::class.java) { worker(context, admission) }
        assertEquals("Whistle worker could not bind", error.message)
        assertEquals("cleanup failed", error.suppressed.single().message)
        assertTrue(context.unbound)
        assertEquals(1, admission.availablePermits())
    }

    @Test
    fun unavailablePermitHasDeadlineAndNeverBinds() = offMain {
        val context = RefusingContext()
        val error = assertThrows(IllegalStateException::class.java) { worker(context, Semaphore(0)) }
        assertEquals("Whistle worker is busy", error.message)
        assertTrue(!context.bound && !context.unbound)
    }

    @Test
    fun interruptedAdmissionRestoresInterrupt() = offMain {
        val context = RefusingContext()
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { worker(context, Semaphore(0)) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(!context.bound && !context.unbound)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun oomIpcReplyThrowsOutOfMemoryErrorAndReleasesPermit() = offMain {
        val serviceThread = HandlerThread("FakeWhistleOomService").apply { start() }
        try {
            val serviceMessenger = Messenger(
                Handler(serviceThread.looper) { msg ->
                    val reply = Message.obtain(null, WhistleProtocol.ERROR).apply {
                        data = WhistleProtocol.errorBundle(
                            "Whistle worker out of memory",
                            WhistleProtocol.ERROR_CODE_OUT_OF_MEMORY,
                        )
                    }
                    msg.replyTo.send(reply)
                    true
                },
            )
            val context = FakeBoundContext(serviceMessenger)
            val admission = Semaphore(1)
            val error = assertThrows(OutOfMemoryError::class.java) {
                WhistleWorker(context, 16000, {}, admission, 5) { WhistleCapability.ELIGIBLE }
            }
            assertEquals("Whistle worker out of memory", error.message)
            assertTrue(context.unbound)
            assertEquals(1, admission.availablePermits())
        } finally {
            serviceThread.quitSafely()
        }
    }

    @Test
    fun genericIpcErrorThrowsIllegalStateException() = offMain {
        val generic = WhistleProtocol.errorBundle("Whistle worker failed", WhistleProtocol.ERROR_CODE_GENERIC)
        val error = assertThrows(IllegalStateException::class.java) { WhistleProtocol.throwError(generic) }
        assertEquals("Whistle worker failed", error.message)
    }

    @Test
    fun tenThousandSegmentsKeepEveryIpcParcelBoundedAndFinishScalar() = offMain {
        val totalSegments = 10_000
        val cachedCount = 8_500

        // 1. Production mode (audit disabled / no-op onSegment): 10,000 segments produce O(1) IPC parcels (< 4 KiB
        //    per segment, < 1 KiB on FINISH) and zero span lists on either process.
        val prodRun = runSyntheticManySegmentSession(totalSegments, cachedCount, collectAudit = false)
        assertEquals(cachedCount, prodRun.finishBundle.getInt("cacheHits"))
        assertEquals(totalSegments - cachedCount, prodRun.finishBundle.getInt("neuralTranscribes"))
        assertFalse(prodRun.finishBundle.containsKey("cacheHitSpans"))
        assertFalse(prodRun.finishBundle.containsKey("neuralSpans"))
        assertTrue(
            "Max segment Parcel (${prodRun.maxSegmentParcelBytes} B) >= 4 KiB",
            prodRun.maxSegmentParcelBytes < 4_096,
        )
        assertTrue("FINISH Parcel (${prodRun.finishParcelBytes} B) >= 1 KiB", prodRun.finishParcelBytes < 1_024)
        assertTrue(prodRun.clientHitSpans.isEmpty() && prodRun.clientNeuralSpans.isEmpty())

        // 2. Audit mode (per-segment client hook enabled): 10,000 segments still keep every IPC Parcel < 4 KiB and
        //    FINISH < 1 KiB while verifying all 8,500 cached prefix spans + 1,500 neural tail spans on the client.
        val auditRun = runSyntheticManySegmentSession(totalSegments, cachedCount, collectAudit = true)
        assertTrue(auditRun.maxSegmentParcelBytes < 4_096)
        assertTrue(auditRun.finishParcelBytes < 1_024)
        val expectedPrefix = (0 until cachedCount).map { idx ->
            WhistlePrefixAudit.spanKey(idx * 16_000L, (idx + 1) * 16_000L, "%064x".format(idx))
        }
        WhistlePrefixAudit.verifyNoNeuralPrefixRedecode(
            precomputedPrefixSpans = expectedPrefix,
            postStopCacheHitSpans = auditRun.clientHitSpans,
            postStopNeuralSpans = auditRun.clientNeuralSpans,
            baselineTotalSegments = totalSegments,
        )
    }

    private data class ManySegmentRun(
        val finishBundle: Bundle,
        val maxSegmentParcelBytes: Int,
        val finishParcelBytes: Int,
        val clientHitSpans: List<String>,
        val clientNeuralSpans: List<String>,
    )

    private fun runSyntheticManySegmentSession(
        totalSegments: Int,
        cachedCount: Int,
        collectAudit: Boolean,
    ): ManySegmentRun {
        val serviceThread = HandlerThread("FakeWhistleManySegments").apply { start() }
        val maxSegBytes = AtomicInteger(0)
        val finishBytes = AtomicInteger(0)
        val nextSeg = AtomicInteger(0)
        val hitSpans = ArrayList<String>()
        val neuralSpans = ArrayList<String>()
        try {
            val messenger = buildManySegmentMessenger(
                serviceThread,
                totalSegments,
                cachedCount,
                nextSeg,
                maxSegBytes,
                finishBytes,
            )
            val context = FakeBoundContext(messenger)
            val worker = WhistleWorker(
                context,
                16_000,
                { seg -> if (collectAudit) collectClientAuditSpan(seg, hitSpans, neuralSpans) },
                Semaphore(1),
                30,
            ) { WhistleCapability.ELIGIBLE }
            val chunk = FloatArray(16)
            repeat(totalSegments) { worker.accept(chunk, chunk.size) }
            val finishBundle = worker.finish()
            return ManySegmentRun(finishBundle, maxSegBytes.get(), finishBytes.get(), hitSpans, neuralSpans)
        } finally {
            serviceThread.quitSafely()
        }
    }

    @Suppress("LongParameterList")
    private fun buildManySegmentMessenger(
        serviceThread: HandlerThread,
        totalSegments: Int,
        cachedCount: Int,
        nextSeg: AtomicInteger,
        maxSegBytes: AtomicInteger,
        finishBytes: AtomicInteger,
    ): Messenger {
        val sampleJson = """{"words":[{"word":"parola","start":0.05,"end":0.45}]}""".toByteArray()
        return Messenger(
            Handler(serviceThread.looper) { msg ->
                when (msg.what) {
                    WhistleProtocol.OPEN -> msg.replyTo.send(
                        Message.obtain(null, WhistleProtocol.OPEN).apply {
                            data = Bundle().apply {
                                putInt("pid", Process.myPid() + 100)
                                putInt("uid", Process.myUid() + 100)
                            }
                        },
                    )
                    WhistleProtocol.ACCEPT -> {
                        val idx = nextSeg.getAndIncrement()
                        val segBundle = WhistleProtocol.segmentBundle(
                            WhistleSpeechSegment(idx * 16_000L, (idx + 1) * 16_000L, "silence"),
                            sampleJson,
                            "%064x".format(idx),
                            idx < cachedCount,
                        )
                        maxSegBytes.accumulateAndGet(parcelBytes(segBundle), ::maxOf)
                        msg.replyTo.send(Message.obtain(null, WhistleProtocol.SEGMENT).apply { data = segBundle })
                        msg.replyTo.send(Message.obtain(null, WhistleProtocol.ACCEPT).apply { data = Bundle.EMPTY })
                    }
                    WhistleProtocol.FINISH -> {
                        val summary = WhistleStream.Summary(
                            "a".repeat(64),
                            "b".repeat(64),
                            totalSegments * 10,
                            totalSegments * 8,
                            0.95f,
                        )
                        val fin = WhistleProtocol.finishBundle(
                            summary,
                            cachedCount,
                            totalSegments - cachedCount,
                            12_345L,
                        )
                        finishBytes.set(parcelBytes(fin))
                        msg.replyTo.send(Message.obtain(null, WhistleProtocol.FINISH).apply { data = fin })
                    }
                }
                true
            },
        )
    }

    private fun collectClientAuditSpan(seg: Bundle, hitSpans: MutableList<String>, neuralSpans: MutableList<String>) {
        val span = WhistlePrefixAudit.spanKey(
            seg.getLong("start"),
            seg.getLong("end"),
            seg.getString("pcmSha256").orEmpty(),
        )
        if (seg.getBoolean("cacheHit")) hitSpans += span else neuralSpans += span
    }

    private fun parcelBytes(bundle: Bundle): Int {
        val parcel = Parcel.obtain()
        return try {
            bundle.writeToParcel(parcel, 0)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }

    private fun worker(context: Context, admission: Semaphore) =
        WhistleWorker(context, 16000, {}, admission, 0) { WhistleCapability.ELIGIBLE }

    private fun offMain(test: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit(test).get(30, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private class FakeBoundContext(private val serviceMessenger: Messenger) :
        ContextWrapper(ApplicationProvider.getApplicationContext()) {
        var unbound = false
        private var activeConnection: ServiceConnection? = null
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            activeConnection = connection
            val component = ComponentName(packageName, WhistleService::class.java.name)
            connection.onServiceConnected(component, serviceMessenger.binder)
            return true
        }
        override fun unbindService(connection: ServiceConnection) {
            unbound = true
            val component = ComponentName(packageName, WhistleService::class.java.name)
            activeConnection?.onServiceDisconnected(component)
        }
    }

    private class RefusingContext : ContextWrapper(ApplicationProvider.getApplicationContext()) {
        var bound = false
        var unbound = false
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            bound = true
            return false
        }
        override fun unbindService(connection: ServiceConnection) {
            unbound = true
            error("cleanup failed")
        }
    }
}
