package io.codemyriad.cassini.acceptance

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.View
import android.view.inspector.WindowInspector
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.ContextCompat
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.codemyriad.cassini.MainActivity
import io.codemyriad.cassini.TestHooks
import io.codemyriad.cassini.background.Background
import io.codemyriad.cassini.core.asr.TranscriptionProgress.Stage
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.library.JobKind
import io.codemyriad.cassini.core.library.PauseReason
import io.codemyriad.cassini.core.speakers.SpeakerPrint
import io.codemyriad.cassini.engine.job.JobEvent
import io.codemyriad.cassini.engine.job.JobOutcome
import io.codemyriad.cassini.engine.job.JobPhase
import io.codemyriad.cassini.flows.Flows
import io.codemyriad.cassini.flows.Flows.awaitUi
import io.codemyriad.cassini.flows.Flows.closeKeyboard
import io.codemyriad.cassini.flows.Flows.text
import io.codemyriad.cassini.flows.Flows.waitForUi
import io.codemyriad.cassini.flows.Flows.waitGone
import io.codemyriad.cassini.flows.Flows.waitKeyboardClosed
import io.codemyriad.cassini.flows.Flows.waitShown
import io.codemyriad.cassini.processing.ProcessingJob
import io.codemyriad.cassini.processing.ProcessingJobFactory
import io.codemyriad.cassini.processing.ProcessingService
import io.codemyriad.cassini.recording.RecordingState
import io.codemyriad.cassini.recording.inProgress
import io.codemyriad.cassini.test.Manual
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import io.codemyriad.cassini.ui.R as UiR

/**
 * Captures the QA screenshot matrix (D-1155) on an Android emulator across phone and foldable sizes in light and dark
 * themes without requiring downloaded ASR models. Realistic multi-speaker meetings, saved voices, trashed notes and an
 * active transcribing draft are seeded through the real repositories and [TestHooks.jobFactory].
 *
 * Arguments:
 * - `-e qaSize SIZE`: `phone`, `fold-folded`, `fold-unfolded-portrait`, or `fold-unfolded-landscape` (required).
 * - `-e qaTheme THEME`: `light` or `dark` (default `light`).
 * - `-e qaLargestText true`: capture only the largest-text phone screens (`11-first-run`, `11-library`,
 *   `11-read-and-listen`) with `-largest-text` in the file name.
 *
 * Screenshots are written to `files/qa-shots/` as `android-NN-screen-SIZE[-largest-text]-THEME.png` for
 * `scripts/qa-screenshots.sh` to pull.
 */
