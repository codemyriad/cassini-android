package org.cassini.android.flows

import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.espresso.Espresso
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.cassini.android.MainActivity
import org.cassini.android.background.Background
import org.cassini.android.flows.Flows.import
import org.cassini.android.flows.Flows.rows
import org.cassini.android.flows.Flows.text
import org.cassini.android.flows.Flows.waitFor
import org.cassini.android.flows.Flows.waitShown
import org.cassini.android.flows.Flows.waitText
import org.cassini.android.processing.StartOrigin
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.DocumentState
import org.cassini.core.library.JobKind
import org.cassini.test.NeedsModels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.cassini.ui.R as UiR

/**
 * Naming a draft speaker through a saved voice (features.md sections 3 and 4). The first half of the FLEURS clip is
 * transcribed, and its speaker is saved as "Luca" and, with the same print, as "Marco": two saved voices that sound
 * the same, so the job asks instead of naming automatically (VoiceMatcher's margin rule). That note then goes to the
 * trash, so the voices keep no live note. The second half (the same speaker, other words; twelve times, so the draft
 * stays on screen for a while) is transcribed from its note, the draft asks "Is this Luca?", and "Yes, Luca" names
 * the speaker. After the job is done the sealed document carries the label, and Saved voices lists Luca with 1 note.
 */
