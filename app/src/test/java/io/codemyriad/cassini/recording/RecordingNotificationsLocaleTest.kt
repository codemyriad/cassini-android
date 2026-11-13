package io.codemyriad.cassini.recording

import android.app.LocaleManager
import android.app.Notification
import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.core.audio.RecordingSpace
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Ongoing recording notification action labels follow the shared catalog in every app locale below API 33
 * (`AppLocalesStorageHelper`) and from API 33 (`LocaleManager`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class RecordingNotificationsLocaleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun ongoingActionsUseTheCatalogInEveryAppLocaleAtSdk32() {
        val notifications = RecordingNotifications(context)
        for (case in LOCALE_CASES) {
            storeAppLocales(case.tag)
            assertOngoingLocale(notifications, case)
        }
        storeAppLocales("")
        assertOngoingLocale(notifications, ENGLISH_CASE)
    }

    private fun storeAppLocales(tags: String) {
        File(context.filesDir, "androidx.appcompat.app.AppCompatDelegate.application_locales_record_file")
            .writeText("<?xml version='1.0' encoding='utf-8'?><locales application_locales=\"$tags\" />")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35])
class RecordingNotificationsLocaleManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun ongoingActionsUseTheCatalogInEveryLocaleManagerLanguage() {
        val notifications = RecordingNotifications(context)
        val manager = context.getSystemService(LocaleManager::class.java)
        for (case in LOCALE_CASES) {
            manager.applicationLocales = LocaleList.forLanguageTags(case.tag)
            assertEquals("en", context.resources.configuration.locales[0].language)
            assertOngoingLocale(notifications, case)
        }
        manager.applicationLocales = LocaleList.getEmptyLocaleList()
        assertOngoingLocale(notifications, ENGLISH_CASE)
    }
}

private data class RecordingLocaleCase(
    val tag: String,
    val pause: String,
    val resume: String,
    val stop: String,
)

private val ENGLISH_CASE = RecordingLocaleCase(tag = "en", pause = "Pause", resume = "Resume", stop = "Stop")

private val LOCALE_CASES = listOf(
    ENGLISH_CASE,
    RecordingLocaleCase(tag = "it", pause = "Pausa", resume = "Riprendi", stop = "Ferma"),
    RecordingLocaleCase(tag = "fr", pause = "Pause", resume = "Reprendre", stop = "Arrêter"),
    RecordingLocaleCase(tag = "es", pause = "Pausar", resume = "Reanudar", stop = "Detener"),
    RecordingLocaleCase(tag = "pt-BR", pause = "Pausar", resume = "Retomar", stop = "Parar"),
    RecordingLocaleCase(tag = "pt-PT", pause = "Pausa", resume = "Retomar", stop = "Parar"),
)

private fun assertOngoingLocale(notifications: RecordingNotifications, case: RecordingLocaleCase) {
    val active = notifications.ongoing(sampleActive(pause = null), elapsedMs = ELAPSED_MS)
    assertEquals(case.tag, listOf(case.pause, case.stop), active.actionTitles())
    assertEquals(
        case.tag,
        listOf(RecordingService.ACTION_PAUSE, RecordingService.ACTION_FINISH),
        active.actionIntents(),
    )
    assertEquals(Notification.VISIBILITY_PRIVATE, active.visibility)
    assertEquals(null, active.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))

    val paused = notifications.ongoing(sampleActive(pause = RecordingPause.USER), elapsedMs = ELAPSED_MS)
    assertEquals(case.tag, listOf(case.resume, case.stop), paused.actionTitles())
    assertEquals(
        case.tag,
        listOf(RecordingService.ACTION_RESUME, RecordingService.ACTION_FINISH),
        paused.actionIntents(),
    )
}

private fun sampleActive(pause: RecordingPause?) = RecordingState.Active(
    noteId = "5eed0000-0000-4000-8000-000000000001",
    title = "Weekly product sync",
    defaultTitle = "Voice note 8 Oct, 08:14",
    format = CaptureFormat.OPUS,
    sampleRateHz = 48_000,
    elapsedMs = ELAPSED_MS,
    pause = pause,
    space = RecordingSpace(spaceMs = 3_600_000L),
    warning = null,
)

private fun Notification.actionTitles(): List<String> = actions.orEmpty().map { it.title.toString() }

private fun Notification.actionIntents(): List<String?> =
    actions.orEmpty().map { shadowOf(it.actionIntent).savedIntent.action }

private const val ELAPSED_MS = 1_447_000L