@Manual
class QaScreenshotsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val graph get() = Flows.graph
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val size get() = arguments.getString(ARG_SIZE)?.takeIf { it.matches(TOKEN) }
    private val theme get() = arguments.getString(ARG_THEME)?.takeIf { it == "light" || it == "dark" } ?: "light"
    private val largestText get() = arguments.getString(ARG_LARGEST_TEXT) == "true"
    private val shots get() = File(graph.paths.root, "qa-shots")
    private var scriptedJob: ScriptedQaJob? = null

    @Before fun setUp() {
        assumeTrue("Run QA screenshots with -e $ARG_SIZE <size>", size != null)
        Background.grant(Manifest.permission.RECORD_AUDIO)
        runBlocking {
            graph.ready.await()
            Background.reset()
            TestHooks.reset()
            graph.awaitCatalogue().voices.forgetEveryone()
        }
        shots.deleteRecursively()
        shots.mkdirs()
        val import = rule.text(UiR.string.ui_library_import)
        rule.waitShown(hasText(import) or hasContentDescription(import))
    }

    @After fun tearDown() {
        try {
            scriptedJob?.finish(JobOutcome.Paused(PauseReason.USER))
            scriptedJob = null
            runBlocking {
                val recording = graph.recording
                if (recording.state.value.inProgress) recording.finish()
                recording.state.await("Recording finishes", FINISH_MS) { !it.inProgress }
                recording.dismissEnded()
                TestHooks.reset()
                Background.reset()
                graph.awaitCatalogue().voices.forgetEveryone()
            }
        } finally {
            val out = File(Background.context.filesDir, OUT_DIR)
            out.mkdirs()
            shots.listFiles().orEmpty().forEach { it.copyTo(File(out, it.name), overwrite = true) }
            Log.i(TAG, "Copied ${shots.listFiles().orEmpty().size} QA screenshots to $out")
        }
    }

    @Test fun captureFirstRun() {
        see(rule.text(UiR.string.ui_hero_title))
        if (largestText) {
            shot(fileName("11", "first-run"))
        } else {
            shot(fileName("10", "first-run"))
        }
    }

    @Test fun captureAllScreens() {
        seedMeetingsAndVoices()
        val runningNoteId = seedTranscribingMeeting()

        // 01 Library
        library()
        see(ROADMAP_TITLE)
        see(ALL_HANDS_TITLE)
        if (largestText) {
            shot(fileName("11", "library"))
            openRow(ROADMAP_TITLE)
            see(ELENA)
            shot(fileName("11", "read-and-listen"))
            return
        }
        shot(fileName("01", "library"))

        // 02 Read & Listen + 12 Note actions + 04 Name a Speaker
        openRow(ROADMAP_TITLE)
        see(ELENA)
        see(MARCUS)
        shot(fileName("02", "read-and-listen"))

        rule.onNode(hasContentDescription(rule.text(UiR.string.ui_note_more_actions))).performClick()
        see(rule.text(UiR.string.ui_export_heading))
        see(rule.text(UiR.string.ui_export_cassini_file))
        shot(fileName("12", "note-actions"))
        Espresso.pressBack()
        rule.waitGone(hasText(rule.text(UiR.string.ui_export_heading)))

        captureNameSpeaker()
        library()

        // 03 Transcribing
        openRow(ALL_HANDS_TITLE)
        see(rule.text(UiR.string.ui_tx_stop_keep))
        see(rule.text(UiR.string.ui_tx_transcript))
        shot(fileName("03", "transcribing"))
        library()

        // Pause scripted job cleanly so recording & settings stay uncluttered.
        scriptedJob?.finish(JobOutcome.Paused(PauseReason.USER))
        runBlocking {
            graph.processing.state.await("Scripted job stops", SEE_MS) { it.running?.noteId != runningNoteId }
        }

        // 05 Recording
        captureRecording()

        // 06 Search
        captureSearch()

        // 07 Settings, 09 Saved Voices, 08 Recently Deleted
        captureSettingsVoicesAndTrash()
    }

    private fun captureNameSpeaker() {
        val placeholder = rule.text(UiR.string.ui_speaker_label, PLACEHOLDER_NUM)
            .substringBefore(PLACEHOLDER_NUM.toString())
        val speaker = hasContentDescription(placeholder, substring = true) and hasClickAction()
        // The transcript, not the Library list that stays beside it when unfolded.
        val list = rule.onAllNodes(hasScrollToNodeAction() and hasAnyDescendant(speaker)).onFirst()
        rule.waitForUi("Scroll to unnamed speaker", SEE_MS) {
            runCatching { list.performScrollToNode(speaker) }.isSuccess
        }
        rule.onAllNodes(speaker).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        val save = hasText(rule.text(UiR.string.ui_sp_save)) and hasClickAction()
        rule.waitShown(save)
        see(rule.text(UiR.string.ui_sp_remember_title))
        Espresso.closeSoftKeyboard()
        rule.waitKeyboardClosed(save)
        shot(fileName("04", "name-a-speaker"))
        val cancel = hasText(rule.text(UiR.string.ui_action_cancel)) and hasClickAction()
        rule.onAllNodes(cancel).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitGone(save)
    }

    private fun captureRecording() {
        rule.onNode(hasContentDescription(rule.text(UiR.string.ui_library_record_description))).performClick()
        see(rule.text(UiR.string.ui_recording_finish))
        runBlocking {
            graph.recording.state.await("Recording starts", START_MS) { it is RecordingState.Active }
            graph.recording.state.await("Recording captures 5 seconds", START_MS + RECORD_MS) {
                it is RecordingState.Active && it.elapsedMs >= RECORD_MS
            }
        }
        shot(fileName("05", "recording"), waitForIdle = false)
        graph.recording.finish()
        runBlocking {
            graph.recording.state.await("Recording ends", FINISH_MS) { !it.inProgress }
            graph.recording.dismissEnded()
        }
        library()
    }

    private fun captureSearch() {
        rule.onNode(hasSetTextAction()).performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement(SEARCH_QUERY)
        see(ROADMAP_TITLE)
        rule.closeKeyboard()
        shot(fileName("06", "search"))
        val close = hasContentDescription(rule.text(UiR.string.ui_search_close))
        if (rule.onAllNodes(close).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNode(close).performClick()
        } else {
            rule.onNode(hasSetTextAction()).performTextReplacement("")
        }
        library()
    }

    private fun captureSettingsVoicesAndTrash() {
        rule.onNode(hasContentDescription(rule.text(UiR.string.ui_library_settings))).performClick()
        see(rule.text(UiR.string.ui_settings_transcripts), ignoreCase = true)
        shot(fileName("07", "settings"))

        val voices = hasText(rule.text(UiR.string.ui_settings_voices)) and hasClickAction()
        rule.waitShown(voices)
        rule.onNode(voices).performScrollTo().performClick()
        see(ELENA)
        see(MARCUS)
        shot(fileName("09", "saved-voices"))
        backToSettings()

        val trash = hasText(rule.text(UiR.string.ui_settings_trash)) and hasClickAction()
        rule.waitShown(trash)
        rule.onNode(trash).performScrollTo().performClick()
        see(TRASHED_TITLE)
        see(rule.text(UiR.string.ui_trash_restore))
        shot(fileName("08", "recently-deleted"))
        backToSettings()
        library()
    }

    /**
     * The screen's own Back to settings. Espresso's back key waits for the whole window to idle, which never comes
     * while the Library stays composed beside Settings on an unfolded screen.
     */
    private fun backToSettings() {
        val back = hasContentDescription(rule.text(UiR.string.ui_back_to_settings)) and hasClickAction()
        rule.waitShown(back)
        rule.onAllNodes(back).onFirst().performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun seedMeetingsAndVoices() = runBlocking {
        val catalogue = graph.awaitCatalogue()

        // 1. Trashed meeting
        val trashedFile = buildMeetingDocument(
            title = TRASHED_TITLE,
            turns = TRASHED_TURNS,
            labels = mapOf("S1" to ELENA, "S2" to TOMASZ),
        )
        val trashedId = Background.import(trashedFile)
        graph.processing.moveToTrash(trashedId)

        // 2. Audio-only field interview
        val interviewId = Background.import(Background.assetCopy("fleurs-it-short.m4a"))
        catalogue.notes.rename(interviewId, INTERVIEW_TITLE)

        // 3. Enterprise customer call
        val customerFile = buildMeetingDocument(
            title = CUSTOMER_TITLE,
            turns = CUSTOMER_TURNS,
            labels = mapOf("S1" to MARCUS, "S2" to TOMASZ),
        )
        Background.import(customerFile)

        // 4. Weekly design critique
        val critiqueFile = buildMeetingDocument(
            title = CRITIQUE_TITLE,
            turns = CRITIQUE_TURNS,
            labels = mapOf("S1" to ELENA, "S2" to PRIYA, "S3" to MARCUS),
        )
        val critiqueId = Background.import(critiqueFile)

        // 5. Primary starred meeting (Read & Listen + Name a Speaker + Search)
        val roadmapFile = buildMeetingDocument(
            title = ROADMAP_TITLE,
            turns = ROADMAP_TURNS,
            labels = mapOf("S1" to ELENA, "S2" to MARCUS, "S3" to PRIYA, "S4" to TOMASZ),
        )
        val roadmapId = Background.import(roadmapFile)
        catalogue.notes.setStarred(roadmapId, true)

        enrolSampleVoices(roadmapId, critiqueId)
    }

    private suspend fun enrolSampleVoices(roadmapId: String, critiqueId: String) {
        val catalogue = graph.awaitCatalogue()
        val roadmapVariant = checkNotNull(documentOf(roadmapId).defaultId)
        val gen = catalogue.voices.generation()
        withContext(Dispatchers.IO) {
            catalogue.voices.savePendingBlocking(
                roadmapId,
                mapOf(
                    "$roadmapVariant/S1" to syntheticPrint(0, 64.0),
                    "$roadmapVariant/S2" to syntheticPrint(1, 52.0),
                    "$roadmapVariant/S3" to syntheticPrint(2, 48.0),
                    "$roadmapVariant/S4" to syntheticPrint(3, 38.0),
                    "$roadmapVariant/S5" to syntheticPrint(4, 24.0),
                ),
                gen,
            )
        }
        catalogue.voices.adoptPending(roadmapId)
        val elena = catalogue.voices.enrol(roadmapId, roadmapVariant, "S1", null, ELENA)
        val marcus = catalogue.voices.enrol(roadmapId, roadmapVariant, "S2", null, MARCUS)
        val priya = catalogue.voices.enrol(roadmapId, roadmapVariant, "S3", null, PRIYA)
        catalogue.voices.enrol(roadmapId, roadmapVariant, "S4", null, TOMASZ)

        val critiqueVariant = checkNotNull(documentOf(critiqueId).defaultId)
        withContext(Dispatchers.IO) {
            catalogue.voices.savePendingBlocking(
                critiqueId,
                mapOf(
                    "$critiqueVariant/S1" to syntheticPrint(0, 41.0),
                    "$critiqueVariant/S2" to syntheticPrint(2, 36.0),
                    "$critiqueVariant/S3" to syntheticPrint(1, 29.0),
                ),
                catalogue.voices.generationBlocking(),
            )
        }
        catalogue.voices.adoptPending(critiqueId)
        if (elena != null) catalogue.voices.enrol(critiqueId, critiqueVariant, "S1", elena.id, ELENA)
        if (priya != null) catalogue.voices.enrol(critiqueId, critiqueVariant, "S2", priya.id, PRIYA)
        if (marcus != null) catalogue.voices.enrol(critiqueId, critiqueVariant, "S3", marcus.id, MARCUS)
    }

    private fun seedTranscribingMeeting(): String = runBlocking {
        val catalogue = graph.awaitCatalogue()
        val noteId = Background.import(Background.assetCopy("fleurs-it-short.wav"))
        catalogue.notes.rename(noteId, ALL_HANDS_TITLE)

        val job = ScriptedQaJob()
        scriptedJob = job
        TestHooks.jobFactory = ProcessingJobFactory { id -> job.also { it.noteId = id } }
        catalogue.jobs.enqueue(noteId, JobKind.TRANSCRIBE)
        ContextCompat.startForegroundService(Background.context, ProcessingService.drainIntent(Background.context))
        withTimeoutOrNull(SEE_MS) { job.started.await() }
            ?: throw AssertionError("Scripted QA job did not start for $noteId")

        val draftWords = wordsFromTurns(DRAFT_TURNS)
        val totalMs = 1_440_000L
        val doneMs = 892_000L
        job.send(JobEvent.Started(totalMs, restored = null, speakers = true, prints = true, lowMemory = false))
        job.send(JobEvent.StageChanged(Stage.WORDS, 840_000L, 900_000L))
        job.send(JobEvent.WindowDone(doneMs, totalMs, draftWords))
        job.send(JobEvent.Checkpointed(doneMs))

        graph.processing.state.await("Transcribing phase is published", SEE_MS) { state ->
            val phase = state.running?.takeIf { it.noteId == noteId }?.phase as? JobPhase.Transcribing
            phase != null && phase.draft.words.isNotEmpty()
        }
        noteId
    }

    private fun buildMeetingDocument(
        title: String,
        turns: List<Pair<String, String>>,
        labels: Map<String, String>,
    ): File {
        val audio = Background.assetCopy("fleurs-it-short.opus")
        val safeName = title.replace(Regex("[^A-Za-z0-9]+"), "-").take(40)
        val document = File(audio.parentFile, "$safeName.opus")
        val words = wordsFromTurns(turns)
        CassiniDocument.create(
            audio,
            Transcript(words, language = "en"),
            title,
            JSONObject().put("engine", "parakeet-ultra"),
            null,
            labels,
            document,
        )
        audio.delete()
        return document
    }

    private fun wordsFromTurns(turns: List<Pair<String, String>>): List<Word> {
        val words = mutableListOf<Word>()
        var cursorMs = 400L
        for ((speaker, line) in turns) {
            for (token in line.split(' ').filter { it.isNotBlank() }) {
                val durationMs = (240L + token.length * 32L).coerceAtMost(620L)
                words += Word(speaker, cursorMs, cursorMs + durationMs, token)
                cursorMs += durationMs + 70L
            }
            cursorMs += 680L
        }
        return words
    }

    private fun syntheticPrint(basis: Int, seconds: Double): SpeakerPrint {
        val embedding = FloatArray(EMBEDDING_DIM)
        embedding[basis % EMBEDDING_DIM] = 1f
        return SpeakerPrint(embedding, seconds, match = null)
    }

    private suspend fun documentOf(noteId: String): CassiniDocument {
        val row = checkNotNull(graph.awaitCatalogue().notes.rowBlocking(noteId))
        return CassiniDocument.read(File(graph.paths.note(noteId), row.audioFile))
    }

    private fun fileName(number: String, screen: String): String {
        val suffix = if (largestText) "-largest-text" else ""
        return "android-$number-$screen-${requireNotNull(size)}$suffix-$theme"
    }

    private fun see(text: String, substring: Boolean = true, ignoreCase: Boolean = false, timeoutMs: Long = SEE_MS) {
        val matcher = hasText(text, substring = substring, ignoreCase = ignoreCase) or
            hasContentDescription(text, substring = substring, ignoreCase = ignoreCase)
        rule.waitShown(matcher, timeoutMs = timeoutMs, unmerged = true)
    }

    private fun library() {
        val import = rule.text(UiR.string.ui_library_import)
        val atLibraryMatcher = hasText(import) or hasContentDescription(import)
        // Model download's Close too: without the bundled Whistle runtime (x86_64 emulators) a finished recording
        // opens it over the meeting.
        val back = hasContentDescription(rule.text(UiR.string.ui_back_to_library)) or
            hasContentDescription(rule.text(UiR.string.ui_back_to_settings)) or
            hasContentDescription(rule.text(UiR.string.ui_action_close))
        // Unfolded, the Library stays beside what it opened: close that too, so the Library stands alone.
        repeat(BACK_TRIES) {
            var hasBack = false
            rule.awaitUi(SEE_MS) {
                val nodes = rule.onAllNodes(atLibraryMatcher or back).fetchSemanticsNodes()
                hasBack = nodes.any(back::matches)
                hasBack || nodes.any(atLibraryMatcher::matches)
            }
            if (!hasBack) return@repeat
            rule.onAllNodes(back).onFirst().performSemanticsAction(SemanticsActions.OnClick)
            rule.waitForIdle()
        }
        rule.waitShown(atLibraryMatcher)
        if (rule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty()) {
            rule.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
        }
    }

    private fun openRow(title: String) {
        val row = hasContentDescription(title, substring = true) and hasClickAction() and
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
        val libraryList = hasScrollToNodeAction() and hasTestTag("library-list")
        rule.waitShown(libraryList)
        rule.waitForUi("Library lays out $title", SEE_MS) {
            runCatching { rule.onNode(libraryList).performScrollToNode(row) }.isSuccess
        }
        rule.waitShown(row)
        rule.onAllNodes(row).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitShown(hasContentDescription(rule.text(UiR.string.ui_back_to_library)))
    }

    private fun shot(name: String, waitForIdle: Boolean = true) {
        if (waitForIdle) rule.waitForIdle()
        val roots = rule.runOnUiThread {
            val activityRoot = rule.activity.window.decorView
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WindowInspector.getGlobalWindowViews()
                    .filter { it.isShown && it.windowVisibility == View.VISIBLE }
                    .ifEmpty { listOf(activityRoot) }
            } else {
                listOf(activityRoot)
            }
        }
        awaitDrawnFrames(roots, name, STILL_MS)
        val bitmap = checkNotNull(Background.instrumentation.uiAutomation.takeScreenshot()) {
            "No screenshot for $name"
        }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        bitmap.recycle()
        Log.i(TAG, "Captured $name.png")
    }

    private class ScriptedQaJob : ProcessingJob {
        override var noteId: String = ""
        private val channel = Channel<JobEvent>(Channel.UNLIMITED)
        private val result = CompletableDeferred<JobOutcome>()
        val started = CompletableDeferred<Unit>()

        override val events: ReceiveChannel<JobEvent> get() = channel

        fun send(event: JobEvent) {
            channel.trySend(event)
        }

        fun finish(outcome: JobOutcome) {
            result.complete(outcome)
        }

        override fun stop(reason: PauseReason) {
            channel.trySend(JobEvent.Stopping)
            result.complete(JobOutcome.Paused(reason))
        }

        override suspend fun run(): JobOutcome = try {
            started.complete(Unit)
            val outcome = result.await()
            channel.trySend(JobEvent.Ended(outcome))
            outcome
        } finally {
            channel.close()
        }
    }

    private companion object {
        const val TAG = "QaScreenshots"
        const val ARG_SIZE = "qaSize"
        const val ARG_THEME = "qaTheme"
        const val ARG_LARGEST_TEXT = "qaLargestText"
        const val OUT_DIR = "qa-shots"
        val TOKEN = Regex("[a-z0-9-]{1,40}")

        const val ELENA = "Elena Vance"
        const val MARCUS = "Marcus Chen"
        const val PRIYA = "Priya Nair"
        const val TOMASZ = "Tomasz Kowalski"

        const val ROADMAP_TITLE = "Q4 Product Roadmap & Mobile Parity Architecture Review"
        const val CRITIQUE_TITLE = "Weekly Design Critique — Typography, Contrast & Adaptive Spacing"
        const val CUSTOMER_TITLE = "Enterprise Customer Onboarding Call with Northstar Logistics"
        const val ALL_HANDS_TITLE = "Quarterly Engineering All-Hands & On-Device Runtime Benchmarks"
        const val INTERVIEW_TITLE = "Field Interview — Hospital Clinical Workflow Observations"
        const val TRASHED_TITLE = "Draft Architecture Brainstorm — Offline Backup & Key Recovery"
        const val SEARCH_QUERY = "foldable"

        const val EMBEDDING_DIM = 192
        const val PLACEHOLDER_NUM = 5
        const val BACK_TRIES = 4
        const val RECORD_MS = 5_000L
        const val START_MS = 20_000L
        const val FINISH_MS = 30_000L
        const val SEE_MS = 20_000L
        const val STILL_MS = 10_000L
        const val PNG_QUALITY = 100

        val ROADMAP_TURNS = listOf(
            "S1" to "Good morning everyone, thanks for joining the Q4 roadmap review. " +
                "Today we want to lock in the mobile parity milestones across phone and foldable layouts " +
                "before our next internal build.",
            "S2" to "Sounds good. On the Android runtime side, the new on-device pipeline is holding steady " +
                "on Pixel 9 and Pixel 9 Pro Fold, with memory well under our ceiling even on ninety minute recordings.",
            "S3" to "From design, our biggest priority is checking how the transcript measure, playback bar, " +
                "and speaker sheets breathe on both the narrow cover display and the unfolded inner screen.",
            "S4" to "I ran the benchmark suite yesterday afternoon. Exported portable Opus documents verify cleanly " +
                "across desktop, iOS, and Android, and speaker colours stay consistent when you reopen a meeting.",
            "S1" to "That is huge for trust. What about the largest accessibility text sizes? " +
                "We want First Run, Library, and Read and Listen to stay completely legible without clipping controls.",
            "S3" to "We audited the bottom action bar and the note rows at two hundred percent font scale. " +
                "Wrapping the record button label and stacking metadata cleanly fixed the crowding from last sprint.",
            "S5" to "Quick question on speaker naming: when someone joins halfway through a workshop and only speaks " +
                "for twenty seconds, does the sheet still offer to remember their voice for future meetings?",
            "S2" to "Yes, as long as we capture a clean voiceprint segment, the sheet lets you name them and toggle " +
                "Remember this voice. Otherwise it still renames every turn in this transcript right away.",
            "S4" to "And search indexes only the spoken words, never synthetic labels, so searching for foldable " +
                "or architecture jumps straight to the matching turn and highlights the exact snippet.",
            "S1" to "Perfect. Let us capture fresh light and dark screenshots across every form factor today " +
                "so Alex can review the full gallery and flag any spacing tweaks before we cut the release branch.",
        )

        val CRITIQUE_TURNS = listOf(
            "S1" to "Let us review the latest contrast tokens for light and dark themes across Library, " +
                "Settings, and Saved Voices.",
            "S2" to "The warm parchment background in light mode pairs nicely with the deeper charcoal surface " +
                "in dark mode, and the six speaker colours remain distinct at every text size.",
            "S3" to "Agreed. Let us also double-check the foldable unfolded landscape layout to make sure " +
                "the reading column keeps a comfortable line length.",
        )

        val CUSTOMER_TURNS = listOf(
            "S1" to "Thanks for walking us through your regional dispatch calls. Everything stays completely " +
                "on device, so your legal and compliance teams never have to worry about audio leaving the phone.",
            "S2" to "We also tested sharing portable Cassini documents over internal email and verifying them " +
                "on the desktop viewer, and the round trip took less than two seconds.",
        )

        val TRASHED_TURNS = listOf(
            "S1" to "This was our early scratch recording comparing backup transport options before we finalized " +
                "the automatic recovery flow.",
            "S2" to "We can safely clear this draft from Recently Deleted once the new documentation lands.",
        )

        val DRAFT_TURNS = listOf(
            "S1" to "Welcome to the quarterly engineering all-hands. Let us start with the on-device speech " +
                "recognition benchmarks across our supported ARM64 handsets.",
            "S2" to "Over the past month we cut cold-start latency by thirty-four percent while keeping word " +
                "error rate flat on noisy conference room recordings.",
            "S3" to "We also shipped incremental checkpointing every window, so if you pause a long transcription " +
                "or switch apps, you never lose a single decoded paragraph.",
            "S1" to "Next up, let us walk through the foldable display QA pass and the upcoming offline search " +
                "improvements for multi-hour meetings.",
        )
    }
}

private suspend fun <T : Any> Flow<T>.await(
    message: String,
    timeoutMs: Long = Background.WAIT_MS,
    matches: (T) -> Boolean,
) {
    withTimeoutOrNull(timeoutMs) { first(matches) }
        ?: throw AssertionError("$message (waited ${timeoutMs / 1_000} s)")
}
