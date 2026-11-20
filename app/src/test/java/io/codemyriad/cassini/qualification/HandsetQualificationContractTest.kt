package io.codemyriad.cassini.qualification

import io.codemyriad.cassini.core.asr.IncrementalState
import io.codemyriad.cassini.core.asr.TranscriptionMode
import io.codemyriad.cassini.core.checkpoint.CheckpointOrigin
import io.codemyriad.cassini.core.checkpoint.CheckpointState
import io.codemyriad.cassini.core.checkpoint.CommittedPrefix
import io.codemyriad.cassini.core.checkpoint.PrefixDecision
import io.codemyriad.cassini.core.checkpoint.RecognitionState
import io.codemyriad.cassini.core.checkpoint.WordLogRef
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.OggOpus
import io.codemyriad.cassini.core.format.OggOpusPrefixScanner
import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.core.models.SpeechModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Deterministic JVM contract test for the D-1126 handset qualification invariants (zero sleeps, D88):
 * - Live Ogg Opus committed prefix bytes match EOS-sealed `.opus` container bytes via [OggOpusPrefixScanner].
 * - `CheckpointState.validateWithResult` accepts valid `oggOpusCommittedBytes` precompute checkpoints after EOS
 *   finalization (`length:mtime` changed) and silently falls back (`DISCARD_AND_FULL_DECODE`) on AAC or modified
 *   prefixes without ever failing the user's job.
 */
class HandsetQualificationContractTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun liveOggOpusPrefixMatchesFinalizedEosContainerAndValidatesPrecomputeCheckpoint() {
        val file = temp.newFile("capture.opus")
        val packet = ByteArray(48) { i -> if (i == 0) 0xfc.toByte() else (i * 13 + 3).toByte() }
        val out = FileOutputStream(file)
        val muxer = OggOpus.LiveMuxer(out, opusHead(), CassiniDocument.tagsPacket(emptyList()), serial = 0xCA55126)
        repeat(200) { muxer.add(packet) }
        out.flush()

        val scanner = OggOpusPrefixScanner(file)
        assertTrue(scanner.poll().isNotEmpty())
        val committedBytes = scanner.committedBytes
        assertTrue(committedBytes > 0L)
        assertFalse(scanner.sawEos)
        val liveSha = scanner.committedSha256()
        val checkpointJson = precomputeCheckpointJson(file, committedBytes, liveSha)

        // Append tail frames and seal EOS page (changing file length & signature, as OpusCapture.stop() does).
        repeat(50) { muxer.add(packet) }
        muxer.finish(312L + 250L * 960L)
        out.flush()
        out.close()

        val finalSha = OggOpusPrefixScanner.sha256Prefix(file, committedBytes)
        assertEquals(liveSha, finalSha)

        val expectedOpus = expectedFor(file, CommittedPrefix.KIND_OGG_OPUS_BYTES, finalSha)
        assertEquals(PrefixDecision.RESUME, CheckpointState.validateWithResult(checkpointJson, expectedOpus).decision)

        // API 26-28 AAC fallback kind mismatch on a precompute checkpoint must silently discard and full-decode.
        val expectedAac = expectedFor(file, CommittedPrefix.KIND_AAC_M4A, null)
        assertEquals(
            PrefixDecision.DISCARD_AND_FULL_DECODE,
            CheckpointState.validateWithResult(checkpointJson, expectedAac).decision,
        )
    }

    @Test
    fun transcriptionModePolicySkipsAacAndPreemptsOnForegroundWork() {
        val opusOk = TranscriptionMode.evaluate(
            rawMode = "transcribeWhileRecording",
            hasForegroundSavedJob = false,
            manualTranscribeRequested = false,
            platformSupportsPrecompute = true,
        )
        assertTrue(opusOk.spawnWorkerDuringRecording)
        assertTrue(opusOk.autoEnqueueOnStop)

        val aacFallback = TranscriptionMode.evaluate(
            rawMode = "transcribeWhileRecording",
            hasForegroundSavedJob = false,
            manualTranscribeRequested = false,
            platformSupportsPrecompute = false,
        )
        assertFalse(
            "AAC capture must never spawn Ogg-Opus prefix precomputation",
            aacFallback.spawnWorkerDuringRecording,
        )
        assertTrue("AAC capture must still auto-enqueue post-Stop transcription", aacFallback.autoEnqueueOnStop)
    }

    private fun opusHead(): ByteArray = ByteArray(19).also { h ->
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(h, 0)
        h[8] = 1
        h[9] = 1
        ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).putShort(10, 312).putInt(12, 48_000)
    }

    private fun precomputeCheckpointJson(file: File, committedBytes: Long, liveSha: String): JSONObject {
        val state = CheckpointState(
            source = file.name,
            signature = CheckpointState.signature(file),
            model = SpeechModel.PARAKEET.identity,
            speakers = ModelCatalog.diarizationModel.sha256,
            recognition = RecognitionState(100L, IncrementalState(32_000L, 32_000L, FloatArray(0), emptyList())),
            encoder = null,
            diarizer = null,
            speakerPrefix = "diar_qual_",
            speakerFailure = false,
            clipSamples = 0,
            voiceClips = emptyMap(),
            positionSamples = 32_000L,
            doneMs = 2_000L,
            durationMs = 0L,
            elapsedMs = 150L,
            origin = CheckpointOrigin.PRECOMPUTE,
            committedPrefix = CommittedPrefix(
                kind = CommittedPrefix.KIND_OGG_OPUS_BYTES,
                committedUnitCount = committedBytes,
                prefixSha256 = liveSha,
            ),
        )
        return CheckpointState.encode(state, WordLogRef.EMPTY)
    }

    private fun expectedFor(file: File, kind: String, prefixSha: String?) = CheckpointState.Expected(
        source = file.name,
        signature = CheckpointState.signature(file),
        modelRevision = SpeechModel.PARAKEET.identity,
        speakersSha256 = ModelCatalog.diarizationModel.sha256,
        clipsBytes = 0L,
        audioBytes = 0L,
        wordLogBytes = 0L,
        prefixKind = kind,
        finalizedUnitCount = file.length(),
        prefixSha256 = prefixSha,
        deferDecodedPcmCheck = true,
    )
}
