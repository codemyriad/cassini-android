package org.cassini.android

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch

/**
 * The work of one [ProcessingJob], run on the service's thread: decode once into the PCM cache on a
 * producer thread while speech recognition reads the cache behind it, journal each settled piece,
 * keep the settled words in the note as a partial transcript, then package the document and, when
 * asked, identify speakers over the same cache. A rerun after a kill continues from the cache's
 * checkpoint and the journal instead of starting over.
 */
internal class ProcessingPipeline(private val context: Context, private val publish: (ProcessingJob, Boolean) -> Unit) {
    private val filesDir = context.filesDir
    private val library = LibraryStore(filesDir)
    private val documents = DocumentStore(context)
    @Volatile private var producer: Thread? = null

    /** Stops the decoder too; the caller interrupts the thread running [run]. */
    fun cancel() { producer?.interrupt() }

    fun run(start: ProcessingJob): ProcessingJob {
        var job = start
        val began = System.nanoTime()
        fun elapsed() = start.elapsedMs + (System.nanoTime() - began) / 1_000_000
        val note = library.load().firstOrNull { it.id == job.noteId } ?: throw UserFacingException(Failure.OPEN)
        val session = note.session
        val uri = Uri.parse(requireNotNull(session.uri) { "note without audio" })
        val existing = session.document?.let { path ->
            try { CassiniDocument.read(File(path)) } catch (error: Exception) { Log.w(TAG, "Could not read document", error); null }
        }
        // Shared with the screens, which append what they have not shown instead of redrawing everything.
        val partialWords = ProcessingJobs.beginWords(emptyList())
        job = job.copy(phase = ProcessingJob.Phase.DECODE, name = session.name)
        publish(job, true)

        // Decode on its own thread: recognition starts on the first cached seconds instead of waiting for the end.
        val opened = CountDownLatch(1)
        var cache: PcmCache? = null
        var decodeError: Throwable? = null
        val decodeBegan = System.nanoTime()
        val decoding = Thread({
            try {
                AudioDecoder.decodeToCache(context, uri, File(context.cacheDir, "pcm")) { cache = it; opened.countDown() }
                Log.i(TAG, "stage=decode wall=${(System.nanoTime() - decodeBegan) / 1_000_000}")
            } catch (error: Throwable) {
                decodeError = error
                if (error !is UserFacingException || error.failure != Failure.CANCELLED) Log.e(TAG, "Decode failed", error)
            } finally { opened.countDown() }
        }, "cassini-decode")
        decoding.priority = Thread.NORM_PRIORITY - 1
        producer = decoding
        decoding.start()
        try {
            try { opened.await() } catch (_: InterruptedException) { throw UserFacingException(Failure.CANCELLED) }
            val pcm = cache ?: throw (decodeError ?: UserFacingException(Failure.OPEN))
            val source = CacheSource(pcm) { decodeError?.let { if (it is OutOfMemoryError) UserFacingException(Failure.MEMORY) else it } }
            val models = ModelStore(File(filesDir, "parakeet-v3"))
            val words: Transcript
            var inferenceMs = session.inferenceMs
            val sourceVariant = existing?.selected(session.selectedVariant)
            var transcribed = false
            if (job.speakers && (session.transcript?.words?.isNotEmpty() == true || sourceVariant?.transcript != null) && !session.partial) {
                words = sourceVariant?.transcript ?: requireNotNull(session.transcript)
            } else {
                transcribed = true
                job = job.copy(phase = ProcessingJob.Phase.ASR)
                publish(job, true)
                val detector = ModelStore.vadPath(filesDir).takeIf { ModelStore.vadReady(filesDir) }
                val policy = DecodePolicy.WHOLE_SPANS
                val cutting = Parakeet.cutting(detector)
                val journal = TranscriptJournal(journalFile(filesDir, job.noteId))
                val header = TranscriptJournal.Header(pcm.key, "parakeet-tdt-0.6b-v3", models.precision, models.revision,
                    detector?.let { File(it).name } ?: "none", cutting.name, policy.toString())
                val resume = journal.load(header)
                resume?.let { state -> partialWords.addAll(state.words.map { it.word }) }
                if (resume != null) Log.i(TAG, "Resuming transcription at ${state(resume)}")
                journal.open(header)
                // Only audio decoded by this job counts towards its speed, so a resume does not inflate it.
                val resumedMs = (resume?.settledEnd ?: 0) * 1000 / Limits.ASR_RATE
                val baseAudioMs = if (start.elapsedMs > 0) start.audioMs.coerceAtMost(resumedMs) else 0
                val asrBegan = System.nanoTime()
                var savedAt = System.nanoTime()
                var savedWords = partialWords.size
                try {
                    words = Parakeet.transcribe(source, models, detector, policy, cutting, resume, onSettled = { settled, end ->
                        if (settled.isNotEmpty()) {
                            journal.append(settled, end)
                            partialWords.addAll(settled.map { it.word })
                        }
                        // The note keeps what is settled, so a stop at any point leaves words to read.
                        val now = System.nanoTime()
                        if (partialWords.size - savedWords >= 200 || (now - savedAt >= 15_000_000_000L && partialWords.size > savedWords)) {
                            savePartial(job.noteId, partialWords)
                            savedAt = now; savedWords = partialWords.size
                        }
                    }) { progress ->
                        // Decode time counts: the multiple is audio settled per second since the job began.
                        job = job.copy(doneMs = progress.doneMs, totalMs = progress.totalMs, elapsedMs = elapsed(),
                            audioMs = baseAudioMs + (progress.doneMs - resumedMs).coerceAtLeast(0), stageMs = elapsed(), pending = progress.pending, settledWords = partialWords.size)
                        publish(job, false)
                    }
                } catch (error: Throwable) {
                    journal.close()
                    if (partialWords.size > savedWords) savePartial(job.noteId, partialWords)
                    throw error
                }
                journal.close()
                inferenceMs = (System.nanoTime() - asrBegan) / 1_000_000
                val durationMs = pcm.available * 1000 / Limits.ASR_RATE
                Log.i(TAG, "stage=asr audio=$durationMs wall=$inferenceMs x=${ProcessingSpeed.realtime(durationMs, inferenceMs)}")
                requireUser(durationMs >= 200, Failure.SHORT)
                writeArtifacts(words, models, detector, durationMs, inferenceMs)
            }
            val durationMs = pcm.available * 1000 / Limits.ASR_RATE
            job = job.copy(phase = ProcessingJob.Phase.PACKAGE, doneMs = durationMs, totalMs = durationMs, elapsedMs = elapsed(),
                audioMs = durationMs, pending = emptyList())
            publish(job, true)
            var document = existing
            var file: File? = null
            var portable: CassiniDocument? = null
            var transcript = words
            var labels = emptyMap<String, String>()
            if (transcribed) {
                val created = documents.create(uri, { PcmAudio(pcm.readAll(), Limits.ASR_RATE) }, words, session.name,
                    processing(models.precision, models.revision).put("x-inferenceMs", inferenceMs)
                        .put("x-segmentation", Parakeet.cutting(ModelStore.vadPath(filesDir).takeIf { ModelStore.vadReady(filesDir) }).provenance), existing)
                file = created.first; portable = created.second; document = created.second
                saveDone(job.noteId, created.first, created.second, words, durationMs, inferenceMs, elapsed(), models.precision)
            }
            if (job.speakers) {
                val speakerModels = DiarizationModels.inFiles(filesDir)
                requireUser(speakerModels.ready(), Failure.SPEAKERS)
                job = job.copy(phase = ProcessingJob.Phase.DIARIZE, doneMs = 0, elapsedMs = elapsed(), audioMs = 0, stageMs = 0)
                publish(job, true)
                val diarizeBegan = System.nanoTime()
                // Confined to the diarization call: packaging reads the cache again and must not hold two copies.
                val turns = Diarization.turns(PcmAudio(pcm.readAll(), Limits.ASR_RATE), speakerModels.modelPath) { done, total ->
                    if (total > 0) {
                        val doneMs = durationMs * done / total
                        val wall = (System.nanoTime() - diarizeBegan) / 1_000_000
                        job = job.copy(doneMs = doneMs, totalMs = durationMs, elapsedMs = elapsed(), audioMs = doneMs, stageMs = wall,
                            pending = emptyList())
                        publish(job, false)
                    }
                }
                val diarizeMs = (System.nanoTime() - diarizeBegan) / 1_000_000
                Log.i(TAG, "stage=diarize audio=$durationMs wall=$diarizeMs x=${ProcessingSpeed.realtime(durationMs, diarizeMs)}")
                requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
                val variant = document?.selected(if (transcribed) document.defaultId else session.selectedVariant)
                val sourceId = variant?.id
                val previousProcessing = document?.manifest?.optJSONObject("provenance")?.optJSONObject("speechToText")?.optJSONObject(sourceId ?: "")
                val result = SpeakerAttribution.derive(words, turns, previousProcessing, sourceId, diarizeMs,
                    document?.manifest?.optJSONObject("provenance")?.optJSONObject("attribution"))
                labels = result.transcript.words.map { it.speaker }.distinct().mapIndexed { index, id ->
                    id to context.getString(R.string.speaker_label, index + 1)
                }.toMap()
                transcript = result.transcript
                job = job.copy(phase = ProcessingJob.Phase.PACKAGE, elapsedMs = elapsed(), audioMs = durationMs, stageMs = diarizeMs)
                publish(job, true)
                val created = documents.create(uri, { PcmAudio(pcm.readAll(), Limits.ASR_RATE) }, transcript, session.name,
                    result.processing, document, labels)
                file = created.first; portable = created.second
                saveDone(job.noteId, created.first, created.second, transcript, durationMs, inferenceMs, elapsed(), models.precision)
            }
            checkNotNull(file); checkNotNull(portable)
            // The words are in the document now; the checkpoint and the cache have done their job.
            journalFile(filesDir, job.noteId).delete()
            pcm.delete()
            job = job.copy(phase = ProcessingJob.Phase.DONE, doneMs = job.totalMs, elapsedMs = elapsed(), failure = null, pending = emptyList())
            return job
        } finally {
            decoding.interrupt()
            try { decoding.join(5_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            producer = null
        }
    }

    private fun state(resume: TranscriptJournal.State) = "${resume.words.size} words, sample ${resume.settledEnd}"

    private fun savePartial(noteId: String, words: SettledWords) {
        try {
            library.update(noteId) { it.copy(transcript = Transcript(words.since(0)), partial = true) }
        } catch (error: Exception) { Log.e(TAG, "Could not keep the partial transcript", error) }
    }

    private fun saveDone(noteId: String, file: File, portable: CassiniDocument, transcript: Transcript, durationMs: Long,
                         inferenceMs: Long, processingMs: Long, precision: String) {
        library.update(noteId) { session ->
            session.copy(uri = Uri.fromFile(file).toString(), document = file.absolutePath,
                name = "${session.name.substringBeforeLast('.')}.opus", selectedVariant = portable.defaultId,
                durationMs = durationMs, inferenceMs = inferenceMs, resultPrecision = precision, processingMs = processingMs,
                transcript = transcript, partial = false)
        }
    }

    private fun writeArtifacts(transcript: Transcript, models: ModelStore, detector: String?, durationMs: Long, inferenceMs: Long) {
        // Processing records are machine-readable provenance, independent of UI language.
        try {
            File(filesDir, "latest.words.json").writeText(transcript.json())
            File(filesDir, "latest.processing.json").writeText(JSONObject()
                .put("model", "nvidia/parakeet-tdt-0.6b-v3").put("modelRevision", models.revision)
                .put("runtime", "sherpa-onnx 1.13.7, stock frontend, CPU ${models.precision}, greedy, 2 threads")
                .put("language", "it (user-selected; automatic multilingual recognition)")
                .put("durationMs", durationMs).put("inferenceMs", inferenceMs)
                .put("speakerAttribution", "single recording; no diarization")
                .put("segmentation", Parakeet.cutting(detector).provenance)
                .put("wordTimings", "TDT token-derived; words over silence dropped; ends follow continuing audio up to the punctuation-inclusive end")
                .toString(2))
        } catch (error: IOException) { Log.e(TAG, "Could not cache processing artifacts", error) }
    }

    companion object {
        private const val TAG = "Cassini"

        fun journalFile(filesDir: File, noteId: String) = File(ProcessingJobs.directory(filesDir), "$noteId.journal.jsonl")

        fun processing(precision: String, revision: String? = null): JSONObject = JSONObject()
            .put("backend", "sherpa-onnx").put("engine", "Parakeet TDT")
            .put("model", "nvidia/parakeet-tdt-0.6b-v3 $precision")
            .put("device", "Android CPU").put("language", "it")
            .put("source", "recording").put("version", "sherpa-onnx 1.13.7${revision?.let { "; model revision $it" }.orEmpty()}")
    }
}
