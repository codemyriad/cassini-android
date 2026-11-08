package io.codemyriad.cassini.models

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.models.ModelFetcher
import io.codemyriad.cassini.data.models.ModelRepository
import io.codemyriad.cassini.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class ParakeetRecommendationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun failedPlatformInspectionHidesTheOfferAndResumeStillRechecks() = runTest {
        val probes = AtomicInteger()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getSystemService(name: String): Any? {
                probes.incrementAndGet()
                throw SecurityException("Lab capability inspection refused")
            }
        }
        val paths = AppPaths(folder.newFolder("root"), folder.newFolder("cache"))
        val settingsJob = Job()
        val settings = SettingsRepository.create(paths, CoroutineScope(Dispatchers.IO + settingsJob))
        val models = ModelRepository(
            paths,
            settings,
            CassiniDispatchers(),
            ModelFetcher { _, _, _ -> error("No download") },
        )
        val reports = Channel<ParakeetRecommendationState>()
        try {
            models.refresh()
            settings.recordWhistleCompleted("first")
            val recommendation = ParakeetRecommendation(context, paths, models, settings, Dispatchers.IO)
            val collector = launch { recommendation.state.collect { reports.send(it) } }
            try {
                assertFalse(reports.receive().visible)
                recommendation.refresh()
                assertFalse(reports.receive().visible)
                assertEquals(2, probes.get())
            } finally {
                collector.cancelAndJoin()
            }
        } finally {
            settingsJob.cancelAndJoin()
            reports.close()
        }
    }
}
