package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.audio.Limits
import io.codemyriad.cassini.core.checkpoint.CheckpointNames
import io.codemyriad.cassini.core.checkpoint.CheckpointOrigin
import io.codemyriad.cassini.core.checkpoint.CheckpointState
import io.codemyriad.cassini.core.checkpoint.DiarizerState
import io.codemyriad.cassini.core.checkpoint.EncoderState
import io.codemyriad.cassini.core.checkpoint.RecognitionState
import io.codemyriad.cassini.core.checkpoint.WordLog
import io.codemyriad.cassini.core.checkpoint.WordLogRef
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.core.speakers.PrintWindow
import io.codemyriad.cassini.engine.job.JobEnvironment
import io.codemyriad.cassini.engine.job.JobStorage
import io.codemyriad.cassini.engine.job.JobVoices
import io.codemyriad.cassini.engine.job.ModelAccess
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

@Suppress("LongParameterList") // Snapshot mirrors CheckpointState fields cleanly.
internal class StagedSnapshot(
    val recognition: RecognitionState,
    val encoder: EncoderState?,
    val diarizer: DiarizerState?,
    val speakerPrefix: String,
    val speakerFailure: Boolean,
    val clipSamples: Int,
    val voiceClips: Map<String, List<PrintWindow>>?,
    val positionSamples: Long,
    val doneMs: Long,
    val elapsedMs: Long,
) {
    fun toCheckpointState(
        source: String,
        signature: String,
        speechModel: SpeechModel,
    ): CheckpointState = CheckpointState(
        source = source,
        signature = signature,
        model = speechModel.identity,
        speakers = ModelCatalog.diarizationModel.sha256,
        recognition = recognition,
        encoder = encoder,
        diarizer = diarizer,
        speakerPrefix = speakerPrefix,
        speakerFailure = speakerFailure,
        clipSamples = clipSamples,
        voiceClips = voiceClips,
        positionSamples = positionSamples,
        doneMs = doneMs,
        durationMs = 0L,
        elapsedMs = elapsedMs,
        origin = CheckpointOrigin.PRECOMPUTE,
        committedPrefix = null,
    )

    companion object {
        fun fromCheckpointState(state: CheckpointState): StagedSnapshot = StagedSnapshot(
            recognition = state.recognition,
            encoder = state.encoder,
            diarizer = state.diarizer,
            speakerPrefix = state.speakerPrefix,
            speakerFailure = state.speakerFailure,
            clipSamples = state.clipSamples,
            voiceClips = state.voiceClips,
            positionSamples = state.positionSamples,
            doneMs = state.doneMs,
            elapsedMs = state.elapsedMs,
        )
    }
}

/**
 * Append-only settled word log inside a `.part.precompute/` staging directory so multi-hour live
 * checkpoints write only newly settled words per window instead of rewriting the whole transcript.
 */
internal class StagedWordLog(private val stagingDir: File) {
    private var cursor: Cursor? = null

    private class Cursor(val ref: WordLogRef, val last: Word?)

    fun save(state: CheckpointState): WordLogRef {
        val chunks = state.recognition.chunks
        val words = chunks.words
        val log = File(stagingDir, WORK_WORD_LOG)
        val active = cursor?.takeIf { continues(it, words, log) }
        val from = active?.ref?.count ?: 0
        val settled = WordLog.settled(words, chunks.start * MS_PER_SECOND / Limits.ASR_RATE, from)
        val ref = when {
            active != null && settled == from -> active.ref
            active != null -> append(log, active.ref, WordLog.lines(words, from, settled), settled)
            else -> rewrite(log, WordLog.lines(words, 0, settled), settled)
        }
        cursor = Cursor(ref, words.getOrNull(ref.count - 1))
        return ref
    }

    private fun continues(active: Cursor, words: List<Word>, log: File): Boolean {
        val count = active.ref.count
        return count <= words.size &&
            (count == 0 || words[count - 1] == active.last) &&
            log.length() >= active.ref.bytes
    }

    private fun append(log: File, base: WordLogRef, lines: ByteArray, count: Int): WordLogRef {
        RandomAccessFile(log, "rw").use { file ->
            file.setLength(base.bytes)
            file.seek(base.bytes)
            file.write(lines)
            file.fd.sync()
        }
        return WordLogRef(count, base.bytes + lines.size)
    }

    private fun rewrite(log: File, lines: ByteArray, count: Int): WordLogRef {
        if (count == 0) {
            if (log.exists() && !log.delete()) throw IOException("Cannot delete $log")
            return WordLogRef.EMPTY
        }
        writeAtomically(log, lines)
        return WordLogRef(count, lines.size.toLong())
    }