@NeedsModels
class SpeakerNamingFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val graph get() = Flows.graph

    @Before fun setUp() = runBlocking {
        Background.grant()
        graph.ready.await()
        Background.reset()
        forgetEveryone()
    }

    @After fun tearDown() = runBlocking {
        Background.reset()
        forgetEveryone()
    }

    private suspend fun forgetEveryone() {
        graph.awaitCatalogue().voices.forgetEveryone()
    }

    @Test fun aSavedVoiceIsSuggestedInTheDraftAndYesNamesTheSpeaker() {
        val (first, second) = halves(Background.assetCopy("fleurs-it-short.wav"))
        val catalogue = runBlocking { graph.awaitCatalogue() }

        // The first transcription saves the voice, then its note goes.
        val firstId = runBlocking { import(first) }
        runBlocking { graph.processing.transcribe(firstId, JobKind.TRANSCRIBE, StartOrigin.SCREEN) }
        val sealed = awaitDocument(firstId)
        val variant = checkNotNull(sealed.defaultId)
        val speaker = sealed.selected(variant)!!.transcript!!.words.first().speaker
        val luca = runBlocking { catalogue.voices.enrol(firstId, variant, speaker, null, NAME) }
        assertNotNull("The first transcription has a print to save", luca)
        // The same print again as a new person: Luca comes first in people.json, and Marco ties with him.
        assertNotNull(runBlocking { catalogue.voices.enrol(firstId, variant, speaker, null, RIVAL) })
        runBlocking { graph.processing.moveToTrash(firstId) }

        // The second copy: the draft suggests Luca, and Yes names the speaker.
        val secondId = runBlocking { import(second) }
        val title = runBlocking { rows().single { it.id == secondId }.title }
        rule.waitShown(hasContentDescription(title, substring = true))
        rule.onNode(hasContentDescription(title, substring = true)).performClick()
        val transcribe = hasText(rule.text(UiR.string.ui_action_transcribe)) and hasClickAction()
        rule.waitShown(transcribe)
        rule.onAllNodes(transcribe).onFirst().performClick()
        val yes = hasText(rule.text(UiR.string.ui_suggestion_yes, NAME)) and hasClickAction()
        rule.waitShown(yes, timeoutMs = Flows.JOB_MS)
        val state = runBlocking { catalogue.jobs.job(secondId) }?.state
        Log.i(TAG, "Draft suggestion shown; job $state")
        assertNotNull("The question is in the draft, while the job runs", state)
        rule.onNode(yes).performSemanticsAction(SemanticsActions.OnClick)

        val document = awaitDocument(secondId)
        val labelled = document.selected(null)!!.transcript!!.words.first().speaker
        assertEquals("The sealed document carries the name", NAME, document.speakerLabel(labelled))

        // Saved voices: Luca, with this note only.
        Espresso.pressBack()
        rule.waitShown(hasContentDescription(rule.text(UiR.string.ui_library_settings)))
        rule.onNode(hasContentDescription(rule.text(UiR.string.ui_library_settings))).performClick()
        rule.waitShown(hasText(rule.text(UiR.string.ui_voices_title), substring = true) and hasClickAction())
        rule.onNode(hasText(rule.text(UiR.string.ui_voices_title), substring = true) and hasClickAction())
            .performClick()
        rule.waitText(NAME)
        val notes = rule.activity.resources.getQuantityString(UiR.plurals.ui_count_notes, 1, "1")
        rule.waitShown(hasText(notes, substring = true))
        val people = runBlocking { catalogue.voices.people().first() }.voices.associate { it.voice.name to it.notes }
        assertEquals(mapOf(NAME to 1, RIVAL to 0), people)
    }

    /** Waits for the note's job to end with a verified document, and returns it. */
    private fun awaitDocument(noteId: String): CassiniDocument {
        var document: CassiniDocument? = null
        rule.waitFor("The job seals a verified document", Flows.JOB_MS) {
            val row = graph.awaitCatalogue().notes.rowBlocking(noteId)
            val done = row != null && graph.awaitCatalogue().jobs.job(noteId) == null &&
                row.documentState == DocumentState.OK
            if (done) document = CassiniDocument.read(File(graph.paths.note(noteId), row.audioFile))
            done
        }
        return checkNotNull(document)
    }

    /**
     * The two halves of a PCM WAV file as WAV files with the same format, in its directory; the second half is
     * written [SECOND_REPEATS] times.
     */
    private fun halves(wav: File): Pair<File, File> {
        val bytes = wav.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var at = RIFF_HEADER
        var format: ByteArray? = null
        var data: IntRange? = null
        while (at + CHUNK_HEADER <= bytes.size && data == null) {
            val id = String(bytes, at, 4, Charsets.US_ASCII)
            val size = header.getInt(at + 4)
            val body = at + CHUNK_HEADER until at + CHUNK_HEADER + size
            if (id == "fmt ") format = bytes.copyOfRange(body.first, body.last + 1)
            if (id == "data") data = body
            at = body.last + 1 + (size and 1)
        }
        val fmt = checkNotNull(format)
        val pcm = checkNotNull(data)
        val blockAlign = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN).getShort(BLOCK_ALIGN_AT).toInt()
        val middle = pcm.first + (pcm.count() / 2 / blockAlign) * blockAlign
        fun write(name: String, from: Int, to: Int, repeats: Int = 1): File = File(wav.parentFile, name).also { file ->
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                val length = (to - from) * repeats
                val head = ByteBuffer.allocate(RIFF_HEADER + CHUNK_HEADER * 2 + fmt.size).order(ByteOrder.LITTLE_ENDIAN)
                head.put("RIFF".toByteArray()).putInt(head.capacity() - CHUNK_HEADER + length).put("WAVE".toByteArray())
                head.put("fmt ".toByteArray()).putInt(fmt.size).put(fmt)
                head.put("data".toByteArray()).putInt(length)
                out.write(head.array())
                repeat(repeats) { out.write(bytes, from, to - from) }
            }
        }
        return write("naming-first.wav", pcm.first, middle) to write("naming-second.wav", middle, pcm.last + 1, SECOND_REPEATS)
    }

    private companion object {
        const val TAG = "SpeakerNamingFlowTest"
        const val NAME = "Luca"
        const val RIVAL = "Marco"
        const val SECOND_REPEATS = 12
        const val RIFF_HEADER = 12
        const val CHUNK_HEADER = 8
        const val BLOCK_ALIGN_AT = 12
    }
}
