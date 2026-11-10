package io.codemyriad.cassini.settings

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.CassiniApplication
import io.codemyriad.cassini.GraphFactory
import io.codemyriad.cassini.MainActivity
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.models.ModelFetcher
import io.codemyriad.cassini.data.models.ModelStatus
import io.codemyriad.cassini.processing.ServiceStarter
import io.codemyriad.cassini.testing.NoUncaughtCoroutines
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList

/** Settings taps through navigation, consent and the real install owner, on a fresh model root for every test. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class SettingsModelDownloadTest {
    private val folder = TemporaryFolder()
    private val app: CassiniApplication = ApplicationProvider.getApplicationContext()
    private val fetched = CopyOnWriteArrayList<String>()
    private var completeDownload = false
    private val graph: AppGraph get() = app.graph
    private val graphRule = object : ExternalResource() {
        override fun before() {
            val paths = AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder())
            CassiniApplication.graphFactory = GraphFactory { context, _ ->
                AppGraph(
                    context,
                    AppGraph.Config(
                        paths = paths,
                        fetcher = ModelFetcher { model, directory, progress ->
                            fetched += model.id
                            if (!completeDownload) throw IOException("Synthetic download failure")
                            model.artifacts.forEach { artifact ->
                                RandomAccessFile(File(directory, artifact.name), "rw").use { it.setLength(artifact.size) }
                            }
                            progress(model.downloadBytes, true)
                        },
                        network = { true },
                        processing = AppGraph.ProcessingHooks(starter = { ServiceStarter { true } }),
                    ),
                )
            }
            shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            runBlocking { withTimeout(TIMEOUT_MS) { graph.ready.await() } }
        }

        override fun after() {
            graph.close { shadowOf(Looper.getMainLooper()).idle() }
            CassiniApplication.graphFactory = null
        }
    }
    private val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain =
        RuleChain.outerRule(NoUncaughtCoroutines()).around(folder).around(graphRule).around(rule)

    @Test fun speakerRowDownloadsAndRetriesOnlyTheDiarizer() =
        downloadAndRetry(ModelPart.SPEAKERS, "Telling speakers apart")

    @Test fun voicesRowDownloadsAndRetriesOnlyTheVoiceprintModel() =
        downloadAndRetry(ModelPart.VOICES, "Recognising saved voices")

    @Test fun parakeetRowDownloadsAndRetriesOnlyParakeet() = downloadAndRetry(ModelPart.SPEECH, "Parakeet Ultra")

    @Test fun completedVoiceDownloadUpdatesOnlyItsOwnRow() {
        completeDownload = true
        openSettings()
        tapRow("Recognising saved voices")
        waitFor("Download")
        rule.onNodeWithText("Download").performScrollTo().performClick()
        rule.waitUntil(TIMEOUT_MS) {
            graph.models.status.value[ModelPart.VOICES] == ModelStatus.Ready && !graph.processing.state.value.downloading
        }
        rule.onNode(hasText("Recognising saved voices") and hasText("Ready")).performScrollTo().assertExists()
        rule.runOnIdle {
            assertEquals(listOf(ModelPart.VOICES.primary.id), fetched.toList())
            assertEquals(ModelStatus.Missing(false), graph.models.status.value[ModelPart.SPEECH])
        }
    }

    @Test fun closingOptionalConsentDoesNotStartAnyModel() {
        openSettings()
        tapRow("Telling speakers apart")
        waitFor("Download")
        rule.onNodeWithContentDescription("Close").performClick()
        waitFor("ON-DEVICE MODELS")
        rule.runOnIdle { assertEquals(emptyList<String>(), fetched.toList()) }
    }

    private fun downloadAndRetry(part: ModelPart, title: String) {
        openSettings()
        repeat(2) { attempt ->
            tapRow(title)
            waitFor("Download")
            for ((candidate, label) in PART_LABELS) {
                rule.onAllNodes(hasText(label)).assertCountEquals(if (candidate == part) 1 else 0)
            }
            rule.onNodeWithText("Download").performScrollTo().performClick()
            rule.waitUntil(TIMEOUT_MS) {
                fetched.size == attempt + 1 && graph.models.status.value[part] is ModelStatus.Failed &&
                    !graph.processing.state.value.downloading
            }
            rule.runOnIdle {
                assertEquals(List(attempt + 1) { part.primary.id }, fetched.toList())
            }
        }
    }

    private fun openSettings() {
        rule.onNodeWithContentDescription("Settings").performClick()
        waitFor("ON-DEVICE MODELS")
    }

    private fun tapRow(title: String) {
        waitFor(title)
        rule.onNodeWithText(title).performScrollTo().performClick()
    }

    private fun waitFor(text: String) {
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        val PART_LABELS = mapOf(
            ModelPart.SPEECH to "Speech recognition",
            ModelPart.SPEAKERS to "Telling speakers apart",
            ModelPart.VOICES to "Recognising saved voices",
        )
    }
}
