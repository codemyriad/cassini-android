package io.codemyriad.cassini.engine.job

import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.OggOpus
import io.codemyriad.cassini.core.format.SpeakerMerges
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.speakers.SpeakerTurn
import io.codemyriad.cassini.engine.asr.Parakeet
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Writes the cross-app vector `android-written-v1.opus` (coordination `ux-draft/cross-app-vectors`, the parity
 * review's risk 6) with Android's own writers, as a note goes through them:
 *
 * 1. A transcription: two diarized speakers with the run-scoped ids of a job (`diar_<run>_1`, `_2`), labelled with the
 *    placeholders an Italian phone stores ("Voce 1", "Voce 2"), language `und`, and the job's provenance
 *    (`JobProvenance`, `x-segmentation` and `x-speakerDiarization` included).
 * 2. A speaker merge (`DocumentRepository.mergeSpeakers`): a second version, the default, with `x-speakerMerges`.
 * 3. A rename (`DocumentRepository.relabel`): speaker 1 becomes "Anna"; "Voce 2" stays stored.
 *
 * The audio is the CC0 conformance vector `002-plain-audio.opus` with its tags removed, as Android's own recordings
 * have none (so the document gets Android's TITLE and no DATE); the audio packets and their digest are unchanged.
 * The writer adds a fresh meeting id, a version id suffix and times, so the bytes differ at each run: the vector is
 * written once and committed, and its SHA-256 and facts are pinned. Runs only when `CASSINI_WRITE_VECTOR` names the
 * output file.
 */
class AndroidWrittenVectorTest {
    @Test fun writeTheVector() {
        val target = System.getenv("CASSINI_WRITE_VECTOR")
        assumeTrue("Set CASSINI_WRITE_VECTOR to write the vector", target != null)
        val conformance = File(
            System.getProperty("cassini.conformance", "../third_party/cassini-format/spec/conformance"),
        )
        // The audio as Android records it: an Ogg Opus stream with no tags of its own (no TITLE, DATE or ARTIST).
        val tagged = File(conformance, "opus/002-plain-audio.opus").readBytes()
        val audio = ByteArrayOutputStream().also { out ->
            OggOpus.rewrite(ByteArrayInputStream(tagged), CassiniDocument.tagsPacket(emptyList()), out)
        }.toByteArray()

        val run = "diar_3f2a9c1e_7b4d_4e21_9a6f_0c8d2b5e1f47_"
        val one = "${run}1"
        val two = "${run}2"
        val words = listOf(
            Word(one, 0, 400, "Buongiorno"),
            Word(one, 400, 800, "a"),
            Word(one, 800, 1300, "tutti."),
            Word(two, 1500, 1900, "Grazie,"),
            Word(two, 1900, 2400, "iniziamo."),
        )
        val transcript = Transcript(words, Transcript.UNDETERMINED)
        val turns = listOf(SpeakerTurn(0, 1300, 0), SpeakerTurn(1500, 2400, 1))
        val recognition = JobProvenance.recognition(
            Transcript.UNDETERMINED,
            inferenceMs = 1234,
            segmentation = Parakeet.incrementalProvenance("silero_vad.onnx"),
            resumedFromMs = 0,
        )
        val first = JobProvenance.diarization(words, turns, recognition, diarizationMs = 321)
        val labels = mapOf(one to "Voce 1", two to "Voce 2")
        val sealed = CassiniDocument.create(audio, transcript, "Riunione di prova", first, null, labels)

        // The merge of speaker 2 into speaker 1, as DocumentRepository.mergeSpeakers writes it.
        val before = CassiniDocument.read(sealed)
        val aliases = mapOf(two to one)
        val merged = SpeakerMerges.apply(transcript, aliases)
        val second = SpeakerMerges.processing(first, aliases, before.defaultId)
        val withMerge = CassiniDocument.create(sealed, merged, "Riunione di prova", second, before, emptyMap())

        // The rename of speaker 1, as DocumentRepository.relabel writes it.
        val work = Files.createTempDirectory("android-vector").toFile()
        try {
            val source = File(work, "source.opus").apply { writeBytes(withMerge) }
            val out = File(work, "out.opus")
            CassiniDocument.relabel(source, CassiniDocument.read(source), mapOf(one to "Anna"), out)
            out.copyTo(File(target!!), overwrite = true)
        } finally {
            work.deleteRecursively()
        }
    }
}
