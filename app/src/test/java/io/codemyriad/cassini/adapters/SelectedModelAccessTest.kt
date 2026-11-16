package io.codemyriad.cassini.adapters

import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.ModelPlan
import io.codemyriad.cassini.data.settings.SpeakerChoices
import io.codemyriad.cassini.engine.job.DownloadProgress
import io.codemyriad.cassini.engine.job.InstallOutcome
import io.codemyriad.cassini.engine.job.ModelAccess
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SelectedModelAccessTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun disabledPartsNeitherRunNorDownloadAndSpeechAlwaysWorks() {
        val plan = ModelPlan.of(folder.newFolder())
        val installed = object : ModelAccess {
            override suspend fun consented() = true
            override fun ready(part: ModelPart) = true
            override fun plan() = plan
            override fun detectorReady() = true
            override fun hasValidatedNetwork() = true
            override suspend fun install(
                parts: Set<ModelPart>,
                onProgress: (DownloadProgress) -> Unit,
            ): InstallOutcome =
                error("No download is needed for this test")
        }
        for (tellApart in listOf(false, true)) {
            for (recognise in listOf(false, true)) {
                val access = SelectedModelAccess(installed, SpeakerChoices(tellApart, recognise))
                assertEquals(true, access.ready(ModelPart.SPEECH))
                assertEquals(tellApart, access.ready(ModelPart.SPEAKERS))
                assertEquals(recognise, access.ready(ModelPart.VOICES))
                val expected = buildSet {
                    add(ModelPart.SPEECH)
                    if (tellApart) add(ModelPart.SPEAKERS)
                    if (recognise) add(ModelPart.VOICES)
                }
                assertEquals(expected, access.plan().missing.map { it.part }.toSet())
            }
        }
    }
}
