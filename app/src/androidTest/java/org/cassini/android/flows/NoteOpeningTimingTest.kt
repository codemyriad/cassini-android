package org.cassini.android.flows

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.cassini.android.CassiniTestRunner
import org.cassini.android.MainActivity
import org.cassini.android.background.Background
import org.cassini.android.flows.Flows.import
import org.cassini.android.flows.Flows.rows
import org.cassini.android.flows.Flows.waitGone
import org.cassini.android.flows.Flows.waitShown
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.OggOpus
import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.cassini.test.Manual
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The FAST NOTE OPENING timing check (docs/development.md): the time from a tap on a Library row to the first
 * transcript turn on screen, for documents of about one hour and ten minutes and for the 15 s FLEURS document.
 * [firstAndLaterOpens] builds and imports them, then opens each one four times (the first open, then later opens).
 * The instrumentation then ends, which kills the process; [opensAfterProcessDeath], in a new process on the same
 * root, opens each one three times again. Later opens must not grow with the length of the audio.
 *
 * Both run only from scripts/note-opening-timing.sh, which gives `-e keepRoot`, so the second run opens the notes of
 * the first. They log one line per open with the tag CassiniTiming.
 */
@Manual
class NoteOpeningTimingTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun needsAKeptRoot() {
        val kept = InstrumentationRegistry.getArguments().getString(CassiniTestRunner.ARG_KEEP_ROOT)
        assumeTrue("Run it with scripts/note-opening-timing.sh", kept != null)
        runBlocking {
            Background.grant()
            Flows.graph.ready.await()
        }
    }

    @Test fun firstAndLaterOpens() {
        runBlocking {
            if (rows().none { it.title == LONG }) import(document(LONG, HOUR_MS, "Ada", "Ben"))
            if (rows().none { it.title == SHORT }) import(document(SHORT, 0, "Carla", "Dario"))
            if (rows().none { it.title == MEDIUM }) import(document(MEDIUM, TEN_MINUTES_MS, "Elsa", "Fabio"))
        }
        measure("first", "later", "later", "later")
    }

    @Test fun opensAfterProcessDeath() {
        val titles = setOf(LONG, SHORT, MEDIUM)
        assumeTrue("Run firstAndLaterOpens first", runBlocking { rows().count { it.title in titles } } == titles.size)
        measure("after process death", "later", "later")
    }

    /**
     * Opens each note once per label, in rounds (the notes take turns, so the warm-up of the process does not favour
     * one of them), and logs the time to its first turn.
     */
    private fun measure(vararg labels: String) {
        labels.forEach { label -> NOTES.forEach { (title, speaker) -> open(title, speaker, label) } }
    }

    /** Opens the note [title] once and logs the time to its first turn (headed by [speaker]). */
    private fun open(title: String, speaker: String, label: String) {
        val row = hasContentDescription(title, substring = true)
        val turn = hasContentDescription("$speaker, ", substring = true)
        val audioMs = runBlocking { rows().single { it.title == title }.durationMs }
        rule.waitShown(row, timeoutMs = IMPORT_MS)
        rule.waitForIdle()
        val started = SystemClock.elapsedRealtime()
        rule.onNode(row).performClick()
        rule.waitShown(turn, timeoutMs = OPEN_MS)
        val ms = SystemClock.elapsedRealtime() - started
        val line = JSONObject().put("note", title).put("audioMs", audioMs).put("open", label).put("ms", ms)
        Log.i(TAG, line.toString())
        Espresso.pressBack()
        rule.waitGone(turn)
    }

    /**
     * A sealed document over the FLEURS Opus clip repeated to about [lengthMs] (once when 0), with the clip's words
     * once per repetition and the speakers [first] and [second] taking turns per repetition.
     */
    private fun document(title: String, lengthMs: Long, first: String, second: String): File {
        val clip = Background.assetCopy("fleurs-it-short.opus")
        val stream = OggOpus.read(clip.readBytes())
        val repeats = maxOf(1, (lengthMs / stream.durationMs).toInt())
        val packets = List(repeats) { stream.audio }.flatten()
        val granule = packets.sumOf { OggOpus.packetSamples(it).toLong() }
        val audio = File(clip.parentFile, "$title-audio.opus")
        audio.writeBytes(OggOpus.mux(stream.copy(audio = packets, finalGranule = granule), stream.tags))
        val step = stream.durationMs / CLIP_WORDS.size
        val words = (0 until repeats).flatMap { r ->
            CLIP_WORDS.mapIndexed { i, text ->
                val start = r * stream.durationMs + i * step
                Word(if (r % 2 == 0) "S1" else "S2", start, start + step - WORD_GAP_MS, text)
            }
        }
        val target = File(clip.parentFile, "$title.opus")
        CassiniDocument.create(
            audio,
            Transcript(words, "it"),
            title,
            JSONObject().put("engine", "timing"),
            null,
            mapOf("S1" to first, "S2" to second),
            target,
        )
        clip.delete()
        audio.delete()
        Log.i(TAG, "Built $title: ${repeats * stream.durationMs} ms, ${words.size} words, ${target.length()} bytes")
        return target
    }

    private companion object {
        const val TAG = "CassiniTiming"
        const val LONG = "timing-one-hour"
        const val SHORT = "timing-fleurs"

        /**
         * The reference for the one-hour note: more words than the first page (FIRST_PAGE) that the Note screen
         * shows first. The 15 s note has one turn, so its screen has less to draw.
         */
        const val MEDIUM = "timing-ten-minutes"
        const val HOUR_MS = 3_600_000L
        const val TEN_MINUTES_MS = 600_000L

        /** Each note with the speaker of its first turn. */
        val NOTES = listOf(LONG to "Ada", SHORT to "Carla", MEDIUM to "Elsa")
        const val WORD_GAP_MS = 40L
        const val IMPORT_MS = 180_000L
        const val OPEN_MS = 60_000L
        val CLIP_WORDS = (
            "La parte settentrionale, conosciuta come catena della sentinella, vanta le montagne più alte " +
                "dell'Antartide, il massiccio del Vinson, che raggiunge i 4892 metri con il monte Vinson."
            ).split(' ')
    }
}
