package org.cassini.android.processing

import android.Manifest
import android.app.Application
import android.app.LocaleManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import org.cassini.android.recording.RecordingNotifications
import org.cassini.android.recording.RecordingState
import org.cassini.core.models.ModelPart
import org.cassini.engine.job.BatteryLine
import org.cassini.engine.job.DownloadProgress
import org.cassini.engine.job.DraftState
import org.cassini.engine.job.JobPhase
import org.cassini.engine.job.ProgressHeadline
import org.cassini.engine.job.RunningProgress
import org.cassini.engine.job.Step
import org.cassini.engine.job.TimeLeft
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

private const val DAILY_LIMIT = "Android limits background work to about 6 hours a day. Open Cassini to resume."

/** The copy of the processing notifications (features.md sections 3.1 and 12). */
@RunWith(RobolectricTestRunner::class)
class ProcessingNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications = ProcessingNotifications(context)

    @Test fun timeLeftUsesTheFormsOfTheProgressCard() {
        val text = { low: Long, high: Long -> ProcessingNotifications.timeLeft(context, low, high) }
        assertEquals("6–9 min", text(6 * MINUTE, 9 * MINUTE))
        assertEquals("under 1 min", text(20_000, 50_000))
        assertEquals("about 6 min", text(6 * MINUTE, 6 * MINUTE))
        assertEquals("1 h 10 min–1 h 30 min", text(70 * MINUTE, 90 * MINUTE))
        assertEquals("50 min–1 h", text(50 * MINUTE, 60 * MINUTE))
    }

    @Test fun theOngoingTextFollowsThePhase() {
        val transcribing = { progress: RunningProgress ->
            notifications.content(running(JobPhase.Transcribing(progress, DraftState.EMPTY))).text
        }
        assertEquals("62% · 6–9 min left", transcribing(progress(TimeLeft.Range(6 * MINUTE, 9 * MINUTE))))
        assertEquals("Calculating time left…", transcribing(progress(TimeLeft.Calculating)))
        assertEquals("Matching voices…", transcribing(progress(TimeLeft.Finishing, ProgressHeadline.MATCHING_VOICES)))
        assertEquals("Saving…", transcribing(progress(TimeLeft.Finishing, ProgressHeadline.SAVING)))
        assertEquals("Starting…", notifications.content(running(JobPhase.Starting)).text)
        assertEquals("Finishing the previous note…", notifications.content(running(JobPhase.WaitingForPrevious)).text)

        val queued = running(JobPhase.Starting).copy(queue = listOf(QueuedNote("b", "B")))
        assertEquals("Transcribing “Riunione”", notifications.content(queued).title)
        assertEquals("1 more in queue", notifications.content(queued).subText)

        val mib = 1_048_576L
        val download = notifications.content(
            running(JobPhase.Downloading(DownloadProgress(ModelPart.SPEECH, 296 * mib, 640 * mib, verifying = false))),
        )
        assertEquals("Downloading speech recognition — 46%", download.title)
        assertEquals("296 of 640 MB", download.text)
        assertEquals(true, download.cancel)
        val checking = notifications.content(
            running(JobPhase.Downloading(DownloadProgress(ModelPart.SPEECH, 4, 5, verifying = true))),
        )
        assertEquals("Checking files — 80%", checking.title)
        val unknownPart = notifications.content(running(JobPhase.Downloading(null)))
        assertEquals("before the first progress the part is not named", "Downloading models…", unknownPart.title)
        assertEquals(true, unknownPart.cancel)
    }

    @Test fun pausedByAndroidWithoutWordsDoesNotShowZeroTimes() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        val text = { manager.allNotifications.single().extras.getCharSequence(Notification.EXTRA_TEXT).toString() }
        notifications.pausedByAndroid(NOTE, "Riunione", 0, 0)
        assertEquals("Paused before any words were found. $DAILY_LIMIT", text())
        notifications.pausedByAndroid(NOTE, "Riunione", 760_000, 2_052_000)
        assertEquals("Paused at 12:40 of 34:12. $DAILY_LIMIT", text())
    }

    /** The last checkpoint of a job that Android stopped replaces the numbers only while "Paused at" is shown. */
    @Test fun theSavedCheckpointUpdatesOnlyAShownPausedNotification() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val text = { shadowOf(manager).allNotifications.single().extras.getCharSequence(Notification.EXTRA_TEXT) }
        notifications.pausedByAndroid(NOTE, "Riunione", 288_000, 950_000)
        notifications.pausedByAndroidSaved(NOTE, "Riunione", 314_000, 950_000)
        assertEquals("Paused at 05:14 of 15:50. $DAILY_LIMIT", text().toString())

        // Another result of the note, or no notification at all: nothing is posted.
        notifications.readyToTranscribe(NOTE, "Riunione")
        notifications.pausedByAndroidSaved(NOTE, "Riunione", 340_000, 950_000)
        assertEquals("Riunione is waiting. Open Cassini to start.", text().toString())
        manager.cancelAll()
        notifications.pausedByAndroidSaved(NOTE, "Riunione", 340_000, 950_000)
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test fun resultsAreReplacedPerNoteAndHideTheTitleInPublic() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        notifications.readyToTranscribe(NOTE, "Riunione")
        notifications.failed(NOTE, "Riunione", "CHECKPOINT_MISMATCH")
        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        assertEquals(1, posted.size)
        val failed = posted.single()
        assertEquals("Couldn't transcribe Riunione", failed.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(
            "Saved progress no longer matches. Your recording is kept.",
            failed.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertEquals(Notification.VISIBILITY_PRIVATE, failed.visibility)
        assertEquals(
            "A note couldn't be transcribed",
            failed.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
    }

    private fun running(phase: JobPhase) =
        ProcessingState(loading = false, running = RunningJob(NOTE, "Riunione", phase))

    private fun progress(left: TimeLeft, headline: ProgressHeadline = ProgressHeadline.FINDING_WORDS_AND_SPEAKERS) =
        RunningProgress(
            headline = headline,
            rangeStartMs = 0,
            rangeEndMs = 0,
            percent = 62,
            step = Step.WORDS_AND_VOICES,
            matchingSkipped = false,
            timeLeft = left,
            doneMs = 1_000,
            totalMs = 4_000,
            battery = BatteryLine.Unavailable,
            words = 3,
            lowMemory = false,
        )

    private companion object {
        const val MINUTE = 60_000L
        const val NOTE = "5eed0000-0000-4000-8000-000000000001"
    }
}

/**
 * Notification text follows the app language below API 33, where `getContextForLanguage` reads the locales that
 * AppCompat stores in `filesDir` (architecture 5.10, features.md section 12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ProcessingNotificationsLocaleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun theTextFollowsTheAppLocaleAtSdk32() {
        val notifications = ProcessingNotifications(context)
        val state = ProcessingState(loading = false, running = RunningJob(NOTE, "Riunione", JobPhase.Starting))
        assertEquals("Transcribing “Riunione”", notifications.content(state).title)

        storeAppLocales("it")
        assertEquals("Trascrizione di “Riunione”", notifications.content(state).title)
        assertEquals("Avvio…", notifications.content(state).text)
        notifications.readyToTranscribe(NOTE, "Riunione")
        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(
            "Riunione è in attesa. Apri Cassini per iniziare.",
            posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertEquals(
            "Una nota è in attesa. Apri Cassini per iniziare.",
            posted.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )

        NotificationChannels.create(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals("Trascrizione", manager.getNotificationChannel(NotificationChannels.PROCESSING).name)
        assertEquals(
            "Aggiornamenti su trascrizioni e registrazioni",
            manager.getNotificationChannel(NotificationChannels.RESULTS).name,
        )
    }

    /** What `AppCompatDelegate.setApplicationLocales` writes below API 33 (`AppLocalesStorageHelper`). */
    private fun storeAppLocales(tags: String) {
        File(context.filesDir, "androidx.appcompat.app.AppCompatDelegate.application_locales_record_file")
            .writeText("<?xml version='1.0' encoding='utf-8'?><locales application_locales=\"$tags\" />")
    }

    private companion object {
        const val NOTE = "5eed0000-0000-4000-8000-000000000001"
    }
}