    companion object {
        private const val WORK_WORD_LOG = "words.log"
        private const val MS_PER_SECOND = 1_000L

        fun read(stagingDir: File, json: JSONObject): List<Word> {
            val ref = CheckpointState.wordLog(json)
            if (ref.count == 0) return emptyList()
            val log = File(stagingDir, WORK_WORD_LOG)
            require(ref.bytes <= Int.MAX_VALUE) { "Staged word log prefix too large: ${ref.bytes}" }
            val bytes = ByteArray(ref.bytes.toInt())
            RandomAccessFile(log, "r").use { it.readFully(bytes) }
            return WordLog.read(bytes, ref.count)
        }

        private fun writeAtomically(target: File, bytes: ByteArray) {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                val replaced = tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))
                if (!replaced) throw IOException("Cannot replace ${target.name}")
            } catch (error: IOException) {
                tmp.delete()
                throw error
            }
        }
    }
}

/**
 * Durable filesystem store for [RecorderLivePrecomputer] staging (`<part>.precompute/`): preserves progress
 * across BT disconnects, preemptions, and reconnects after Stop, verifying PCM slices (never the 44 B WAV
 * header, which is rewritten upon Stop) before resuming or installing into `notes/<id>/work/`.
 */
@Suppress("TooManyFunctions") // Focused helpers for stage path, save, verify/load, and install.
internal object RecorderLivePrecomputeStore {
    const val WORK_AUDIO_PART = "audio.opus.part"
    const val WORK_CLIPS = "clips.pcm"
    const val WORK_WHISTLE_SEGMENTS = "whistle-segments.json"

    private const val PRECOMPUTE_SUFFIX = ".precompute"
    private const val CHECKPOINT_FILE = "checkpoint.json"
    private const val SOURCE_FILE = "live-source.json"
    private const val WORD_LOG_FILE = "words.log"
    private const val TMP_SUFFIX = ".tmp"
    private const val BYTES_PER_FLOAT = 4L
    private const val BYTES_PER_SAMPLE = 2L
    private const val HEAD_PCM_HASH_BYTES = 65_536L
    private const val TAIL_PCM_HASH_BYTES = 16_384L
    private const val IO_BUF_BYTES = 32 * 1024
    private const val WAV_HEADER_MIN = 44L

    fun stagingDirFor(partFile: File): File =
        File(partFile.parentFile, "${partFile.name}$PRECOMPUTE_SUFFIX")

    fun clearStagingFiles(stagingDir: File) {
        File(stagingDir, WORK_AUDIO_PART).delete()
        File(stagingDir, WORK_CLIPS).delete()
        File(stagingDir, WORD_LOG_FILE).delete()
        File(stagingDir, CHECKPOINT_FILE).delete()
        File(stagingDir, SOURCE_FILE).delete()
        File(stagingDir, WORK_WHISTLE_SEGMENTS).delete()
    }

