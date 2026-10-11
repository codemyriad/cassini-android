package org.cassini.android.acceptance

import android.Manifest
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToLog
import androidx.core.os.LocaleListCompat
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.cassini.android.CassiniTestRunner
import org.cassini.android.MainActivity
import org.cassini.android.ScriptedModels
import org.cassini.android.ScriptedModels.Step
import org.cassini.android.TestHooks
import org.cassini.android.background.Background
import org.cassini.android.flows.Flows
import org.cassini.android.flows.Flows.tapWord
import org.cassini.android.flows.Flows.text
import org.cassini.android.flows.Flows.waitFor
import org.cassini.android.flows.Flows.waitGone
import org.cassini.android.flows.Flows.waitShown
import org.cassini.android.processing.StartOrigin
import org.cassini.android.recording.RecordingState
import org.cassini.android.recording.RecordingWarning
import org.cassini.android.recording.inProgress
import org.cassini.core.Failure
import org.cassini.core.audio.Limits
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.DocumentState
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.engine.job.JobPhase
import org.cassini.test.Manual
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import org.cassini.ui.R as UiR

/**
 * The acceptance walk through every board on emulator-5554 (P12, docs/development.md "Acceptance walk"). It saves a
 * screenshot of each board as `<scratch root>/acceptance/NN-board.png` and, when the test ends, copies them to
 * `files/acceptance-out/<label>/`, where `scripts/acceptance-walk.sh` collects them.
 *
 * - [realModels] runs with the installed models. It seeds notes through the real repositories and the coordinator:
 *   one transcription to Done, two saved voices from it, a second transcription that runs during the walk, a third
 *   one paused, one queued, one in the trash, one Audio only, and two document copies, one with an audio byte
 *   flipped (Not verified) and one with a damaged manifest (Can't read transcript). Then it drives [MainActivity]
 *   through the Library, Recording, Transcribing, Read & listen, Name a speaker, Note actions, Saved voices, Settings,
 *   Trash and the Paused, Not verified, Unreadable and Storage almost full banners.
 * - [downloadScreens] runs on an empty model root (`-e emptyModelRoot true`) with [ScriptedModels] as the fetcher, so
 *   nothing is downloaded: Model download, Downloading 46 %, Checking 80 %, No connection, Not enough storage and
 *   File check failed.
 *
 * Arguments: `-e acceptanceLabel NAME` names the output folder; `-e acceptanceLanguage it` switches the app to
 * Italian through Settings first and back to the phone language at the end. The night mode is set by the script.
 */
