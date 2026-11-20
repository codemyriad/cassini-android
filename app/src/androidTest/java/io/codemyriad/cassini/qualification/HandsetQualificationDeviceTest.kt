package io.codemyriad.cassini.qualification

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.codemyriad.cassini.MainActivity
import io.codemyriad.cassini.TestHooks
import io.codemyriad.cassini.background.Background
import io.codemyriad.cassini.core.asr.TranscriptionMode
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.format.OggOpus
import io.codemyriad.cassini.core.format.OggOpusPrefixScanner
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.core.models.WhistleCapability
import io.codemyriad.cassini.engine.asr.WhistleDeviceCapability
import io.codemyriad.cassini.engine.capture.CaptureFactory
import io.codemyriad.cassini.engine.decode.AudioDecoder
import io.codemyriad.cassini.engine.decode.CommittedOggOpusDecoder
import io.codemyriad.cassini.engine.job.DiarizerFactory
import io.codemyriad.cassini.engine.job.JobEngines
import io.codemyriad.cassini.engine.job.PrecomputeStopReason
import io.codemyriad.cassini.engine.job.Recognizer
import io.codemyriad.cassini.engine.job.RecognizerFactory
import io.codemyriad.cassini.engine.job.StreamingDiarizer
import io.codemyriad.cassini.engine.whistle.WhistlePrefixAudit
import io.codemyriad.cassini.playback.AndroidPlaybackPlatform
import io.codemyriad.cassini.playback.FocusChange
import io.codemyriad.cassini.recording.AfterFinish
import io.codemyriad.cassini.recording.CaptureFormat
import io.codemyriad.cassini.recording.RecordingOutcome
import io.codemyriad.cassini.recording.RecordingPause
import io.codemyriad.cassini.recording.RecordingState
import io.codemyriad.cassini.recording.inProgress
import io.codemyriad.cassini.test.CiSmoke
import io.codemyriad.cassini.test.Manual
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Physical Android handset qualification suite for D-1126 (`D-1117` head-start transcription):
 * - [qualificationHarnessTelemetryAndPrefixContractSmoke] (`@CiSmoke`): fast deterministic contract check on CI
 *   emulators (API 26..35) and physical handsets with zero sleeps and zero model downloads.
 * - [test01HardwareCaptureLifecycleInterruptionsAndAacControl] (`@Manual`): live hardware `OpusCapture` +
 *   `CommittedOggOpusDecoder` prefix equivalence, Pause/Resume, Home backgrounding (`RecordingService`),
 *   audio-focus & call-silence interruptions, `ACTION_AUDIO_BECOMING_NOISY`, and `AacCapture` fallback control.
 * - [test02PairedFiveMinuteEquivalenceAndPressure] (`@Manual`): paired 5-min 3-speaker (`Voice_A`, `Voice_B`,
 *   `Voice_C`) `afterStop` vs `transcribeWhileRecording` equivalence + thermal/memory/stall/OOM controls for
 *   both `Parakeet + Sortformer + CAM++` and `Whistle + Sortformer + CAM++`.
 * - [test03ThirtyMinuteContinuousQualification] (`@Manual`): 30-min (`1,800 s`) continuous 1.0x real-time
 *   hardware `AudioRecord(MIC, 48 kHz)` capture invulnerability + Stop-to-published latency & telemetry.
 */