    @Suppress("LongParameterList") // Carries live WAV format offsets alongside staged state.
    fun saveStaged(
        partFile: File,
        stagingDir: File,
        wordLog: StagedWordLog,
        snapshot: StagedSnapshot,
        speechModel: SpeechModel,
        sampleRate: Int,
        channels: Int,
        dataOffset: Long,
    ) {
        val frameBytes = BYTES_PER_SAMPLE * channels
        val rawVerified = dataOffset + (snapshot.positionSamples * sampleRate / Limits.ASR_RATE) * frameBytes
        val partLength = partFile.length()
        val verifiedBytes = minOf(rawVerified, partLength)
        if (dataOffset < WAV_HEADER_MIN || verifiedBytes <= dataOffset) return

        val (headSha, tailSha) = hashPcmSlices(partFile, dataOffset, verifiedBytes)
        val sourceJson = JSONObject()
            .put("sampleRate", sampleRate)
            .put("channels", channels)
            .put("dataOffset", dataOffset)
            .put("verifiedBytes", verifiedBytes)
            .put("headPcmSha256", headSha)
            .put("tailPcmSha256", tailSha)

        val ckptState = snapshot.toCheckpointState(
            source = partFile.name,
            signature = "$verifiedBytes:$sampleRate:$channels:$dataOffset",
            speechModel = speechModel,
        )
        val ref = wordLog.save(ckptState)
        val checkpoint = CheckpointState.encode(ckptState, ref).put("liveSource", sourceJson)
        writeAtomicallyText(File(stagingDir, CHECKPOINT_FILE), checkpoint.toString())
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught") // Stale or corrupt staging discards cleanly.
    fun loadStaged(
        partFile: File,
        stagingDir: File,
        speechModel: SpeechModel,
        models: ModelAccess,
    ): StagedSnapshot? {
        val ckptFile = File(stagingDir, CHECKPOINT_FILE)
        if (!partFile.isFile || !ckptFile.isFile) return null
        return try {
            val loaded = decodeAndVerify(partFile, stagingDir, speechModel, models)
            if (loaded == null) stagingDir.deleteRecursively()
            loaded
        } catch (_: IOException) {
            stagingDir.deleteRecursively()
            null
        } catch (_: JSONException) {
            stagingDir.deleteRecursively()
            null
        } catch (_: IllegalArgumentException) {
            stagingDir.deleteRecursively()
            null
        } catch (_: IllegalStateException) {
            stagingDir.deleteRecursively()
            null
        }
    }

    @Suppress("ReturnCount") // Validates live WAV source hashes before loading checkpoint and staging artifacts.
    private fun decodeAndVerify(
        partFile: File,
        stagingDir: File,
        speechModel: SpeechModel,
        models: ModelAccess,
    ): StagedSnapshot? {
        val ckptJson = JSONObject(File(stagingDir, CHECKPOINT_FILE).readText(Charsets.UTF_8))
        if (ckptJson.optInt("version", -1) != CheckpointState.VERSION) return null
        // Older staging directories stored the PCM identity in a separate file.
        val source = ckptJson.optJSONObject("liveSource") ?: JSONObject(File(stagingDir, SOURCE_FILE).readText())
        if (!verifyLiveSource(partFile, source, ckptJson.getString("signature"))) return null
        val saved = CheckpointState.decode(ckptJson, StagedWordLog.read(stagingDir, ckptJson))
        val valid = isCompatibleModels(saved, speechModel, models) &&
            hasValidArtifacts(stagingDir, saved) &&
            isValidProgress(saved)
        return if (valid) StagedSnapshot.fromCheckpointState(saved) else null
    }

    @Suppress("ReturnCount") // Short-circuits on header or PCM slice mismatch cleanly.
    private fun verifyLiveSource(partFile: File, srcJson: JSONObject, signature: String): Boolean {
        val sampleRate = srcJson.getInt("sampleRate")
        val channels = srcJson.getInt("channels")
        val dataOffset = srcJson.getLong("dataOffset")
        val verifiedBytes = srcJson.getLong("verifiedBytes")
        val inBounds = dataOffset >= WAV_HEADER_MIN &&
            verifiedBytes > dataOffset &&
            partFile.length() >= verifiedBytes
        if (!inBounds || signature != "$verifiedBytes:$sampleRate:$channels:$dataOffset") return false
        val header = StreamingWavPrefixReader.inspectHeader(partFile) ?: return false
        val headerMatches = header.sampleRate == sampleRate &&
            header.channels == channels &&
            header.dataOffset == dataOffset
        if (!headerMatches) return false
        val (headSha, tailSha) = hashPcmSlices(partFile, dataOffset, verifiedBytes)
        return headSha == srcJson.getString("headPcmSha256") && tailSha == srcJson.getString("tailPcmSha256")
    }

    private fun isCompatibleModels(
        saved: CheckpointState,
        speechModel: SpeechModel,
        models: ModelAccess,
    ): Boolean {
        val speakersMatch = models.ready(ModelPart.SPEAKERS) == !saved.speakerFailure
        val voicesMatch = !models.ready(ModelPart.VOICES) || saved.voiceClips != null
        return saved.model == speechModel.identity &&
            saved.speakers == ModelCatalog.diarizationModel.sha256 &&
            speakersMatch &&
            voicesMatch
    }

    private fun hasValidArtifacts(stagingDir: File, saved: CheckpointState): Boolean {
        val encoder = saved.encoder ?: return false
        val stagedOpus = File(stagingDir, WORK_AUDIO_PART)
        val stagedClips = File(stagingDir, WORK_CLIPS)
        val opusOk = stagedOpus.isFile && stagedOpus.length() >= encoder.length
        val clipsOk = saved.clipSamples in 0..CheckpointState.MAX_CLIP_SAMPLES &&
            stagedClips.isFile &&
            stagedClips.length() >= saved.clipSamples.toLong() * BYTES_PER_FLOAT
        return opusOk && clipsOk
    }

    private fun isValidProgress(saved: CheckpointState): Boolean =
        saved.positionSamples in 0..Limits.MAX_SAMPLES && saved.doneMs in 1..Limits.MAX_TRANSCRIBE_MS

    @Suppress("LongParameterList") // Carries target note, staging files, and install dependencies cleanly.
    suspend fun installIntoNote(
        noteId: String,
        partFile: File,
        stagingDir: File,
        speechModel: SpeechModel,
        models: ModelAccess,
        storage: JobStorage,
        voices: JobVoices,
        env: JobEnvironment,
        canInstallBlocking: (String) -> Boolean,
    ) {
        withContext(NonCancellable + env.io) {
            try {
                val snap = loadStaged(partFile, stagingDir, speechModel, models)
                    ?: return@withContext
                val encoder = snap.encoder ?: return@withContext
                val audioFile = storage.audioFile(noteId)
                val stagedOpus = File(stagingDir, WORK_AUDIO_PART)
                val stagedClips = File(stagingDir, WORK_CLIPS)
                val minClipsBytes = snap.clipSamples.toLong() * BYTES_PER_FLOAT
                storage.withNoteLockBlocking(noteId) {
                    val valid = canInstallBlocking(noteId) &&
                        audioFile.isFile &&
                        stagedOpus.isFile && stagedOpus.length() >= encoder.length &&
                        stagedClips.isFile && stagedClips.length() >= minClipsBytes
                    if (valid) {
                        val work = storage.workFiles(noteId)
                        val generation = voices.generationBlocking()
                        storage.mutateNames(noteId) {
                            CheckpointNames(emptyMap(), emptyMap(), generation = generation)
                        }
                        moveOrCopyTruncated(stagedOpus, work.encodedAudio, encoder.length, env)
                        moveOrCopyTruncated(stagedClips, work.clips, minClipsBytes, env)
                        val stagedWhistle = File(stagingDir, WORK_WHISTLE_SEGMENTS)
                        if (stagedWhistle.isFile) {
                            storage.whistleSegmentCache(noteId)?.let { dest ->
                                moveOrCopyFile(stagedWhistle, dest, env)
                            }
                        }
                        val state = snap.toCheckpointState(
                            source = audioFile.name,
                            signature = CheckpointState.signature(audioFile),
                            speechModel = speechModel,
                        )
                        storage.saveCheckpoint(noteId, state)
                    }
                }
            } catch (error: IOException) {
                env.warn("Could not install live recorder precompute checkpoint", error)
            } finally {
                stagingDir.deleteRecursively()
            }
        }
    }

    private fun hashPcmSlices(partFile: File, dataOffset: Long, verifiedBytes: Long): Pair<String, String> {
        val pcmLen = verifiedBytes - dataOffset
        val headBytes = minOf(pcmLen, HEAD_PCM_HASH_BYTES).toInt()
        val tailBytes = minOf(pcmLen, TAIL_PCM_HASH_BYTES).toInt()
        return RandomAccessFile(partFile, "r").use { raf ->
            val headSha = sha256Slice(raf, dataOffset, headBytes)
            val tailSha = sha256Slice(raf, verifiedBytes - tailBytes, tailBytes)
            headSha to tailSha
        }
    }

    private fun sha256Slice(raf: RandomAccessFile, offset: Long, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(minOf(length, IO_BUF_BYTES))
        raf.seek(offset)
        var remaining = length
        while (remaining > 0) {
            val read = raf.read(buf, 0, minOf(remaining, buf.size))
            if (read <= 0) throw IOException("Unexpected EOF while hashing staged PCM")
            digest.update(buf, 0, read)
            remaining -= read
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun moveOrCopyFile(src: File, dst: File, env: JobEnvironment) {
        dst.delete()
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
        RandomAccessFile(dst, "rw").use { raf ->
            env.syncFile(dst) { raf.fd.sync() }
        }
    }

    private fun moveOrCopyTruncated(src: File, dst: File, length: Long, env: JobEnvironment) {
        dst.delete()
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
        RandomAccessFile(dst, "rw").use { raf ->
            if (raf.length() != length) raf.setLength(length)
            env.syncFile(dst) { raf.fd.sync() }
        }
    }

    private fun writeAtomicallyText(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}$TMP_SUFFIX")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            val replaced = tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))
            if (!replaced) throw IOException("Cannot replace ${target.name}")
        } catch (error: IOException) {
            tmp.delete()
            throw error
        }
    }
}