/**
 * From API 33 the app language is in `LocaleManager`, and the application context may not have it (an Italian UI
 * gave English notifications on a Pixel 8): every notification text still follows it (architecture 5.10). Robolectric
 * keeps the application context in English, as that device did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35])
class ProcessingNotificationsLocaleManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun theTextFollowsTheLocaleManagerLanguage() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val processing = ProcessingNotifications(context)
        val recording = RecordingNotifications(context)
        val state = ProcessingState(loading = false, running = RunningJob(NOTE, "Riunione", JobPhase.Starting))
        assertEquals("Transcribing “Riunione”", processing.content(state).title)

        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("it")
        val own = context.resources.configuration.locales[0].language
        assertEquals("the application context itself stays English", "en", own)
        assertEquals("Trascrizione di “Riunione”", processing.content(state).title)
        assertEquals("Avvio…", processing.content(state).text)
        val ongoing = processing.ongoing(state)
        val ongoingTitle = ongoing.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        assertEquals("Trascrizione di “Riunione”", ongoingTitle)
        assertEquals("Ferma", ongoing.actions.first().title.toString())
        val mib = 1_048_576L
        val download = DownloadProgress(ModelPart.SPEECH, 296 * mib, 640 * mib, verifying = false)
        val downloading = state.copy(running = RunningJob(NOTE, "Riunione", JobPhase.Downloading(download)))
        assertEquals("Download: riconoscimento vocale — 46%", processing.content(downloading).title)
        assertEquals("Registrazione", recording.content(RecordingState.Idle).title)
        assertEquals("Avvio del microfono…", recording.content(RecordingState.Idle).text)

        processing.pausedByAndroid(NOTE, "Riunione", doneMs = 288_000, totalMs = 950_000)
        val paused = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val pausedTitle = paused.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        assertEquals("Trascrizione sospesa da Android", pausedTitle)
        assertEquals(
            "In pausa a 04:48 di 15:50. Android limita il lavoro in background a circa 6 ore al giorno. Apri Cassini per riprendere.",
            paused.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )

        NotificationChannels.create(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals("Trascrizione", manager.getNotificationChannel(NotificationChannels.PROCESSING).name)

        // Back to the phone's language.
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        assertEquals("Transcribing “Riunione”", processing.content(state).title)
    }

    private companion object {
        const val NOTE = "5eed0000-0000-4000-8000-000000000001"
    }
}