@Suppress("LargeClass", "TooManyFunctions") // End-to-end D-1126 hardware qualification suite with decomposed steps.
class HandsetQualificationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph get() = Background.graph

    @Before
    fun setUp() = runBlocking {
        TestHooks.reset()
        graph.ready.await()
        Background.reset()
    }

    @After
    fun tearDown() = runBlocking {
        TestHooks.reset()
        val rec = graph.recording
        if (rec.state.value.inProgress) rec.finish()
        withTimeout(FINISH_TIMEOUT_MS) { rec.state.first { !it.inProgress } }
        rec.dismissEnded()
        Background.reset()
    }

    @CiSmoke
    @Test
    fun qualificationHarnessTelemetryAndPrefixContractSmoke() {
        val monitor = HandsetTelemetryMonitor.start(context)
        val liveOpus = File(context.cacheDir, "qual-smoke-prefix.opus")
        try {
            val head = ByteArray(19).also { h ->
                "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(h, 0)
                h[8] = 1
                h[9] = 1
                ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).putShort(10, 312).putInt(12, 48_000)
            }
            val out = FileOutputStream(liveOpus)
            val muxer = OggOpus.LiveMuxer(out, head, CassiniDocument.tagsPacket(emptyList()), serial = 4242)
            val packet = ByteArray(48) { i -> if (i == 0) 0xfc.toByte() else (i * 7).toByte() }
            repeat(150) { muxer.add(packet) }
            out.flush()

            val scanner = OggOpusPrefixScanner(liveOpus)
            assertTrue("Live OggOpusPrefixScanner must yield committed packets", scanner.poll().isNotEmpty())
            val committedBytes = scanner.committedBytes
            assertTrue(committedBytes > 0L)
            assertFalse(scanner.sawEos)
            val liveSha = scanner.committedSha256()

            repeat(30) { muxer.add(packet) }
            muxer.finish(312L + 180L * 960L)
            out.flush()
            out.close()

            val finalPrefixSha = OggOpusPrefixScanner.sha256Prefix(liveOpus, committedBytes)
            assertEquals("Committed Ogg Opus prefix must match after EOS seal", liveSha, finalPrefixSha)
            verifyNoisyBroadcastHook()

            val snap = monitor.finish()
            assertTrue(snap.totalRamMiB > 0.0)
            assertTrue(snap.sampleCount >= 1)
            assertEquals(Build.VERSION.SDK_INT, snap.toJson().getInt("sdkInt"))
        } finally {
            liveOpus.delete()
        }
    }

    @Manual
    @Test
    fun test01HardwareCaptureLifecycleInterruptionsAndAacControl() = runBlocking {
        requireQualConfig()
        grantRecordAudio()
        val hasOpus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && CaptureFactory.platformOpusEncoder()
        val report = JSONObject()
            .put("test", "test01HardwareCaptureLifecycleInterruptionsAndAacControl")
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("platformOpusSupported", hasOpus)

        if (hasOpus) {
            report.put("opusLifecycle", runLiveOpusLifecycleAndInterruptions())
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitVisible(true)
            report.put("aacFallbackControl", runAacFallbackControl())
        }
        emitReport("test01-lifecycle-aac", report)
    }

    private data class BackgroundEvidence(val beforeBgMs: Long, val afterBgMs: Long)

    private data class SealedOpusCheck(val maxPcmDiff: Float, val sealedDurationMs: Long)

    private class LifecyclePrecomputeWatch {
        val tracker = HandsetQualificationAudit.PrecomputeBackgroundYieldTracker()
        val postResumeStarted = CompletableDeferred<Unit>()
        val postResumeStopped = CompletableDeferred<PrecomputeStopReason>()

        fun install() {
            TestHooks.enginesOverride = { _, raw, precompute ->
                if (precompute && tracker.onWorkerStarted()) postResumeStarted.complete(Unit)
                raw
            }
            TestHooks.onPrecomputeStopped = { _, reason ->
                if (tracker.onWorkerStopped(reason.name)) postResumeStopped.complete(reason)
            }
        }
    }

    private suspend fun runLiveOpusLifecycleAndInterruptions(): JSONObject {
        var screen: ActivityScenario<MainActivity>? = ActivityScenario.launch(MainActivity::class.java)
        val whistleEligible = WhistleDeviceCapability.current() == WhistleCapability.ELIGIBLE
        val monitor = HandsetTelemetryMonitor.start(context, isolatedWorkerCpuUnavailable = whistleEligible)
        val sawWhistleFinish = AtomicBoolean(false)
        TestHooks.onWhistleFinish = {
            sawWhistleFinish.set(true)
            monitor.recordWhistleWorkerCpuMs(it.getLong("workerCpuMs"))
        }
        val precomputeWatch = LifecyclePrecomputeWatch().apply { install() }
        var decoder: CommittedOggOpusDecoder? = null
        val livePcmFile = File(context.cacheDir, "qual-live-pcm.f32")
        var livePcmOut: DataOutputStream? = null
        try {
            awaitVisible(true)
            configureOpusLifecycleMode(whistleEligible)
            assertTrue("OpusCapture starts", graph.recording.start("D1126 Opus Lifecycle"))
            val active = awaitRecordingState("Active Opus") { it is RecordingState.Active } as RecordingState.Active
            assertEquals(CaptureFormat.OPUS, active.format)
            val captureFile = File(graph.paths.note(active.noteId), "capture.opus")

            livePcmOut = DataOutputStream(BufferedOutputStream(FileOutputStream(livePcmFile), 64 * 1024))
            val liveSamplesCount = longArrayOf(0L)
            decoder = CommittedOggOpusDecoder(captureFile, included = whistleEligible)
            val committed1 = pollInitialPrefix(decoder, livePcmOut, liveSamplesCount)
            val (next, bgEvidence) = exercisePauseResumeAndBackground(screen!!, whistleEligible, precomputeWatch)
            screen = next
            val focusObserved = exerciseInterruptionsAndRouteLoss()
            val committed2 = pollPostInterruptionPrefix(decoder, livePcmOut, liveSamplesCount, committed1.first)
            decoder.close()
            livePcmOut.close()
            livePcmOut = null

            val sealed = stopAndVerifyOpusPrefixAndPcm(
                captureFile,
                whistleEligible,
                committed1,
                committed2,
                livePcmFile,
                liveSamplesCount[0],
                bgEvidence,
            )
            val snap = monitor.finish().also { Background.reset() }
            assertLifecycleCpuScope(whistleEligible, sawWhistleFinish.get(), snap)
            return buildOpusLifecycleJson(
                committed1.first,
                committed2.first,
                captureFile,
                liveSamplesCount[0],
                sealed,
                bgEvidence,
                focusObserved,
                snap,
            )
        } finally {
            runCatching { livePcmOut?.close() }
            runCatching { decoder?.close() }
            runCatching { livePcmFile.delete() }
            runCatching { screen?.close() }
        }
    }

    private fun assertLifecycleCpuScope(
        whistleEligible: Boolean,
        sawWhistleFinish: Boolean,
        snap: HandsetTelemetryMonitor.Snapshot,
    ) {
        if (whistleEligible && !sawWhistleFinish) {
            assertEquals(null, snap.whistleWorkerCpuSeconds)
            assertEquals(null, snap.totalCpuSeconds)
            assertEquals("app_process_only_isolated_worker_unavailable", snap.cpuScope)
        }
    }

    @Suppress("LongParameterList")
    private fun buildOpusLifecycleJson(
        committedBytes1: Long,
        committedBytes2: Long,
        captureFile: File,
        liveSampleCount: Long,
        sealed: SealedOpusCheck,
        bgEvidence: BackgroundEvidence,
        focusObserved: List<String>,
        snap: HandsetTelemetryMonitor.Snapshot,
    ): JSONObject = JSONObject()
        .put("passed", true)
        .put("committedBytes1", committedBytes1)
        .put("committedBytes2", committedBytes2)
        .put("finalFileBytes", captureFile.length())
        .put("backgroundAdvanceMs", bgEvidence.afterBgMs - bgEvidence.beforeBgMs)
        .put("sealedDurationMs", sealed.sealedDurationMs)
        .put("liveDecodedSamples16k", liveSampleCount)
        .put("maxDecodedPcmAbsDiff", sealed.maxPcmDiff.toDouble())
        .put("audioFocusEvents", JSONArray(focusObserved))
        .put("telemetry", snap.toJson())

    private suspend fun configureOpusLifecycleMode(whistleEligible: Boolean) {
        if (whistleEligible) {
            graph.speech.choose(SpeechModel.INCLUDED)
            graph.settings.setTranscriptionMode(TranscriptionMode.TRANSCRIBE_WHILE_RECORDING)
        } else {
            graph.settings.setTranscriptionMode(TranscriptionMode.RECORD_ONLY)
        }
    }

    private suspend fun pollInitialPrefix(
        decoder: CommittedOggOpusDecoder,
        livePcmOut: DataOutputStream,
        liveSamplesCount: LongArray,
    ): Pair<Long, String> {
        var committed = 0L
        awaitPolicyTicksUntil("Initial committed Ogg Opus pages") {
            decoder.poll { samples, count ->
                for (i in 0 until count) livePcmOut.writeFloat(samples[i])
                liveSamplesCount[0] += count
            }
            committed = decoder.committedBytes
            committed > 0L && liveSamplesCount[0] >= 16_000L
        }
        return committed to decoder.committedSha256()
    }

    private suspend fun pollPostInterruptionPrefix(
        decoder: CommittedOggOpusDecoder,
        livePcmOut: DataOutputStream,
        liveSamplesCount: LongArray,
        previousBytes: Long,
    ): Pair<Long, String> {
        var committed = previousBytes
        awaitPolicyTicksUntil("Post-interruption Ogg Opus pages committed") {
            decoder.poll { samples, count ->
                for (i in 0 until count) livePcmOut.writeFloat(samples[i])
                liveSamplesCount[0] += count
            }
            committed = decoder.committedBytes
            committed > previousBytes
        }
        return committed to decoder.committedSha256()
    }

    private suspend fun exercisePauseResumeAndBackground(
        initialScreen: ActivityScenario<MainActivity>,
        whistleEligible: Boolean,
        precomputeWatch: LifecyclePrecomputeWatch,
    ): Pair<ActivityScenario<MainActivity>, BackgroundEvidence> {
        val recording = graph.recording
        recording.pause()
        awaitRecordingState("Paused by user") { (it as? RecordingState.Active)?.pause == RecordingPause.USER }
        val pausedMs = recording.elapsedNowMs
        awaitNPicks(2)
        val driftWhilePaused = abs(recording.elapsedNowMs - pausedMs)
        assertTrue("Paused OpusCapture must not advance ($driftWhilePaused ms)", driftWhilePaused <= 100L)

        // Ignore any stale precompute stop queued by Pause and require a fresh post-Resume worker start.
        precomputeWatch.tracker.markResumed()
        recording.resume()
        awaitRecordingState("Resumed after user pause") {
            it is RecordingState.Active && it.pause == null && it.elapsedMs >= pausedMs + 1_200L
        }
        if (whistleEligible) {
            withTimeout(EVENT_TIMEOUT_MS) { precomputeWatch.postResumeStarted.await() }
        }

        val beforeBgMs = recording.elapsedNowMs
        val targetBgMs = beforeBgMs + BACKGROUND_DURATION_MS
        precomputeWatch.tracker.markHomeIssued()
        Background.shell("input keyevent KEYCODE_HOME")
        awaitVisible(false)
        if (whistleEligible) {
            withTimeout(EVENT_TIMEOUT_MS) { precomputeWatch.postResumeStopped.await() }
            precomputeWatch.tracker.verifyYieldedAfterBackground()
        }
        val bgState = withTimeout(BACKGROUND_TIMEOUT_MS) {
            recording.state.first { state ->
                when (state) {
                    is RecordingState.Active -> state.elapsedMs >= targetBgMs || state.pause != null
                    else -> true
                }
            }
        }
        assertTrue(
            "RecordingService must keep OpusCapture active for full 5-min background interval ($bgState)",
            bgState is RecordingState.Active && bgState.pause == null,
        )
        assertFalse("Activity must remain backgrounded during 5-min interval", graph.visibility.visible.value)
        val afterBgMs = recording.elapsedNowMs
        assertTrue(
            "OpusCapture must advance >= $BACKGROUND_DURATION_MS ms in background (actual=${afterBgMs - beforeBgMs} ms)",
            afterBgMs >= targetBgMs,
        )
        runCatching { initialScreen.close() }
        val reopened = ActivityScenario.launch(MainActivity::class.java).also { awaitVisible(true) }
        return reopened to BackgroundEvidence(beforeBgMs, afterBgMs)
    }

    private suspend fun exerciseInterruptionsAndRouteLoss(): List<String> {
        val recording = graph.recording
        val focusObserved = exerciseAudioFocusTransientLossAndGain()
        verifyNoisyBroadcastHook()
        TestHooks.emitSilence(true)
        awaitRecordingState("Silenced by incoming call") {
            (it as? RecordingState.Active)?.pause == RecordingPause.OTHER_APP
        }
        TestHooks.emitSilence(false)
        recording.awaitPosted()
        assertEquals(RecordingPause.OTHER_APP, (recording.state.value as? RecordingState.Active)?.pause)
        recording.resume()
        awaitRecordingState("Resumed after call silence") {
            it is RecordingState.Active && it.pause == null
        }
        return focusObserved
    }

    @Suppress("LongParameterList")
    private suspend fun stopAndVerifyOpusPrefixAndPcm(
        captureFile: File,
        included: Boolean,
        committed1: Pair<Long, String>,
        committed2: Pair<Long, String>,
        livePcmFile: File,
        liveSampleCount: Long,
        bgEvidence: BackgroundEvidence,
    ): SealedOpusCheck {
        val recording = graph.recording
        graph.settings.setTranscriptionMode(TranscriptionMode.RECORD_ONLY)
        recording.finish()
        val ended = awaitRecordingState("OpusCapture ended") { it is RecordingState.Ended } as RecordingState.Ended
        recording.dismissEnded()
        assertTrue(ended.outcome is RecordingOutcome.Saved)

        assertEquals(
            "Committed prefix 1 must match EOS-sealed .opus",
            committed1.second,
            OggOpusPrefixScanner.sha256Prefix(captureFile, committed1.first),
        )
        assertEquals(
            "Committed prefix 2 must match EOS-sealed .opus",
            committed2.second,
            OggOpusPrefixScanner.sha256Prefix(captureFile, committed2.first),
        )

        var compared = 0L
        var finalizedSamples = 0L
        var maxPcmDiff = 0f
        DataInputStream(BufferedInputStream(livePcmFile.inputStream(), 64 * 1024)).use { liveIn ->
            openDecoder(context, included).stream(
                Uri.fromFile(captureFile),
                { block, count ->
                    finalizedSamples += count
                    val take = minOf(count.toLong(), liveSampleCount - compared).toInt()
                    for (i in 0 until take) {
                        val liveSample = liveIn.readFloat()
                        HandsetQualificationAudit.verifyBitExactFloatPcmSample(compared + i, liveSample, block[i])
                        maxPcmDiff = maxOf(maxPcmDiff, abs(liveSample - block[i]))
                    }
                    compared += take
                },
            )
        }
        assertEquals("Finalized .opus must contain all live-decoded samples", liveSampleCount, compared)
        val sealedDurationMs = finalizedSamples * 1_000L / 16_000L
        assertTrue(
            "Sealed .opus ($sealedDurationMs ms) must cover the 5-min background interval " +
                "(afterBg=${bgEvidence.afterBgMs} ms, min=$BACKGROUND_DURATION_MS ms)",
            sealedDurationMs >= BACKGROUND_DURATION_MS && sealedDurationMs >= bgEvidence.afterBgMs - 2_000L,
        )
        return SealedOpusCheck(maxPcmDiff = maxPcmDiff, sealedDurationMs = sealedDurationMs)
    }

    private suspend fun runAacFallbackControl(): JSONObject {
        val monitor = HandsetTelemetryMonitor.start(context)
        ensureModelsInstalled(setOf(ModelPart.SPEECH))
        graph.settings.recordConsent(1L, includeSpeech = true, includeOptional = false)
        graph.settings.setSpeakerChoice(ModelPart.SPEAKERS, false)
        graph.settings.setSpeakerChoice(ModelPart.VOICES, false)
        graph.speech.choose(SpeechModel.PARAKEET)
        graph.settings.setTranscriptionMode(TranscriptionMode.TRANSCRIBE_WHILE_RECORDING)

        val precomputeCheckpoints = AtomicInteger(0)
        TestHooks.onPrecomputeCheckpointed = { _, _ -> precomputeCheckpoints.incrementAndGet() }
        // Leave captureSource unset on API 26-28 so production format selection (CaptureFactory -> AacCapture) runs
        // naturally; keep ExplicitAacCaptureSource as the forced control on Opus-capable API 29+ devices.
        val naturalAacFallback = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        TestHooks.captureSource = if (naturalAacFallback) null else ExplicitAacCaptureSource(context)
        TestHooks.lastStartedCaptureClass = null

        val recording = graph.recording
        assertTrue("AacCapture starts", recording.start("D1126 AAC Fallback"))
        val active = awaitRecordingState("Active AacCapture") { it is RecordingState.Active } as RecordingState.Active
        assertEquals(CaptureFormat.M4A, active.format)
        assertEquals("AacCapture", TestHooks.lastStartedCaptureClass)
        awaitRecordingState("AAC records >= 2.5s") { it is RecordingState.Active && it.elapsedMs >= 2_500L }

        val api26FocusPaused = exerciseApi26FocusPauseIfApplicable()
        awaitNPicks(2)
        assertEquals("AAC fallback must spawn zero precompute checkpoints", 0, precomputeCheckpoints.get())

        val stopNs = SystemClock.elapsedRealtimeNanos()
        recording.finish()
        val ended = awaitRecordingState("AacCapture ended") { it is RecordingState.Ended } as RecordingState.Ended
        recording.dismissEnded()
        assertEquals(RecordingOutcome.Saved(AfterFinish.TRANSCRIBE), ended.outcome)

        val publishedDoc = awaitNotePublished(active.noteId, PUBLISH_TIMEOUT_MS)
        val stopToPublishedSec = (SystemClock.elapsedRealtimeNanos() - stopNs) / 1_000_000_000.0
        assertEquals(DocumentState.OK, publishedDoc.documentState)
        assertEquals("AAC precompute checkpoints must remain 0", 0, precomputeCheckpoints.get())

        val snap = monitor.finish()
        val captureClass = TestHooks.lastStartedCaptureClass
        TestHooks.captureSource = null
        Background.reset()
        return JSONObject()
            .put("passed", true)
            .put("naturalApi26To28", naturalAacFallback)
            .put("captureClass", captureClass)
            .put("api26FocusPauseVerified", api26FocusPaused)
            .put("precomputeCheckpoints", precomputeCheckpoints.get())
            .put("stopToPublishedSeconds", stopToPublishedSec)
            .put("publishedDurationMs", publishedDoc.audioDurationMs)
            .put("telemetry", snap.toJson())
    }

    private suspend fun exerciseApi26FocusPauseIfApplicable(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
        val competing = requestTransientFocus()
        assertNotNull("Competing transient audio-focus request must be granted on API 26-28", competing)
        try {
            awaitRecordingState("API 26-28 focus loss pauses AAC") {
                (it as? RecordingState.Active)?.pause == RecordingPause.OTHER_APP
            }
        } finally {
            abandonTransientFocus(competing!!)
        }
        graph.recording.resume()
        awaitRecordingState("API 26-28 AAC resumed") { it is RecordingState.Active && it.pause == null }
        return true
    }

    @Manual
    @Test
    fun test02PairedFiveMinuteEquivalenceAndPressure() = runBlocking {
        val cfg = requireQualConfig()
        val whistleEligible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            WhistleDeviceCapability.current() == WhistleCapability.ELIGIBLE
        val plan = HandsetQualificationAudit.resolvePairedEngines(cfg.runParakeet, cfg.runWhistle, whistleEligible)
        grantRecordAudio()

        val neededParts = mutableSetOf(ModelPart.SPEAKERS, ModelPart.VOICES)
        if (plan.runParakeet) neededParts += ModelPart.SPEECH
        ensureModelsInstalled(neededParts)

        val track = withContext(Dispatchers.IO) {
            MultiSpeakerSpeechTrack.fromAssets(context, instrumentation.context)
        }
        val enrolled = MultiSpeakerSpeechTrack.enrollThreeVoices(graph, track)
        assertEquals(MultiSpeakerSpeechTrack.SYNTHETIC_VOICE_LABELS, enrolled.names)
        enableFullSpeakerSettings()

        val report = JSONObject()
            .put("test", "test02PairedFiveMinuteEquivalenceAndPressure")
            .put("audioSeconds", cfg.shortSeconds)
            .put("enrolledVoices", JSONArray(enrolled.names))
            .put("maxPairwiseVoiceCosine", enrolled.maxPairwiseCosine.toDouble())
            .put("whistleEligible", whistleEligible)

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitVisible(true)
            report.put("recordOnly", runRecordOnlyControl(track, cfg.shortSeconds))
            if (plan.runParakeet) {
                report.put("parakeet", runEngineEquivalenceSuite(SpeechModel.PARAKEET, track, cfg.shortSeconds))
            }
            if (plan.runWhistle) {
                report.put("whistle", runEngineEquivalenceSuite(SpeechModel.INCLUDED, track, cfg.shortSeconds))
            }
        }
        emitReport("test02-paired-5min", report)
    }

    private suspend fun enableFullSpeakerSettings() {
        graph.settings.recordConsent(1L, includeSpeech = true, includeOptional = true)
        graph.settings.setSpeakerChoice(ModelPart.SPEAKERS, true)
        graph.settings.setSpeakerChoice(ModelPart.VOICES, true)
        graph.settings.setAutoNameVoices(true)
    }

    private suspend fun runRecordOnlyControl(track: MultiSpeakerSpeechTrack, seconds: Int): JSONObject {
        val monitor = HandsetTelemetryMonitor.start(context)
        val precomputeCheckpoints = AtomicInteger(0)
        TestHooks.onPrecomputeCheckpointed = { _, _ -> precomputeCheckpoints.incrementAndGet() }
        val source = DeterministicOpusCaptureSource(track, totalSeconds = seconds, splitAfterSeconds = null)
        TestHooks.captureSource = source
        graph.settings.setTranscriptionMode(TranscriptionMode.RECORD_ONLY)

        assertTrue(graph.recording.start("D1126 RecordOnly Control"))
        val active = awaitRecordingState("RecordOnly active") { it is RecordingState.Active } as RecordingState.Active
        withTimeout(ENCODE_TIMEOUT_MS) { source.prefixCommitted.await() }
        val stopNs = SystemClock.elapsedRealtimeNanos()
        graph.recording.finish()
        val ended = awaitRecordingState("RecordOnly ended") { it is RecordingState.Ended } as RecordingState.Ended
        val stopSec = (SystemClock.elapsedRealtimeNanos() - stopNs) / 1_000_000_000.0
        graph.recording.dismissEnded()

        assertEquals(RecordingOutcome.Saved(AfterFinish.AUDIO_ONLY), ended.outcome)
        assertEquals(0, precomputeCheckpoints.get())
        assertEquals(null, Background.row(active.noteId)?.jobState)
        val snap = monitor.finish()
        cleanupNote(active.noteId)
        return JSONObject()
            .put("stopSeconds", stopSec)
            .put("precomputeCheckpoints", 0)
            .put("telemetry", snap.toJson())
    }

    private suspend fun runEngineEquivalenceSuite(
        model: SpeechModel,
        track: MultiSpeakerSpeechTrack,
        totalSeconds: Int,
    ): JSONObject {
        val prefixSeconds = (totalSeconds - 20).coerceAtLeast(35)
        sendStream("[qual] Starting ${model.name} AFTER_STOP (${totalSeconds}s)...\n")
        val baseline = runPairedLane(model, TranscriptionMode.AFTER_STOP, track, totalSeconds, null)
        sendStream("[qual] Starting ${model.name} TRANSCRIBE_WHILE_RECORDING (${totalSeconds}s)...\n")
        val headStart = runPairedLane(
            model,
            TranscriptionMode.TRANSCRIBE_WHILE_RECORDING,
            track,
            totalSeconds,
            prefixSeconds,
        )
        assertPairEquivalence(model, baseline, headStart)

        sendStream("[qual] Starting ${model.name} thermal + stall recovery lane...\n")
        val thermal = runThermalAndStallLane(model, track, totalSeconds, prefixSeconds)
        assertRecoveryMatchesBaseline(model, "thermal", baseline, thermal.lane)

        sendStream("[qual] Starting ${model.name} dedicated low-memory suppression lane...\n")
        val lowMemory = runLowMemorySuppressionLane(model, track, totalSeconds)
        assertRecoveryMatchesBaseline(model, "lowMemory", baseline, lowMemory.lane)

        val oomPassed = if (model == SpeechModel.PARAKEET) {
            runOomSuppressionCheck(track)
        } else {
            HandsetQualificationAudit.evaluateOomSuppression(exercised = false, observedStopReason = null)
        }
        return buildEquivalenceReport(model, baseline, headStart, thermal, lowMemory, oomPassed)
    }

    private fun assertRecoveryMatchesBaseline(
        model: SpeechModel,
        label: String,
        baseline: LaneResult,
        recovered: LaneResult,
    ) {
        HandsetQualificationAudit.verifyLaneEquivalence(
            label = "${model.name} $label recovery",
            baseline = baseline.toEquivalenceSnapshot(),
            candidate = recovered.toEquivalenceSnapshot(),
            expectedEnrolledVoices = MultiSpeakerSpeechTrack.SYNTHETIC_VOICE_LABELS,
        )
    }

    private fun assertPairEquivalence(model: SpeechModel, baseline: LaneResult, headStart: LaneResult) {
        HandsetQualificationAudit.verifyLaneEquivalence(
            label = model.name,
            baseline = baseline.toEquivalenceSnapshot(),
            candidate = headStart.toEquivalenceSnapshot(),
            expectedEnrolledVoices = MultiSpeakerSpeechTrack.SYNTHETIC_VOICE_LABELS,
        )
        assertTrue("${model.name} must save precompute checkpoints", headStart.precomputeCheckpoints > 0)
        assertTrue("${model.name} x-resumedFromMs > 0", headStart.resumedFromMs > 0L)
        assertTrue(
            "${model.name} post-Stop units (${headStart.postStopUnits}) < baseline (${baseline.postStopUnits})",
            headStart.postStopUnits < baseline.postStopUnits,
        )
        if (model == SpeechModel.INCLUDED) {
            WhistlePrefixAudit.verifyNoNeuralPrefixRedecode(
                precomputedPrefixSpans = headStart.whistlePrecomputedSpans,
                postStopCacheHitSpans = headStart.whistlePostStopCacheHitSpans,
                postStopNeuralSpans = headStart.whistlePostStopNeuralSpans,
                baselineTotalSegments = baseline.whistlePostStopNeural,
            )
        }
    }

    private data class PressureOutcome(val stopReason: PrecomputeStopReason, val lane: LaneResult)

    @Suppress("LongParameterList")
    private fun buildEquivalenceReport(
        model: SpeechModel,
        baseline: LaneResult,
        headStart: LaneResult,
        thermal: PressureOutcome,
        lowMemory: PressureOutcome,
        oomPassed: Boolean?,
    ): JSONObject {
        val speedup = if (headStart.stopToPublishedSec > 0.0) {
            baseline.stopToPublishedSec / headStart.stopToPublishedSec
        } else {
            1.0
        }
        return JSONObject()
            .put("engine", model.name)
            .put("equivalencePassed", true)
            .put("thermalAndStallPassed", true)
            .put("thermalStopReason", thermal.stopReason.name)
            .put("thermalPrecomputeCheckpoints", thermal.lane.precomputeCheckpoints)
            .put("thermalPostStopUnits", thermal.lane.postStopUnits)
            .put("lowMemorySuppressionPassed", true)
            .put("lowMemoryStopReason", lowMemory.stopReason.name)
            .put("lowMemoryPrecomputeCheckpoints", lowMemory.lane.precomputeCheckpoints)
            .put("lowMemoryPostStopUnits", lowMemory.lane.postStopUnits)
            .put("oomSuppressionPassed", oomPassed ?: JSONObject.NULL)
            .put("wordCount", baseline.words.size)
            .put("turnCount", baseline.turnCount)
            .put("speakerCount", baseline.speakerCount)
            .put("matchedVoices", JSONArray(baseline.matchedVoices))
            .put("afterStopStopToPublishedSeconds", baseline.stopToPublishedSec)
            .put("headStartStopToPublishedSeconds", headStart.stopToPublishedSec)
            .put("speedupFactor", speedup)
            .put("afterStopPostStopUnits", baseline.postStopUnits)
            .put("headStartPostStopUnits", headStart.postStopUnits)
            .put("headStartPrecomputeCheckpoints", headStart.precomputeCheckpoints)
            .put("headStartResumedFromMs", headStart.resumedFromMs)
            .put("whistleBaselineNeuralSegments", baseline.whistlePostStopNeural)
            .put("whistlePrecomputedPrefixSegments", headStart.whistlePrecomputedSpans.size)
            .put("whistleHeadStartCacheHits", headStart.whistlePostStopCacheHits)
            .put("whistleHeadStartPostStopNeuralSegments", headStart.whistlePostStopNeural)
            .put("whistleRedecodedPrefixSegments", 0)
            .put("afterStopTelemetry", baseline.postStopTelemetry.toJson())
            .put("headStartPrecomputeTelemetry", headStart.precomputeTelemetry?.toJson())
            .put("headStartPostStopTelemetry", headStart.postStopTelemetry.toJson())
            .put("thermalPostStopTelemetry", thermal.lane.postStopTelemetry.toJson())
            .put("lowMemoryPostStopTelemetry", lowMemory.lane.postStopTelemetry.toJson())
    }

    private data class LaneMetrics(
        val preCheckpoints: Int,
        val postUnits: Int,
        val whistlePrecomputedSpans: List<String>,
        val whistleCacheHitSpans: List<String>,
        val whistleNeuralSpans: List<String>,
        val whistleCacheHits: Int,
        val whistleNeural: Int,
        val diarizerTurns: List<HandsetQualificationAudit.ComparableTurn>,
        val stopToPubSec: Double,
        val preSnap: HandsetTelemetryMonitor.Snapshot?,
        val postSnap: HandsetTelemetryMonitor.Snapshot,
    )

    private data class LaneResult(
        val captureOpusSha256: String,
        val decodedPcmSha256: String,
        val words: List<HandsetQualificationAudit.ComparableWord>,
        val turns: List<HandsetQualificationAudit.ComparableTurn>,
        val turnCount: Int,
        val speakerCount: Int,
        val speakerLabels: Map<String, String>,
        val matchedVoices: List<String>,
        val precomputeCheckpoints: Int,
        val postStopUnits: Int,
        val whistlePrecomputedSpans: List<String>,
        val whistlePostStopCacheHitSpans: List<String>,
        val whistlePostStopNeuralSpans: List<String>,
        val whistlePostStopCacheHits: Int,
        val whistlePostStopNeural: Int,
        val resumedFromMs: Long,
        val stopToPublishedSec: Double,
        val precomputeTelemetry: HandsetTelemetryMonitor.Snapshot?,
        val postStopTelemetry: HandsetTelemetryMonitor.Snapshot,
    ) {
        fun toEquivalenceSnapshot() = HandsetQualificationAudit.EquivalenceSnapshot(
            captureOpusSha256 = captureOpusSha256,
            decodedPcmSha256 = decodedPcmSha256,
            words = words,
            turns = turns,
            turnCount = turnCount,
            speakerCount = speakerCount,
            speakerLabels = speakerLabels,
            matchedVoices = matchedVoices,
        )
    }

    private class LaneCounters {
        val preChunks = AtomicInteger(0)
        val postChunks = AtomicInteger(0)
        val preCheckpoints = AtomicInteger(0)
        val lastPreDoneMs = AtomicReference(0L)
        val checkpointSignal = Channel<Long>(Channel.CONFLATED)
        val whistleSummaries = ArrayList<Bundle>()
        val activeMonitor = AtomicReference<HandsetTelemetryMonitor?>(null)
        val finalDiarizerTurns = AtomicReference<List<HandsetQualificationAudit.ComparableTurn>>(emptyList())
        private val livePreWhistleSpans = ArrayList<String>()
        private val postCacheHitSpans = ArrayList<String>()
        private val postNeuralSpans = ArrayList<String>()

        @Volatile var committedWhistleSpans: List<String> = emptyList()
            private set

        fun recordCheckpoint(doneMs: Long): Int {
            committedWhistleSpans = synchronized(livePreWhistleSpans) { livePreWhistleSpans.toList() }
            val count = preCheckpoints.incrementAndGet()
            lastPreDoneMs.set(doneMs)
            checkpointSignal.trySend(doneMs)
            return count
        }

        fun clearPostStopWhistle() {
            synchronized(whistleSummaries) { whistleSummaries.clear() }
            synchronized(postCacheHitSpans) { postCacheHitSpans.clear() }
            synchronized(postNeuralSpans) { postNeuralSpans.clear() }
            finalDiarizerTurns.set(emptyList())
        }

        fun installHooks(onChunk: ((Int) -> Unit)? = null) {
            TestHooks.speakerPrefixOverride = { "diar_d1126_" }
            TestHooks.enginesOverride = { _, raw, precompute ->
                wrapCountingEngines(raw, precompute, preChunks, postChunks, finalDiarizerTurns, onChunk)
            }
            TestHooks.onWhistleSegment = { seg, precompute -> recordWhistleSegment(seg, precompute) }
            TestHooks.onWhistleFinish = { summary ->
                synchronized(whistleSummaries) { whistleSummaries.add(summary) }
                activeMonitor.get()?.recordWhistleWorkerCpuMs(summary.getLong("workerCpuMs"))
            }
            TestHooks.onPrecomputeCheckpointed = { _, doneMs -> recordCheckpoint(doneMs) }
        }

        private fun recordWhistleSegment(seg: Bundle, precompute: Boolean) {
            val span = WhistlePrefixAudit.spanKey(
                seg.getLong("start"),
                seg.getLong("end"),
                seg.getString("pcmSha256").orEmpty(),
            )
            if (precompute) {
                synchronized(livePreWhistleSpans) { livePreWhistleSpans.add(span) }
            } else if (seg.getBoolean("cacheHit")) {
                synchronized(postCacheHitSpans) { postCacheHitSpans.add(span) }
            } else {
                synchronized(postNeuralSpans) { postNeuralSpans.add(span) }
            }
        }

        fun toMetrics(
            stopToPubSec: Double,
            preSnap: HandsetTelemetryMonitor.Snapshot?,
            postSnap: HandsetTelemetryMonitor.Snapshot,
        ): LaneMetrics {
            val postWhistle = synchronized(whistleSummaries) { whistleSummaries.lastOrNull() }
            val hitSpans = synchronized(postCacheHitSpans) { postCacheHitSpans.toList() }
            val inferredSpans = synchronized(postNeuralSpans) { postNeuralSpans.toList() }
            return LaneMetrics(
                preCheckpoints = preCheckpoints.get(),
                postUnits = postChunks.get(),
                whistlePrecomputedSpans = committedWhistleSpans,
                whistleCacheHitSpans = hitSpans,
                whistleNeuralSpans = inferredSpans,
                whistleCacheHits = postWhistle?.getInt("cacheHits") ?: 0,
                whistleNeural = postWhistle?.getInt("neuralTranscribes") ?: 0,
                diarizerTurns = finalDiarizerTurns.get(),
                stopToPubSec = stopToPubSec,
                preSnap = preSnap,
                postSnap = postSnap,
            )
        }
    }

    private suspend fun runPairedLane(
        model: SpeechModel,
        mode: TranscriptionMode,
        track: MultiSpeakerSpeechTrack,
        totalSeconds: Int,
        splitAfterSeconds: Int?,
    ): LaneResult {
        TestHooks.reset()
        val counters = LaneCounters().apply { installHooks() }
        val source = DeterministicOpusCaptureSource(track, totalSeconds, splitAfterSeconds)
        TestHooks.captureSource = source
        graph.speech.choose(model)
        graph.settings.setTranscriptionMode(mode)

        val preMon = if (splitAfterSeconds != null) {
            HandsetTelemetryMonitor.start(
                context,
                isolatedWorkerCpuUnavailable = model == SpeechModel.INCLUDED,
            ).also(counters.activeMonitor::set)
        } else {
            null
        }
        assertTrue(graph.recording.start("D1126 ${model.name} ${mode.name}"))
        val active = awaitRecordingState("Lane active") { it is RecordingState.Active } as RecordingState.Active
        withTimeout(ENCODE_TIMEOUT_MS) { source.prefixCommitted.await() }

        if (splitAfterSeconds != null) {
            val targetMs = (splitAfterSeconds - 28).coerceAtLeast(20) * 1_000L
            withTimeout(PRECOMPUTE_TIMEOUT_MS) {
                while (counters.lastPreDoneMs.get() < targetMs) counters.checkpointSignal.receive()
            }
            source.allowTail()
            withTimeout(ENCODE_TIMEOUT_MS) { source.tailCommitted.await() }
        } else {
            assertEquals("AFTER_STOP must not run precompute", 0, counters.preCheckpoints.get())
        }
        val preSnap = preMon?.finish()
        counters.clearPostStopWhistle()

        val (doc, stopToPubSec, postSnap) = finishAndMeasureStopToPublished(active.noteId, counters.activeMonitor)
        val result = inspectPublishedLane(
            noteId = active.noteId,
            model = model,
            doc = doc,
            metrics = counters.toMetrics(stopToPubSec, preSnap, postSnap),
        )
        cleanupNote(active.noteId)
        TestHooks.reset()
        return result
    }

    private suspend fun runThermalAndStallLane(
        model: SpeechModel,
        track: MultiSpeakerSpeechTrack,
        totalSeconds: Int,
        splitAfterSeconds: Int,
    ): PressureOutcome {
        TestHooks.reset()
        val stallEntered = CompletableDeferred<Unit>()
        val releaseStall = CountDownLatch(1)
        val preStopped = Channel<PrecomputeStopReason>(Channel.UNLIMITED)
        val triggerCheckpoint = if (model == SpeechModel.PARAKEET) 2 else 3
        val counters = installThermalHooks(triggerCheckpoint, stallEntered, releaseStall, preStopped)
        val source = DeterministicOpusCaptureSource(track, totalSeconds, splitAfterSeconds)
        TestHooks.captureSource = source
        graph.speech.choose(model)
        graph.settings.setTranscriptionMode(TranscriptionMode.TRANSCRIBE_WHILE_RECORDING)

        assertTrue(graph.recording.start("D1126 ${model.name} Thermal"))
        val active = awaitRecordingState("Thermal active") { it is RecordingState.Active } as RecordingState.Active
        withTimeout(ENCODE_TIMEOUT_MS) { source.prefixCommitted.await() }
        withTimeout(PRECOMPUTE_TIMEOUT_MS) { stallEntered.await() }
        assertTrue("Capture stays Active during inference stall", graph.recording.state.value is RecordingState.Active)
        releaseStall.countDown()

        val stopReason = withTimeout(PRECOMPUTE_TIMEOUT_MS) { preStopped.receive() }
        assertEquals("Thermal throttling must yield precomputation", PrecomputeStopReason.YIELDED, stopReason)
        assertTrue(
            "Precompute must pause within 1 unit of thermal (${counters.preCheckpoints.get()} vs $triggerCheckpoint)",
            counters.preCheckpoints.get() <= triggerCheckpoint + 1,
        )
        source.allowTail()
        withTimeout(ENCODE_TIMEOUT_MS) { source.tailCommitted.await() }
        TestHooks.injectedThermalThrottled.value = false

        counters.clearPostStopWhistle()
        val (doc, stopToPubSec, postSnap) = finishAndMeasureStopToPublished(active.noteId, counters.activeMonitor)
        val lane = inspectPublishedLane(active.noteId, model, doc, counters.toMetrics(stopToPubSec, null, postSnap))
        cleanupNote(active.noteId)
        TestHooks.reset()
        return PressureOutcome(stopReason, lane)
    }

    private fun installThermalHooks(
        triggerCheckpoint: Int,
        stallEntered: CompletableDeferred<Unit>,
        releaseStall: CountDownLatch,
        preStopped: Channel<PrecomputeStopReason>,
    ): LaneCounters {
        val counters = LaneCounters().apply {
            installHooks { chunkIdx ->
                if (chunkIdx == 1 && !stallEntered.isCompleted) {
                    stallEntered.complete(Unit)
                    releaseStall.await(30, TimeUnit.SECONDS)
                }
            }
        }
        TestHooks.onPrecomputeCheckpointed = { _, doneMs ->
            if (counters.recordCheckpoint(doneMs) >= triggerCheckpoint) {
                TestHooks.injectedThermalThrottled.value = true
            }
        }
        TestHooks.onPrecomputeStopped = { _, reason -> preStopped.trySend(reason) }
        return counters
    }

    private suspend fun runLowMemorySuppressionLane(
        model: SpeechModel,
        track: MultiSpeakerSpeechTrack,
        totalSeconds: Int,
    ): PressureOutcome {
        TestHooks.reset()
        val preStopped = Channel<PrecomputeStopReason>(Channel.UNLIMITED)
        val counters = LaneCounters().apply { installHooks() }
        TestHooks.lowMemoryOverride = { true }
        TestHooks.onPrecomputeStopped = { _, reason -> preStopped.trySend(reason) }

        val source = DeterministicOpusCaptureSource(track, totalSeconds, splitAfterSeconds = null)
        TestHooks.captureSource = source
        graph.speech.choose(model)
        graph.settings.setTranscriptionMode(TranscriptionMode.TRANSCRIBE_WHILE_RECORDING)

        assertTrue(graph.recording.start("D1126 ${model.name} LowMemory"))
        val active = awaitRecordingState("LowMemory active") { it is RecordingState.Active } as RecordingState.Active
        withTimeout(ENCODE_TIMEOUT_MS) { source.prefixCommitted.await() }
        val stopReason = withTimeout(EVENT_TIMEOUT_MS) { preStopped.receive() }
        assertEquals("Low-memory preflight must report LOW_MEMORY", PrecomputeStopReason.LOW_MEMORY, stopReason)
        assertEquals("Low-memory suppression must save 0 checkpoints", 0, counters.preCheckpoints.get())
        assertTrue(
            "Capture continues uninterrupted under low-memory suppression",
            graph.recording.state.value is RecordingState.Active,
        )

        TestHooks.lowMemoryOverride = null
        counters.clearPostStopWhistle()
        val (doc, stopToPubSec, postSnap) = finishAndMeasureStopToPublished(active.noteId, counters.activeMonitor)
        val lane = inspectPublishedLane(active.noteId, model, doc, counters.toMetrics(stopToPubSec, null, postSnap))
        cleanupNote(active.noteId)
        TestHooks.reset()
        return PressureOutcome(stopReason, lane)
    }

    private suspend fun finishAndMeasureStopToPublished(
        noteId: String,
        activeMonitor: AtomicReference<HandsetTelemetryMonitor?>,
    ): Triple<CassiniDocument, Double, HandsetTelemetryMonitor.Snapshot> {
        val postMon = HandsetTelemetryMonitor.start(context).also(activeMonitor::set)
        val stopStartedNs = SystemClock.elapsedRealtimeNanos()
        graph.recording.finish()
        val ended = awaitRecordingState("Recording ended") { it is RecordingState.Ended } as RecordingState.Ended
        assertEquals(RecordingOutcome.Saved(AfterFinish.TRANSCRIBE), ended.outcome)
        val doc = awaitNotePublished(noteId, PUBLISH_TIMEOUT_MS)
        val stopToPubSec = (SystemClock.elapsedRealtimeNanos() - stopStartedNs) / 1_000_000_000.0
        val postSnap = postMon.finish()
        activeMonitor.set(null)
        graph.recording.dismissEnded()
        return Triple(doc, stopToPubSec, postSnap)
    }

    private suspend fun runOomSuppressionCheck(track: MultiSpeakerSpeechTrack): Boolean {
        TestHooks.reset()
        val throwOnce = AtomicBoolean(true)
        val oomReasons = Channel<PrecomputeStopReason>(Channel.UNLIMITED)
        TestHooks.enginesOverride = { _, raw, precompute ->
            if (!precompute) {
                raw
            } else {
                JobEngines(
                    recognizers = RecognizerFactory { restored, cb ->
                        if (throwOnce.getAndSet(false)) throw OutOfMemoryError("Injected precompute OOM")
                        raw.recognizers.open(restored, cb)
                    },
                    diarizers = raw.diarizers,
                    prints = raw.prints,
                    audio = raw.audio,
                    encoders = raw.encoders,
                )
            }
        }
        TestHooks.onPrecomputeStopped = { _, reason -> oomReasons.trySend(reason) }
        val source = DeterministicOpusCaptureSource(track, totalSeconds = 20, splitAfterSeconds = null)
        TestHooks.captureSource = source
        graph.speech.choose(SpeechModel.PARAKEET)
        graph.settings.setTranscriptionMode(TranscriptionMode.TRANSCRIBE_WHILE_RECORDING)

        assertTrue(graph.recording.start("D1126 OOM Control"))
        val active = awaitRecordingState("OOM control active") { it is RecordingState.Active } as RecordingState.Active
        withTimeout(ENCODE_TIMEOUT_MS) { source.prefixCommitted.await() }
        val reason = withTimeout(EVENT_TIMEOUT_MS) { oomReasons.receive() }
        val evaluated = HandsetQualificationAudit.evaluateOomSuppression(
            exercised = true,
            observedStopReason = reason.name,
        ) == true
        assertTrue(
            "Capture continues uninterrupted after precompute OOM",
            graph.recording.state.value is RecordingState.Active,
        )

        graph.settings.setTranscriptionMode(TranscriptionMode.RECORD_ONLY)
        graph.recording.finish()
        awaitRecordingState("OOM control ended") { it is RecordingState.Ended }
        graph.recording.dismissEnded()
        cleanupNote(active.noteId)
        TestHooks.reset()
        return evaluated
    }

    @Manual
    @Test
    fun test03ThirtyMinuteContinuousQualification() = runBlocking {
        val cfg = requireQualConfig()
        val engineArg = cfg.longEngine
        assumeTrue("Pass -e longEngine parakeet or whistle", engineArg == "parakeet" || engineArg == "whistle")
        grantRecordAudio()

        val model = prepareLongRunModel(engineArg)
        val track = withContext(Dispatchers.IO) {
            MultiSpeakerSpeechTrack.fromAssets(context, instrumentation.context)
        }
        val enrolled = MultiSpeakerSpeechTrack.enrollThreeVoices(graph, track)
        enableFullSpeakerSettings()
        graph.speech.choose(model)
        graph.settings.setTranscriptionMode(TranscriptionMode.TRANSCRIBE_WHILE_RECORDING)

        val counters = LaneCounters().apply { installHooks() }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitVisible(true)
            TestHooks.captureSource = null
            val observer = ProductionOpusEnduranceObserver(track, cfg.longSeconds) { sec ->
                sendStream("[30min-$engineArg] ${sec}s/${cfg.longSeconds}s cp=${counters.preCheckpoints.get()}\n")
            }
            TestHooks.opusReadHook = observer
            val recMonitor = HandsetTelemetryMonitor.start(
                context,
                isolatedWorkerCpuUnavailable = model == SpeechModel.INCLUDED,
            ).also(counters.activeMonitor::set)
            assertTrue(graph.recording.start("D1126 30min $engineArg"))
            val active = awaitRecordingState("30-min active") { it is RecordingState.Active } as RecordingState.Active
            assertEquals("OpusCapture", TestHooks.lastStartedCaptureClass)
            val health = withTimeout((cfg.longSeconds + 300L) * 1_000L) { observer.targetReached.await() }
            val recSnap = recMonitor.finish()

            counters.clearPostStopWhistle()
            val (doc, stopToPubSec, postSnap) = finishAndMeasureStopToPublished(active.noteId, counters.activeMonitor)
            assertLongCaptureInvulnerability(health, doc.audioDurationMs, cfg.longSeconds)
            val metrics = counters.toMetrics(stopToPubSec, recSnap, postSnap)
            val lane = inspectPublishedLane(active.noteId, model, doc, metrics)
            verifyLongLaneOutput(model, lane, cfg.longSeconds * 1_000L)
            val report = buildThirtyMinuteReport(
                engineArg,
                cfg.longSeconds,
                enrolled.names,
                counters.preChunks.get(),
                doc.audioDurationMs,
                health,
                lane,
            )
            cleanupNote(active.noteId)
            TestHooks.reset()
            emitReport("test03-30min-$engineArg", report)
        }
    }

    private fun verifyLongLaneOutput(model: SpeechModel, lane: LaneResult, expectedDurationMs: Long) {
        HandsetQualificationAudit.verifyLongLaneProgress(
            HandsetQualificationAudit.LongLaneProgress(
                expectedDurationMs = expectedDurationMs,
                precomputeCheckpoints = lane.precomputeCheckpoints,
                resumedFromMs = lane.resumedFromMs,
                wordCount = lane.words.size,
                lastWordEndMs = lane.words.maxOfOrNull { it.endMs } ?: 0L,
            ),
        )
        if (model == SpeechModel.INCLUDED) {
            WhistlePrefixAudit.verifyNoNeuralPrefixRedecode(
                precomputedPrefixSpans = lane.whistlePrecomputedSpans,
                postStopCacheHitSpans = lane.whistlePostStopCacheHitSpans,
                postStopNeuralSpans = lane.whistlePostStopNeuralSpans,
            )
        }
    }

    private suspend fun prepareLongRunModel(engineArg: String): SpeechModel {
        val model = if (engineArg == "whistle") SpeechModel.INCLUDED else SpeechModel.PARAKEET
        if (model == SpeechModel.INCLUDED) {
            val ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                WhistleDeviceCapability.current() == WhistleCapability.ELIGIBLE
            check(ok) { "Whistle is not eligible on this device" }
        }
        val parts = if (model == SpeechModel.PARAKEET) {
            setOf(ModelPart.SPEECH, ModelPart.SPEAKERS, ModelPart.VOICES)
        } else {
            setOf(ModelPart.SPEAKERS, ModelPart.VOICES)
        }
        ensureModelsInstalled(parts)
        return model
    }

    private fun assertLongCaptureInvulnerability(
        health: ProductionOpusEnduranceObserver.CaptureHealth,
        publishedDurationMs: Long,
        expectedSeconds: Int,
    ) {
        HandsetQualificationAudit.verifyCaptureHealth(
            summary = health.halSummary,
            maxFrameIntervalMs = health.maxFrameIntervalMs,
            ringBufferMs = health.ringBufferMs,
        )
        assertTrue(
            "Published duration ($publishedDurationMs ms) within 2s of ${expectedSeconds * 1000L} ms",
            abs(publishedDurationMs - expectedSeconds * 1_000L) <= 2_000L,
        )
    }

    @Suppress("LongParameterList")
    private fun buildThirtyMinuteReport(
        engineArg: String,
        longSeconds: Int,
        enrolledNames: List<String>,
        preUnitsDecoded: Int,
        publishedDurationMs: Long,
        health: ProductionOpusEnduranceObserver.CaptureHealth,
        lane: LaneResult,
    ): JSONObject = JSONObject()
        .put("test", "test03ThirtyMinuteContinuousQualification")
        .put("captureClass", "OpusCapture")
        .put("engine", engineArg)
        .put("audioSeconds", longSeconds)
        .put("enrolledVoices", JSONArray(enrolledNames))
        .put("precomputeCheckpointsSaved", lane.precomputeCheckpoints)
        .put("precomputeUnitsDecoded", preUnitsDecoded)
        .put("postStopUnitsDecoded", lane.postStopUnits)
        .put("resumedFromMs", lane.resumedFromMs)
        .put("whistlePrecomputedPrefixSegments", lane.whistlePrecomputedSpans.size)
        .put("whistlePostStopCacheHits", lane.whistlePostStopCacheHits)
        .put("whistlePostStopNeuralSegments", lane.whistlePostStopNeural)
        .put("whistleRedecodedPrefixSegments", 0)
        .put("stopToPublishedSeconds", lane.stopToPublishedSec)
        .put("wordCount", lane.words.size)
        .put("turnCount", lane.turnCount)
        .put("speakerCount", lane.speakerCount)
        .put("matchedVoices", JSONArray(lane.matchedVoices))
        .put("publishedDurationMs", publishedDurationMs)
        .put(
            "captureHealth",
            JSONObject()
                .put("captureClass", "OpusCapture")
                .put("capturedSamples", health.capturedSamples)
                .put("capturedSeconds", health.capturedSeconds)
                .put("droppedBuffers", health.halSummary.droppedBuffers ?: JSONObject.NULL)
                .put("halTimestampAvailable", health.halSummary.halTimestampAvailable)
                .put("halTimestampSamples", health.halSummary.halTimestampSamples)
                .put("halDropEvents", health.halSummary.halDropEvents)
                .put("maxHalFrameDeficit", health.halSummary.maxHalFrameDeficit)
                .put("timingDropEvents", health.halSummary.timingDropEvents)
                .put("maxFrameIntervalMs", health.maxFrameIntervalMs)
                .put("ringBufferMs", health.ringBufferMs)
                .put("micPeakAmplitude", health.micPeakAmplitude),
        )
        .put("recordingTelemetry", lane.precomputeTelemetry?.toJson())
        .put("postStopTelemetry", lane.postStopTelemetry.toJson())

    private data class QualConfig(
        val shortSeconds: Int,
        val longSeconds: Int,
        val runParakeet: Boolean,
        val runWhistle: Boolean,
        val longEngine: String,
    )

    private fun requireQualConfig(): QualConfig {
        val args = InstrumentationRegistry.getArguments()
        val enabled = args.getString("cassiniHandsetQual") == "true"
        assumeTrue("Opt-in physical handset qualification (pass -e cassiniHandsetQual true)", enabled)
        return QualConfig(
            shortSeconds = args.getString("shortSeconds")?.toIntOrNull() ?: 300,
            longSeconds = args.getString("longSeconds")?.toIntOrNull() ?: 1_800,
            runParakeet = args.getString("runParakeet") != "false",
            runWhistle = args.getString("runWhistle") != "false",
            longEngine = args.getString("longEngine") ?: "none",
        )
    }

    private suspend fun ensureModelsInstalled(parts: Set<ModelPart>) {
        withContext(Dispatchers.IO) {
            for (part in parts) {
                if (!part.models.all { it.ready(graph.paths.modelRoot) }) {
                    sendStream("[qual] Installing model part ${part.name}...\n")
                    graph.models.installBlocking(part)
                }
            }
        }
        graph.models.refresh()
    }

    private suspend fun awaitVisible(expected: Boolean) {
        withTimeout(EVENT_TIMEOUT_MS) {
            graph.visibility.visible.first { it == expected }
        }
    }

    private suspend fun awaitRecordingState(
        description: String,
        predicate: (RecordingState) -> Boolean,
    ): RecordingState = withTimeout(EVENT_TIMEOUT_MS) {
        graph.recording.state.first(predicate)
    }.also { Log.i(TAG, "$description -> $it") }

    private suspend fun awaitNPicks(ticksToAdvance: Long) {
        val base = TestHooks.policyTicks.value
        withTimeout(EVENT_TIMEOUT_MS) {
            TestHooks.policyTicks.first { it >= base + ticksToAdvance }
        }
    }

    private suspend fun awaitPolicyTicksUntil(description: String, check: () -> Boolean) {
        if (check()) return
        var seen = TestHooks.policyTicks.value
        withTimeout(EVENT_TIMEOUT_MS) {
            while (!check()) {
                seen = TestHooks.policyTicks.first { it > seen }
            }
        }
        Log.i(TAG, "Condition met: $description")
    }

    private suspend fun awaitNotePublished(noteId: String, timeoutMs: Long): CassiniDocument {
        val catalogue = graph.awaitCatalogue()
        val docFile = File(graph.paths.note(noteId), "document.opus")
        withTimeout(timeoutMs) {
            catalogue.notes.library().first { rows ->
                val row = rows.firstOrNull { it.id == noteId }
                row != null && row.jobState == null && docFile.isFile
            }
        }
        return withContext(Dispatchers.IO) { CassiniDocument.read(docFile) }
    }

    private suspend fun inspectPublishedLane(
        noteId: String,
        model: SpeechModel,
        doc: CassiniDocument,
        metrics: LaneMetrics,
    ): LaneResult = withContext(Dispatchers.IO) {
        assertEquals(DocumentState.OK, doc.documentState)
        val variantId = requireNotNull(doc.defaultId)
        val selected = requireNotNull(doc.selected(null)?.transcript)
        val captureFile = File(graph.paths.note(noteId), "capture.opus")
        val docFile = File(graph.paths.note(noteId), "document.opus")
        val captureSha = if (captureFile.isFile) sha256File(captureFile) else sha256AudioPackets(docFile)
        val pcmSha = sha256DecodedPcm16k(docFile, included = model == SpeechModel.INCLUDED)

        val speechMap = doc.manifest!!.getJSONObject("provenance").getJSONObject("speechToText")
        val speechProv = speechMap.getJSONObject(variantId)
        val resumedFromMs = speechProv.optLong("x-resumedFromMs", 0L)
        val diar = speechProv.optJSONObject("x-speakerDiarization")

        val speakerLabels = LinkedHashMap<String, String>()
        val speakersArr = doc.manifest?.optJSONArray("speakers")
        if (speakersArr != null) {
            for (i in 0 until speakersArr.length()) {
                val obj = speakersArr.getJSONObject(i)
                speakerLabels[obj.getString("id")] = obj.getString("label")
            }
        }

        val catalogue = graph.awaitCatalogue()
        val peopleById = catalogue.voices.peopleBlocking().associate { it.id to it.name }
        val prints = catalogue.voices.printsBlocking(noteId, variantId)
        val matchedVoices = prints.entries
            .mapNotNull { (spkId, sp) ->
                val m = sp.match ?: return@mapNotNull null
                val voiceName = peopleById[m.voiceId] ?: m.voiceId
                "$spkId:$voiceName:${m.state.name}"
            }
            .sorted()

        LaneResult(
            captureOpusSha256 = captureSha,
            decodedPcmSha256 = pcmSha,
            words = selected.words.map {
                HandsetQualificationAudit.ComparableWord(it.text, it.startMs, it.endMs, it.speaker)
            },
            turns = metrics.diarizerTurns,
            turnCount = diar?.optInt("turnCount", 0) ?: 0,
            speakerCount = diar?.optInt("speakerCount", 0) ?: 0,
            speakerLabels = speakerLabels,
            matchedVoices = matchedVoices,
            precomputeCheckpoints = metrics.preCheckpoints,
            postStopUnits = metrics.postUnits,
            whistlePrecomputedSpans = metrics.whistlePrecomputedSpans,
            whistlePostStopCacheHitSpans = metrics.whistleCacheHitSpans,
            whistlePostStopNeuralSpans = metrics.whistleNeuralSpans,
            whistlePostStopCacheHits = metrics.whistleCacheHits,
            whistlePostStopNeural = metrics.whistleNeural,
            resumedFromMs = resumedFromMs,
            stopToPublishedSec = metrics.stopToPubSec,
            precomputeTelemetry = metrics.preSnap,
            postStopTelemetry = metrics.postSnap,
        )
    }

    private suspend fun cleanupNote(noteId: String) {
        val catalogue = graph.awaitCatalogue()
        if (catalogue.jobs.job(noteId) != null) catalogue.jobs.remove(noteId)
        graph.processing.moveToTrash(noteId)
        withContext(Dispatchers.IO) {
            graph.paths.note(noteId).deleteRecursively()
            catalogue.voices.deleteForNote(noteId)
        }
    }

    private suspend fun exerciseAudioFocusTransientLossAndGain(): List<String> {
        val observed = ArrayList<String>()
        val transientLostDeferred = CompletableDeferred<Unit>()
        val gainedAfterLossDeferred = CompletableDeferred<Unit>()
        val sawTransientLoss = AtomicBoolean(false)
        val platform = AndroidPlaybackPlatform(context)
        var primaryGranted = false
        instrumentation.runOnMainSync {
            primaryGranted = platform.requestFocus { change ->
                synchronized(observed) { observed.add(change.name) }
                if (change == FocusChange.LOST_FOR_NOW) {
                    sawTransientLoss.set(true)
                    transientLostDeferred.complete(Unit)
                } else if (change == FocusChange.GAINED && sawTransientLoss.get()) {
                    gainedAfterLossDeferred.complete(Unit)
                }
            }
        }
        var competing: AudioFocusRequest? = null
        var competingGranted = false
        try {
            assertTrue("Primary audio-focus request must be granted", primaryGranted)
            competing = requestTransientFocus()
            competingGranted = competing != null
            assertNotNull("Competing transient audio-focus request must be granted", competing)
            withTimeout(EVENT_TIMEOUT_MS) { transientLostDeferred.await() }
            abandonTransientFocus(competing!!)
            competing = null
            withTimeout(EVENT_TIMEOUT_MS) { gainedAfterLossDeferred.await() }
        } finally {
            competing?.let { runCatching { abandonTransientFocus(it) } }
            instrumentation.runOnMainSync { platform.abandonFocus() }
        }
        val snapshot = synchronized(observed) { observed.toList() }
        HandsetQualificationAudit.verifyTransientAudioFocusTransition(
            primaryGranted = primaryGranted,
            competingGranted = competingGranted,
            observedEvents = snapshot,
        )
        return snapshot
    }

    private fun requestTransientFocus(): AudioFocusRequest? {
        val manager = context.getSystemService(AudioManager::class.java) ?: return null
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener {}
            .build()
        return if (manager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) req else null
    }

    private fun abandonTransientFocus(request: AudioFocusRequest) {
        context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
    }

    private fun grantRecordAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Background.grant(Manifest.permission.RECORD_AUDIO)
        } else {
            Background.shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        }
    }

    private fun verifyNoisyBroadcastHook() {
        val capturedReceiver = AtomicReference<BroadcastReceiver?>(null)
        val noisyLatch = CountDownLatch(1)
        val wrapper = NoisyCapturingContext(context, capturedReceiver)
        val platform = AndroidPlaybackPlatform(wrapper)
        instrumentation.runOnMainSync {
            platform.watchNoisy { noisyLatch.countDown() }
            val rx = capturedReceiver.get()
            assertNotNull("AndroidPlaybackPlatform.watchNoisy must register receiver", rx)
            rx!!.onReceive(context, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
        assertTrue("Noisy route-change callback must fire", noisyLatch.await(5, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { platform.unwatchNoisy() }
    }

    private class NoisyCapturingContext(
        base: Context,
        private val captured: AtomicReference<BroadcastReceiver?>,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        private fun captureIfNoisy(receiver: BroadcastReceiver?, filter: IntentFilter?) {
            if (filter?.hasAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY) == true) {
                captured.set(receiver)
            }
        }

        // Forwards system ACTION_AUDIO_BECOMING_NOISY registrations across API 26..35 ContextCompat paths.
        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
            captureIfNoisy(receiver, filter)
            return super.registerReceiver(receiver, filter)
        }

        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, flags: Int): Intent? {
            captureIfNoisy(receiver, filter)
            return super.registerReceiver(receiver, filter, flags)
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter?,
            broadcastPermission: String?,
            scheduler: Handler?,
        ): Intent? {
            captureIfNoisy(receiver, filter)
            return super.registerReceiver(receiver, filter, broadcastPermission, scheduler)
        }

        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter?,
            broadcastPermission: String?,
            scheduler: Handler?,
            flags: Int,
        ): Intent? {
            captureIfNoisy(receiver, filter)
            return super.registerReceiver(receiver, filter, broadcastPermission, scheduler, flags)
        }
    }

    private fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256AudioPackets(opusFile: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val parsed = OggOpus.read(opusFile.readBytes())
        md.update(parsed.head)
        for (pkt in parsed.audio) md.update(pkt)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256DecodedPcm16k(opusFile: File, included: Boolean): String {
        val md = MessageDigest.getInstance("SHA-256")
        openDecoder(context, included).stream(
            Uri.fromFile(opusFile),
            { samples, count ->
                val bb = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) bb.putFloat(samples[i])
                md.update(bb.array())
            },
        )
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private val CassiniDocument.audioDurationMs: Long
        get() = manifest?.optJSONObject("audio")?.optLong("durationMs") ?: 0L

    private fun openDecoder(ctx: Context, included: Boolean) =
        AudioDecoder(ctx.contentResolver, ctx.cacheDir, included = included)

    private fun sendStream(text: String) {
        Log.i(TAG, text.trimEnd())
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", text) })
    }

    private fun emitReport(name: String, report: JSONObject) {
        HandsetQualificationAudit.ReportStore.append(context.filesDir, report)
        sendStream("\nCASSINI_HANDSET_QUAL_BEGIN:$name\n$report\nCASSINI_HANDSET_QUAL_END:$name\n")
    }

    private companion object {
        const val TAG = "CassiniHandsetQual"
        const val EVENT_TIMEOUT_MS = 30_000L
        const val BACKGROUND_DURATION_MS = 300_000L
        const val BACKGROUND_TIMEOUT_MS = BACKGROUND_DURATION_MS + 60_000L
        const val ENCODE_TIMEOUT_MS = 120_000L
        const val PRECOMPUTE_TIMEOUT_MS = 1_200_000L
        const val PUBLISH_TIMEOUT_MS = 1_800_000L
        const val FINISH_TIMEOUT_MS = 30_000L

        @Suppress("LongParameterList")
        fun wrapCountingEngines(
            raw: JobEngines,
            precompute: Boolean,
            preChunks: AtomicInteger,
            postChunks: AtomicInteger,
            finalDiarizerTurns: AtomicReference<List<HandsetQualificationAudit.ComparableTurn>>,
            onPrecomputeChunk: ((Int) -> Unit)?,
        ): JobEngines = JobEngines(
            recognizers = RecognizerFactory { restored, cb ->
                raw.recognizers.open(
                    restored,
                    Recognizer.Callbacks(
                        onChunk = { chunk, startMs, words ->
                            if (precompute) {
                                val idx = preChunks.incrementAndGet()
                                onPrecomputeChunk?.invoke(idx)
                            } else {
                                postChunks.incrementAndGet()
                            }
                            cb.onChunk(chunk, startMs, words)
                        },
                        onStage = cb.onStage,
                        onProgress = cb.onProgress,
                        checkReplayActive = cb.checkReplayActive,
                    ),
                )
            },
            diarizers = if (precompute) {
                raw.diarizers
            } else {
                DiarizerFactory { restored ->
                    val delegate = raw.diarizers.open(restored)
                    object : StreamingDiarizer by delegate {
                        override fun close() {
                            finalDiarizerTurns.set(
                                delegate.turns.map {
                                    HandsetQualificationAudit.ComparableTurn(it.speaker, it.startMs, it.endMs)
                                },
                            )
                            delegate.close()
                        }
                    }
                }
            },
            prints = raw.prints,
            audio = raw.audio,
            encoders = raw.encoders,
        )
    }
}