@Manual
class AcceptanceWalkTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val graph get() = Flows.graph
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val label get() = arguments.getString(ARG_LABEL)?.takeIf { it.matches(LABEL) } ?: "run"
    private val italian get() = arguments.getString(ARG_LANGUAGE) == "it"
    private val shots get() = File(graph.paths.root, "acceptance")
    private var languageChanged = false

    @Before fun setUp() {
        Background.grant(Manifest.permission.RECORD_AUDIO)
        runBlocking {
            graph.ready.await()
            Background.reset()
            TestHooks.reset()
            graph.awaitCatalogue().voices.forgetEveryone()
        }
        shots.deleteRecursively()
        shots.mkdirs()
    }

    @After fun tearDown() {
        try {
            runBlocking {
                val recording = graph.recording
                if (recording.state.value.inProgress) recording.finish()
                Background.waitFor("The recording ends", FINISH_MS) {
                    recording.state.value.takeUnless { it.inProgress }
                }
                recording.dismissEnded()
                TestHooks.reset()
                Background.reset()
                graph.awaitCatalogue().voices.forgetEveryone()
            }
        } finally {
            if (languageChanged) {
                // The walk ends on the phone language through Settings; this is only for a walk that failed.
                rule.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }
            }
            val out = File(Background.context.filesDir, "$OUT/$label")
            shots.listFiles().orEmpty().forEach { it.copyTo(File(out, it.name), overwrite = true) }
            Log.i(TAG, "Copied ${shots.listFiles().orEmpty().size} screenshots to $out")
        }
    }

    // Part A: the installed models. ---------------------------------------------------------------------------------

    @Test fun realModels() {
        assertFalse("Run the real-model walk without emptyModelRoot", CassiniTestRunner.emptyModelRoot)
        if (italian) chooseLanguage(ITALIANO, "it")
        val notes = seed()

        // 00 Library: the progress banner and the queued row.
        library()
        see(rule.text(UiR.string.ui_progress_transcribing, WEEKLY))
        see(rule.text(UiR.string.ui_status_queued))
        see(rule.text(UiR.string.ui_library_privacy))
        shot("00-library")

        recording()
        transcribing()
        readAndListen(notes.done)
        pausedAndNaming()
        trust(VERIFY_COPY, UiR.string.ui_banner_trust_not_verified_body, "10-states-not-verified")
        trust(DAMAGED_COPY, UiR.string.ui_banner_trust_unreadable_title, "10-states-unreadable")
        settingsVoicesAndTrash()

        // The recording made at the start ends now, from the Library.
        library()
        graph.recording.finish()
        rule.waitFor("The recording is saved", FINISH_MS) { graph.recording.state.value is RecordingState.Ended }
        if (italian) chooseLanguage(rule.text(UiR.string.ui_settings_language_phone), null)
    }

    /** The notes of the walk, oldest first, and the jobs that run, pause and wait in the queue. */
    private fun seed(): Seeded = runBlocking {
        val catalogue = graph.awaitCatalogue()
        suspend fun note(asset: String, title: String): String {
            val id = Background.import(Background.assetCopy(asset))
            catalogue.notes.rename(id, title)
            return id
        }
        // Imported documents keep the title inside them.
        Background.import(notVerifiedCopy())
        Background.import(damagedCopy())
        val trashed = note("generated/acceptance-x3.wav", TEST_RECORDING)
        note("fleurs-it-short.m4a", VOICE_MEMO)
        val paused = note("generated/acceptance-x8.wav", BOARD_REVIEW)
        val done = note("fleurs-it-short.wav", SUPPLIER_CALL)
        val queued = note("generated/acceptance-x2.wav", INTERVIEW)
        val running = note("generated/acceptance-x23.wav", WEEKLY)
        graph.processing.moveToTrash(trashed)

        // One transcription to Done, and its speaker saved twice with the same print: two voices that sound the
        // same, so that a later draft asks instead of naming automatically (the VoiceMatcher margin rule).
        graph.processing.transcribe(done, JobKind.TRANSCRIBE, StartOrigin.SCREEN)
        val sealed = awaitDocument(done)
        val variant = checkNotNull(sealed.defaultId)
        val speaker = sealed.selected(variant)!!.transcript!!.words.first().speaker
        catalogue.documents.relabel(done, mapOf(speaker to ANNA))
        // The last enrolment keeps the note's link, so Anna Ricci, the speaker's label, lists this note.
        val first = catalogue.voices.enrol(done, variant, speaker, null, PAOLO)
        assertNotNull("The first transcription has a print", first)
        assertNotNull(catalogue.voices.enrol(done, variant, speaker, null, ANNA))

        // A transcription paused after its first words.
        graph.processing.transcribe(paused, JobKind.TRANSCRIBE, StartOrigin.SCREEN)
        Background.waitFor("The paused note has draft words", Background.WAIT_MS) {
            graph.processing.state.value.running?.takeIf { it.noteId == paused }
                ?.let { it.phase as? JobPhase.Transcribing }?.takeIf { it.draft.words.isNotEmpty() }
        }
        assertTrue(graph.processing.stop(paused))
        Background.waitFor("The note is paused with a checkpoint", Background.WAIT_MS) {
            catalogue.jobs.job(paused)?.takeIf { it.state == JobState.PAUSED && it.words > 0 }
        }

        // The long note runs during the walk; the short one waits behind it.
        graph.processing.transcribe(running, JobKind.TRANSCRIBE, StartOrigin.SCREEN)
        graph.processing.transcribe(queued, JobKind.TRANSCRIBE, StartOrigin.SCREEN)
        Background.waitFor("The long note shows draft words", Background.WAIT_MS) {
            graph.processing.state.value.running?.takeIf { it.noteId == running }
                ?.let { it.phase as? JobPhase.Transcribing }?.takeIf { it.draft.words.isNotEmpty() }
        }
        Seeded(done = done, paused = paused, running = running)
    }

    private data class Seeded(val done: String, val paused: String, val running: String)

    /** 01 Recording for 8 s, then the Storage almost full banner, then back minimises; the recording goes on. */
    private fun recording() {
        rule.onNode(hasContentDescription(rule.text(UiR.string.ui_library_record_description))).performClick()
        rule.waitFor("The recording starts", START_MS) { graph.recording.state.value is RecordingState.Active }
        rule.waitFor("Eight seconds are captured", START_MS + CAPTURE_MS) {
            ((graph.recording.state.value as? RecordingState.Active)?.elapsedMs ?: 0) >= CAPTURE_MS
        }
        see(rule.text(UiR.string.ui_recording_finish))
        see(rule.text(UiR.string.ui_recording_hint), substring = true)
        shot("01-recording")

        // Storage almost full: the recording sees about 12 minutes of space at the rate its file grows.
        TestHooks.recordingFreeBytes = { directory ->
            val elapsed = (graph.recording.state.value as? RecordingState.Active)?.elapsedMs ?: 0
            val bytes = directory.listFiles().orEmpty().sumOf { it.length() }
            val rate = if (elapsed > 0) bytes * MS_PER_S / elapsed else 0
            Limits.MIN_RECORDING_FREE_BYTES + rate * LOW_SPACE_S
        }
        rule.waitFor("The recording warns about storage", START_MS) {
            (graph.recording.state.value as? RecordingState.Active)?.warning is RecordingWarning.StorageLow
        }
        see(prefix(UiR.string.ui_banner_rec_storage_low_title))
        see(rule.text(UiR.string.ui_banner_rec_storage_low_body))
        shot("10-states-storage-low")
        TestHooks.recordingFreeBytes = null
        rule.waitFor("The warning ends", START_MS) {
            (graph.recording.state.value as? RecordingState.Active)?.warning == null
        }

        Espresso.pressBack()
        see(rule.text(UiR.string.ui_library_recording_tap))
        assertTrue("Back minimises", graph.recording.state.value is RecordingState.Active)
    }

    /** 02 Transcribing: the running note with its draft. */
    private fun transcribing() {
        openRow(WEEKLY)
        see(rule.text(UiR.string.ui_draft_title))
        see(rule.text(UiR.string.ui_action_stop_keep_progress))
        shot("02-transcribing")
        library()
    }

    /** 03 Read & listen with a word playing, then 06 Note actions. */
    private fun readAndListen(noteId: String) {
        openRow(SUPPLIER_CALL)
        see(rule.text(UiR.string.ui_trust_verified))
        val document = runBlocking { documentOf(noteId) }
        val words = document.selected(null)!!.transcript!!.words
        val word = words.first { it.text.filter(Char::isLetter).length >= WORD_LETTERS }
        val letters = word.text.filter(Char::isLetter)
        see(ANNA)
        rule.waitShown(hasText(letters, substring = true), unmerged = true)
        // The player opens when the screen does; a tap before it is ready seeks nothing.
        rule.waitFor("The player opens the note", PLAYER_MS) {
            graph.playback.state.value.let { it.noteId == noteId && it.durationMs > 0 }
        }
        rule.onAllNodes(hasText(letters, substring = true), useUnmergedTree = true).onFirst().tapWord(letters)
        rule.waitFor("The word tap plays from \"$letters\": ${graph.playback.state.value}", PLAYER_MS) {
            graph.playback.state.value.playing
        }
        // The screen shows it: the Pause button and a position past the start of the word.
        rule.waitShown(hasContentDescription(rule.text(UiR.string.ui_player_pause)))
        rule.waitFor("The position passes \"$letters\": ${graph.playback.state.value}", PLAYER_MS) {
            graph.playback.state.value.let { it.playing && it.positionMs > word.startMs }
        }
        shot("03-read-and-listen")
        Log.i(TAG, "Read & listen shot at ${graph.playback.state.value}")
        rule.runOnUiThread { graph.playback.pause() }

        rule.onNode(hasContentDescription(rule.text(UiR.string.ui_note_share_more))).performClick()
        see(rule.text(UiR.string.ui_actions_save_copy), ignoreCase = true)
        see(rule.text(UiR.string.ui_actions_document))
        see(rule.text(UiR.string.ui_actions_document_info))
        shot("06-note-actions-and-export")
        Espresso.pressBack()
        rule.waitGone(hasText(rule.text(UiR.string.ui_actions_document)))
        library()
    }

    /** The Paused banner, then 04 Name a speaker on the paused draft: "Anna" asks about Anna Ricci. */
    private fun pausedAndNaming() {
        openRow(BOARD_REVIEW)
        see(prefix(UiR.string.ui_banner_tx_paused_title))
        see(rule.text(UiR.string.ui_banner_tx_paused_body))
        shot("10-states-paused")

        val placeholder = rule.text(UiR.string.ui_speaker_label, NUMBER).substringBefore(NUMBER.toString())
        val speaker = hasContentDescription(placeholder, substring = true) and hasClickAction()
        rule.waitShown(speaker)
        rule.onAllNodes(speaker).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        val save = hasText(rule.text(UiR.string.ui_name_save)) and hasClickAction()
        rule.waitShown(save)
        see(rule.text(UiR.string.ui_name_remember))
        rule.onAllNodes(hasSetTextAction()).onLast().performTextReplacement(FIRST_NAME)
        see(rule.text(UiR.string.ui_name_same_person_saved, ANNA))
        see(rule.text(UiR.string.ui_name_same_person))
        // Board 04 shows the sheet without the keyboard.
        Espresso.closeSoftKeyboard()
        shot("04-name-a-speaker")
        val cancel = hasText(rule.text(UiR.string.ui_action_cancel)) and hasClickAction()
        rule.onAllNodes(cancel).onLast().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitGone(save)
        library()
    }

    /** A trust banner of a document copy: [expected] shows on its note. */
    private fun trust(title: String, expected: Int, name: String) {
        openRow(title)
        see(rule.text(expected))
        shot(name)
        library()
    }

    /** 08 Settings, 07 Saved voices, 09 Trash. */
    private fun settingsVoicesAndTrash() {
        rule.onNode(hasContentDescription(rule.text(UiR.string.ui_library_settings))).performClick()
        see(rule.text(UiR.string.ui_settings_models), ignoreCase = true)
        see(rule.text(UiR.string.ui_settings_model_ready))
        shot("08-settings")

        val voices = hasText(rule.text(UiR.string.ui_settings_voices), substring = true) and hasClickAction()
        rule.waitShown(voices)
        rule.onAllNodes(voices).onFirst().performClick()
        see(ANNA)
        see(PAOLO)
        see(rule.text(UiR.string.ui_voices_privacy))
        shot("07-saved-voices")
        Espresso.pressBack()

        val trash = hasText(rule.text(UiR.string.ui_settings_trash), substring = true) and hasClickAction()
        rule.waitShown(trash)
        rule.onAllNodes(trash).onFirst().performClick()
        see(TEST_RECORDING)
        see(rule.text(UiR.string.ui_trash_restore))
        see(rule.text(UiR.string.ui_trash_intro))
        shot("09-trash")
        Espresso.pressBack()
        library()
    }

    // Part B: the download screens on an empty model root. ----------------------------------------------------------

    @Test fun downloadScreens() {
        assertTrue("Run the download walk with -e emptyModelRoot true", CassiniTestRunner.emptyModelRoot)
        if (italian) chooseLanguage(ITALIANO, "it")
        runBlocking {
            val id = Background.import(Background.assetCopy("fleurs-it-short.wav"))
            graph.awaitCatalogue().notes.rename(id, SUPPLIER_CALL)
        }
        library()
        openRow(SUPPLIER_CALL)
        val transcribe = hasText(rule.text(UiR.string.ui_action_transcribe)) and hasClickAction()
        rule.waitShown(transcribe)
        rule.onAllNodes(transcribe).onFirst().performClick()

        // 05 Model download: every part is missing, 730 MB in all.
        see(rule.text(UiR.string.ui_models_title))
        see(TOTAL_MB, substring = true)
        see(rule.text(UiR.string.ui_part_speech))
        see(rule.text(UiR.string.ui_part_speakers))
        see(rule.text(UiR.string.ui_part_voices))
        shot("05-model-download")

        ScriptedModels.script(
            Step.Progress(DOWNLOAD_PERCENT, verifying = false),
            Step.Progress(CHECK_PERCENT, verifying = true),
            Step.Fail(Failure.DOWNLOAD, online = false),
            Step.Fail(Failure.SPACE),
            Step.Fail(Failure.VERIFY),
        )
        val start = hasText(rule.text(UiR.string.ui_models_download_and_transcribe)) and hasClickAction()
        rule.onNode(start).performClick()

        see(rule.text(UiR.string.ui_part_downloading_speech, DOWNLOAD_PERCENT), timeoutMs = Flows.JOB_MS)
        shot("10-states-downloading")
        ScriptedModels.release()
        see(rule.text(UiR.string.ui_banner_dl_checking_title, CHECK_PERCENT), timeoutMs = Flows.JOB_MS)
        see(rule.text(UiR.string.ui_banner_dl_checking_body))
        shot("10-states-checking")
        ScriptedModels.release()

        see(rule.text(UiR.string.ui_banner_dl_no_connection_title), timeoutMs = Flows.JOB_MS)
        see(rule.text(UiR.string.ui_banner_dl_no_connection_body))
        shot("10-states-no-connection")
        clickAction(UiR.string.ui_action_retry)
        see(rule.text(UiR.string.ui_banner_dl_no_space_title), timeoutMs = Flows.JOB_MS)
        see(prefix(UiR.string.ui_banner_dl_no_space_body))
        shot("10-states-no-space")
        clickAction(UiR.string.ui_action_retry)
        see(rule.text(UiR.string.ui_banner_dl_file_check_title), timeoutMs = Flows.JOB_MS)
        see(rule.text(UiR.string.ui_banner_dl_file_check_body))
        shot("10-states-file-check")
        assertEquals(
            "Nothing was installed",
            emptyList<String>(),
            File(graph.paths.modelRoot, "parakeet-v3").listFiles().orEmpty().map { it.name },
        )
        library()
        if (italian) chooseLanguage(rule.text(UiR.string.ui_settings_language_phone), null)
    }

    // Helpers. -------------------------------------------------------------------------------------------------------

    /** Waits until [text] shows, in a text or a content description (merged rows describe their children). */
    private fun see(text: String, substring: Boolean = true, ignoreCase: Boolean = false, timeoutMs: Long = SEE_MS) {
        val matcher = hasText(text, substring = substring, ignoreCase = ignoreCase) or
            hasContentDescription(text, substring = substring, ignoreCase = ignoreCase)
        rule.waitShown(matcher, timeoutMs = timeoutMs, unmerged = true)
    }

    /** The text of [id] before its first argument: "Paused at " of "Paused at %1$s of %2$s". */
    private fun prefix(id: Int): String = rule.text(id, MARK, MARK).substringBefore(MARK)

    /** Clicks the banner action [id] (the last one on screen: the banner's own, not a header button). */
    private fun clickAction(id: Int) {
        val action = hasText(rule.text(id)) and hasClickAction()
        rule.waitShown(action)
        rule.onAllNodes(action).onLast().performSemanticsAction(SemanticsActions.OnClick)
    }

    /**
     * Goes back until the Library shows, and scrolls it to the top. Its bottom bar (Import audio) is always on screen;
     * the header scrolls with the list.
     */
    private fun library() {
        toLibrary()
        val settings = hasContentDescription(rule.text(UiR.string.ui_library_settings))
        rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(settings)
        rule.waitShown(settings)
    }

    private fun toLibrary() {
        // A text button, or an icon with this description while a recording runs.
        val import = rule.text(UiR.string.ui_library_import)
        val settings = hasText(import) or hasContentDescription(import)
        fun shown(timeoutMs: Long) = runCatching {
            rule.waitUntil(timeoutMs) { rule.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() }
        }.isSuccess
        if (shown(FIRST_LOOK_MS)) return
        // Each back waits for its screen change: one back too many would close the app.
        repeat(BACK_TRIES) { attempt ->
            if (attempt > 0) {
                val roots = rule.onAllNodes(isRoot())
                roots.fetchSemanticsNodes().indices.forEach { roots[it].printToLog(TAG) }
                shot("debug-back-$attempt")
            }
            Espresso.pressBack()
            if (shown(BACK_MS)) return
        }
        rule.waitShown(settings)
    }

    /**
     * Scrolls the Library to the row of [title] and opens it. A row has a long click (the actions sheet), which its
     * "· Resume" suffix and the progress banner do not have. The click is a semantics action: after the scroll the
     * row can sit under the bottom bar, where a touch would reach the Record button.
     */
    private fun openRow(title: String) {
        val row = hasContentDescription(title, substring = true) and hasClickAction() and
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
        rule.waitShown(hasScrollToNodeAction())
        rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(row)
        rule.waitShown(row)
        rule.onAllNodes(row).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitShown(hasContentDescription(rule.text(UiR.string.ui_back_to_library)))
    }

    /**
     * Picks the [segment] of App language in Settings and goes back to the Library. [language] is the app language
     * it gives (null: the phone's); the activity is created again with it.
     */
    private fun chooseLanguage(segment: String, language: String?) {
        library()
        rule.onNode(hasContentDescription(rule.text(UiR.string.ui_library_settings))).performClick()
        val choice = hasText(segment) and hasClickAction()
        rule.waitShown(choice)
        rule.onNode(choice).performClick()
        languageChanged = language != null
        rule.waitFor("The app language is ${language ?: "the phone's"}") {
            val locales = AppCompatDelegate.getApplicationLocales()
            val shown = runCatching { rule.activity.resources.configuration.locales[0].language }.getOrNull()
            if (language == null) locales.isEmpty else locales[0]?.language == language && shown == language
        }
        rule.waitShown(hasText(rule.text(UiR.string.ui_settings_language), ignoreCase = true))
        library()
    }

    /** Saves a screenshot of the whole screen as `<name>.png` once the transitions have settled. */
    private fun shot(name: String) {
        SystemClock.sleep(SETTLE_MS)
        val bitmap = checkNotNull(Background.instrumentation.uiAutomation.takeScreenshot()) { "No screenshot" }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        bitmap.recycle()
        Log.i(TAG, "Screenshot $name")
    }

    /** Waits for the note's job to end with a verified document, and returns it. */
    private suspend fun awaitDocument(noteId: String): CassiniDocument {
        Background.waitFor("The job seals a verified document", Background.WAIT_MS) {
            val row = graph.awaitCatalogue().notes.rowBlocking(noteId)
            row?.takeIf { graph.awaitCatalogue().jobs.job(noteId) == null && it.documentState == DocumentState.OK }
        }
        return documentOf(noteId)
    }

    private suspend fun documentOf(noteId: String): CassiniDocument {
        val row = checkNotNull(graph.awaitCatalogue().notes.rowBlocking(noteId))
        return CassiniDocument.read(File(graph.paths.note(noteId), row.audioFile))
    }

    /** A document whose last audio byte is flipped: its Ogg page check fails, so the audio cannot be verified. */
    private fun notVerifiedCopy(): File {
        val file = Flows.meetingDocument(VERIFY_COPY)
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        check(CassiniDocument.read(file).documentState == DocumentState.UNVERIFIED) { "The copy is not unverified" }
        return file
    }

    /** A document whose manifest checksum is changed: the Cassini data cannot be read, the audio still plays. */
    private fun damagedCopy(): File {
        val file = Flows.meetingDocument(DAMAGED_COPY)
        val bytes = file.readBytes()
        val tag = MANIFEST_SHA.toByteArray(Charsets.US_ASCII)
        val at = (0..bytes.size - tag.size).first { start -> tag.indices.all { bytes[start + it] == tag[it] } }
        val digit = at + tag.size
        bytes[digit] = (if (bytes[digit] == '0'.code.toByte()) '1' else '0').code.toByte()
        file.writeBytes(bytes)
        val state = CassiniDocument.read(file).documentState
        check(state == DocumentState.INVALID_METADATA) { "The damaged copy reads as $state" }
        return file
    }

    private companion object {
        const val TAG = "AcceptanceWalk"
        const val ARG_LABEL = "acceptanceLabel"
        const val ARG_LANGUAGE = "acceptanceLanguage"
        const val OUT = "acceptance-out"
        val LABEL = Regex("[a-z0-9-]{1,32}")

        const val WEEKLY = "Weekly product sync"
        const val INTERVIEW = "Interview — Marta"
        const val SUPPLIER_CALL = "Call with supplier"
        const val BOARD_REVIEW = "Board review prep"
        const val VOICE_MEMO = "Voice memo"
        const val TEST_RECORDING = "Test recording"
        const val VERIFY_COPY = "Contract call copy"
        const val DAMAGED_COPY = "Damaged export"
        const val ANNA = "Anna Ricci"
        const val PAOLO = "Paolo Ferri"
        const val FIRST_NAME = "Anna"
        const val ITALIANO = "Italiano"
        const val TOTAL_MB = "730"
        const val MANIFEST_SHA = "CASSINI_PAYLOAD_SHA256="
        const val MARK = "\u0000"
        const val NUMBER = 987

        const val DOWNLOAD_PERCENT = 46
        const val CHECK_PERCENT = 80
        const val WORD_LETTERS = 6
        const val START_MS = 20_000L
        const val CAPTURE_MS = 8_000L
        const val FINISH_MS = 30_000L
        const val SEE_MS = 20_000L
        const val PLAYER_MS = 60_000L
        const val SETTLE_MS = 800L
        const val BACK_TRIES = 3
        const val BACK_MS = 5_000L
        const val FIRST_LOOK_MS = 200L
        const val MS_PER_S = 1_000L
        const val LOW_SPACE_S = 12 * 60L
        const val PNG_QUALITY = 100
    }
}
