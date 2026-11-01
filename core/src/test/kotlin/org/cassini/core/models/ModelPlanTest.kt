package org.cassini.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToLong

class ModelPlanTest {
    @get:Rule val folder = TemporaryFolder()

    private val mib = 1024.0 * 1024.0

    private fun install(model: ModelDescriptor) {
        val directory = model.directory(folder.root).apply { mkdirs() }
        for (artifact in model.artifacts) {
            RandomAccessFile(File(directory, artifact.name), "rw").use { it.setLength(artifact.size) }
        }
        File(directory, model.markerFile).writeText(model.markerValue)
    }

    /** Each file's download and its decompressed copy: both are on disk until the download is deleted. */
    private fun Artifact.installed() = download!!.size + size

    @Test fun aFreshInstallNeedsAbout1331MiBAndShows528() {
        val plan = ModelPlan.of { false }
        assertEquals(listOf(ModelPart.SPEECH, ModelPart.SPEAKERS, ModelPart.VOICES), plan.parts)
        assertEquals(listOf(440L, 62L, 26L), plan.missing.map { it.mebibytes })
        assertEquals(528L, plan.totalMebibytes)
        // The rounded parts add up on screen; the rounded sum of the bytes would say 530.
        assertEquals(530L, ModelCatalog.mebibytes(plan.totalBytes))
        val speech = ModelCatalog.parakeetArtifacts.sumOf { it.installed() } + ModelCatalog.sileroVad.installed()
        val speakers = ModelCatalog.diarizationModel.installed()
        val voices = ModelCatalog.voiceprintModel.installed()
        assertEquals(speech + speakers + voices + ArtifactFetcher.SLACK_BYTES, plan.requiredFreeBytes)
        assertEquals(1331L, (plan.requiredFreeBytes / mib).roundToLong())
        assertEquals(1330L, plan.requiredFreeMebibytes)
    }

    @Test fun theDownloadBytesOfEachPartAreWhatTheNetworkCarries() {
        val plan = ModelPlan.of { false }
        assertEquals(
            mapOf(
                ModelPart.SPEECH to ModelCatalog.parakeetBytes + ModelCatalog.sileroVad.download!!.size,
                ModelPart.SPEAKERS to ModelCatalog.diarizationModel.download!!.size,
                ModelPart.VOICES to ModelCatalog.voiceprintModel.download!!.size,
            ),
            plan.downloadBytes,
        )
        assertEquals(plan.downloadBytes.values.sum(), plan.totalBytes)
    }

    @Test fun aReadySpeechPartLeavesOnlyTheSpeakerParts() {
        val plan = ModelPlan.of { it == ModelCatalog.PARAKEET }
        assertEquals(listOf(ModelPart.SPEAKERS, ModelPart.VOICES), plan.parts)
        assertEquals(88L, plan.totalMebibytes)
        val expected = ModelCatalog.diarizationModel.installed() + ModelCatalog.voiceprintModel.installed() +
            ArtifactFetcher.SLACK_BYTES
        assertEquals(expected, plan.requiredFreeBytes)
    }

    @Test fun aMissingDetectorAloneDoesNotMakeSpeechMissing() {
        val plan = ModelPlan.of { it != ModelCatalog.VAD }
        assertTrue(plan.isEmpty)
        assertEquals(0L, plan.requiredFreeBytes)
        assertEquals(0L, plan.totalMebibytes)
    }

    @Test fun aMissingPartBringsOnlyItsModelsThatAreNotReady() {
        val plan = ModelPlan.of { it == ModelCatalog.VAD }
        assertEquals(listOf(ModelCatalog.PARAKEET), plan.missing.first { it.part == ModelPart.SPEECH }.models)
        assertEquals(440L, plan.missing.first().mebibytes)
    }

    @Test fun onlyTheVoiceModelMissing() {
        val plan = ModelPlan.of { it != ModelCatalog.VOICEPRINT }
        assertEquals(listOf(ModelPart.VOICES), plan.parts)
        assertEquals(26L, plan.totalMebibytes)
        assertEquals(ModelCatalog.voiceprintModel.installed() + ArtifactFetcher.SLACK_BYTES, plan.requiredFreeBytes)
    }

    @Test fun thePlanForADirectoryReadsTheInstalledModels() {
        assertEquals(ModelPart.entries, ModelPlan.of(folder.root).parts)
        install(ModelCatalog.PARAKEET)
        install(ModelCatalog.DIARIZATION)
        assertEquals(listOf(ModelPart.VOICES), ModelPlan.of(folder.root).parts)
        install(ModelCatalog.VOICEPRINT)
        assertTrue(ModelPlan.of(folder.root).isEmpty)
    }

    @Test fun thePlanCoversEverySpaceCheckOfTheInstallThatFollows() {
        // A plan that passes its space check must not fail later with Not enough storage: for every set of ready
        // models, the plan needs at least what ArtifactFetcher checks before each file, and at least one model's
        // whole download plus the slack (the shape of the 0.1.0 Parakeet pre-check, without its 128 MiB margin).
        val all = ModelPart.entries.flatMap { it.models }
        for (mask in 0 until (1 shl all.size)) {
            val ready = all.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            val plan = ModelPlan.of { it in ready }
            for (model in plan.missing.flatMap { it.models }) {
                // ArtifactFetcher.fetchZstd checks each file's download and its decompressed copy.
                val perFile = model.artifacts.map { it.installed() }
                for (need in perFile + model.downloadBytes) {
                    assertTrue("$ready: $need", plan.requiredFreeBytes >= need + ArtifactFetcher.SLACK_BYTES)
                }
            }
        }
    }

    @Test fun eachPartIsLedByTheModelThatMakesItReady() {
        assertEquals(ModelCatalog.PARAKEET, ModelPart.SPEECH.primary)
        assertEquals(listOf(ModelCatalog.PARAKEET, ModelCatalog.VAD), ModelPart.SPEECH.models)
        assertEquals(listOf(ModelCatalog.DIARIZATION), ModelPart.SPEAKERS.models)
        assertEquals(listOf(ModelCatalog.VOICEPRINT), ModelPart.VOICES.models)
    }
}
