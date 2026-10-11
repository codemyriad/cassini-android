package org.cassini.ui.banner

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.cassini.ui.format.FIXED_ZONE
import org.cassini.ui.format.FormatSettings
import org.cassini.ui.format.ModelBytes
import org.cassini.ui.format.ModelPartUi
import org.cassini.ui.theme.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

private const val MIN = 60_000L
private const val MIB = 1_048_576L

/** The arguments of the board 10 copy for each kind that reads arguments. */
private val BOARD_ARGS: Map<BannerKind, BannerArgs> = mapOf(
    BannerKind.REC_STORAGE_LOW to BannerArgs(ms1 = 10 * MIN + 20_000),
    BannerKind.REC_STOPPED_STORAGE to BannerArgs(bytes = 200 * MIB),
    BannerKind.RECOVERED to BannerArgs(count = 1, ms1 = 5 * MIN + 12_000),
    BannerKind.TX_PAUSED to BannerArgs(ms1 = 12 * MIN + 40_000, ms2 = 34 * MIN + 12_000),
    BannerKind.TX_QUEUE_HELD to BannerArgs(count = 2),
    BannerKind.TX_FAILED to BannerArgs(text = "The audio could not be decoded."),
    BannerKind.DL_DOWNLOADING to
        BannerArgs(part = ModelPartUi.SPEECH, percent = 46, bytes = 296 * MIB, bytes2 = ModelBytes.SPEECH),
    BannerKind.DL_CHECKING to BannerArgs(percent = 80),
    BannerKind.DL_NO_SPACE to BannerArgs(bytes = ModelBytes.REQUIRED_FREE),
    BannerKind.LIB_NO_MATCH to BannerArgs(text = "fattura"),
    BannerKind.IMPORTING to BannerArgs(text = "call.m4a"),
    BannerKind.IMPORT_NO_SPACE to BannerArgs(bytes = 1_500_000_000L),
    BannerKind.NO_ENCODER to BannerArgs(m4a = true),
)

internal fun boardArgs(kind: BannerKind): BannerArgs = BOARD_ARGS[kind] ?: BannerArgs()

/** The English copy of every kind with [boardArgs]: title, body and action labels (features.md section 13). */
private val ENGLISH: Map<BannerKind, Triple<String, String?, List<String>>> = mapOf(
    BannerKind.TRUST_VERIFIED to
        Triple("Verified", "Audio matches the transcript exactly. Everything works.", listOf()),
    BannerKind.TRUST_NOT_VERIFIED to
        Triple("Not verified", "The audio could not be checked, so word timing may be off.", listOf("Play anyway")),
    BannerKind.TRUST_AUDIO_CHANGED to
        Triple(
            "Audio changed since transcription",
            "Word timing may not match what you hear.",
            listOf("Transcribe again"),
        ),
    BannerKind.TRUST_UNREADABLE to Triple(
        "Transcript can’t be read",
        "The file’s Cassini data is damaged. The audio still plays.",
        listOf("Transcribe again"),
    ),
    BannerKind.TRUST_NEWER_FORMAT to
        Triple("Newer Cassini format", "This version can’t be read here yet. The audio still plays.", listOf()),
    BannerKind.PLAIN_AUDIO to Triple("Plain audio", "No transcript yet.", listOf("Transcribe")),
    BannerKind.UNPLAYABLE to Triple(
        "This recording can’t be played",
        "Cassini closed before the recording was saved. You can still save the file.",
        listOf(),
    ),
    BannerKind.REC_PAUSED_OTHER_APP to
        Triple(
            "Paused — another app is using audio",
            "Resume when your call ends. Nothing was lost.",
            listOf("Resume"),
        ),
    BannerKind.REC_STORAGE_LOW to Triple(
        "Storage almost full — about 10\u00A0min left",
        "Free some space to keep going. If it runs out, the recording saves automatically.",
        listOf(),
    ),
    BannerKind.REC_STOPPED_FILE_LIMIT to Triple(
        "Stopped — recording file is full",
        "Cassini ends M4A recordings at 1\u00A0GB, about 74\u00A0hours. The recording so far is saved. Start a new recording to continue.",
        listOf("Open note"),
    ),
    BannerKind.REC_STOPPED_STORAGE to Triple(
        "Stopped — storage almost full",
        "Less than 200 MB free. The recording so far is saved.",
        listOf("Open note"),
    ),
    BannerKind.REC_MIC_OFF to Triple(
        "Microphone access is off",
        "Turn it on in system settings. Recording starts when you come back.",
        listOf("Open settings"),
    ),
    BannerKind.REC_MIC_FAILED to Triple(
        "Microphone stopped working",
        "Recording ended. The audio so far is in your library.",
        listOf("Open note"),
    ),
    BannerKind.REC_COULD_NOT_START to
        Triple("Couldn’t start the microphone", "Another app may be using it. Try again.", listOf("Try again")),
    BannerKind.RECOVERED to Triple(
        "1 recording recovered",
        "Cassini closed unexpectedly. We saved what was captured (05:12).",
        listOf("Open"),
    ),
    BannerKind.TX_PAUSED to Triple(
        "Paused at 12:40 of 34:12",
        "Pick up where you left off. The draft so far is kept.",
        listOf("Resume", "Start over"),
    ),
    BannerKind.TX_PAUSED_EARLY to
        Triple("Paused before any words were found", "Resume to start transcribing.", listOf("Resume")),
    BannerKind.TX_QUEUE_HELD to
        Triple("Transcription paused · 2 waiting", "Continue when you’re ready.", listOf("Continue")),
    BannerKind.TX_CALCULATING to
        Triple("Calculating time left…", "An estimate appears after the first few minutes of audio.", listOf()),
    BannerKind.TX_BATTERY to Triple("Measuring battery use…", "Plugged in — no battery estimate needed.", listOf()),
    BannerKind.TX_ONE_VOICE to Triple(
        "All words shown as one voice",
        "Speakers couldn’t be told apart in this recording.",
        listOf("Transcribe again"),
    ),
    BannerKind.TX_CHECKPOINT_MISMATCH to Triple(
        "Saved progress no longer matches",
        "The recording or models changed. Your recording is kept.",
        listOf("Start over"),
    ),
    BannerKind.TX_MEMORY to Triple("Not enough memory", "Close other apps or try a shorter clip.", listOf("Try again")),
    BannerKind.TX_FAILED to Triple(
        "Couldn’t transcribe",
        "The audio could not be decoded. Your recording is kept.",
        listOf("Try again"),
    ),
    BannerKind.DL_DOWNLOADING to Triple("Downloading speech recognition — 46%", "296 of 640 MB.", listOf("Cancel")),
    BannerKind.DL_CHECKING to Triple("Checking files — 80%", "Each file is verified before it is used.", listOf()),
    BannerKind.DL_NO_CONNECTION to Triple(
        "No connection",
        "Connect to the internet and try again. Recording still works.",
        listOf("Retry"),
    ),
    BannerKind.DL_STOPPED to Triple(
        "Download stopped",
        "The connection dropped. Try again to continue where it stopped.",
        listOf("Retry"),
    ),
    BannerKind.DL_NO_SPACE to Triple("Not enough storage", "Free about 860 MB and try again.", listOf("Retry")),
    BannerKind.DL_FILE_CHECK to Triple(
        "File check failed",
        "The download was damaged and has been discarded.",
        listOf("Download again"),
    ),
    BannerKind.DL_INSTALL_FAILED to
        Triple("Couldn’t finish installing", "Try again. Your recordings are safe.", listOf("Try again")),
    BannerKind.DL_RECORD_NOW to Triple(
        "Record now, transcribe later",
        "Transcription needs a one-time download. Audio is safe meanwhile.",
        listOf("Download"),
    ),
    BannerKind.LIB_EMPTY to
        Triple("No notes yet", "Record something, or import an audio file.", listOf("Record", "Import")),
    BannerKind.LIB_NO_MATCH to Triple("Nothing matches “fattura”", "Try another word or title.", listOf()),
    BannerKind.IMPORTING to Triple("Importing call.m4a…", null, listOf()),
    BannerKind.IMPORT_TOO_LARGE to Triple("File too large", "Choose a file under 4 GB.", listOf("Choose another")),
    BannerKind.IMPORT_NO_SPACE to Triple(
        "Not enough space to import",
        "This file needs about 1.4 GB free. Free some space and try again.",
        listOf("Try again"),
    ),
    BannerKind.IMPORT_WAV to
        Triple("Unsupported WAV", "Use 16-bit PCM WAV, or MP3, M4A or Opus.", listOf("Choose another")),
    BannerKind.IMPORT_OPEN to
        Triple("Can’t open this file", "It doesn’t look like audio Cassini can read.", listOf("Choose another")),
    BannerKind.NO_ENCODER to Triple(
        "This phone can’t create Cassini files",
        "It has no Opus encoder. You can still record (M4A) and open documents.",
        listOf(),
    ),
    BannerKind.NOTIFICATIONS_OFF to
        Triple("Notifications are off", "Progress shows only in the app.", listOf("Turn on")),
    BannerKind.VOICES_UNREADABLE to Triple(
        "Saved voices can’t be read",
        "They were made by a newer Cassini or are damaged. Nothing was changed.",
        listOf(),
    ),
    BannerKind.LIB_UNAVAILABLE to Triple(
        "Your notes can’t be opened",
        "Your files are kept. Free some storage, then open Cassini again.",
        listOf(),
    ),
)

/** A value of each [BannerArgs] field that differs from the default, to check [BannerKind.uses]. */
private fun BannerArgs.vary(arg: BannerArg): BannerArgs =
    when (arg) {
        BannerArg.COUNT -> copy(count = 7)
        BannerArg.TEXT -> copy(text = "x.wav")
        BannerArg.MS1 -> copy(ms1 = 62 * MIN + 3_000)
        BannerArg.MS2 -> copy(ms2 = 83 * MIN)
        BannerArg.BYTES -> copy(bytes = 5_000_000_000L)
        BannerArg.BYTES2 -> copy(bytes2 = 950 * MIB)
        BannerArg.PERCENT -> copy(percent = 37)
        BannerArg.M4A -> copy(m4a = true)
        BannerArg.PART -> copy(part = ModelPartUi.VOICES)
        BannerArg.BY_SYSTEM -> copy(bySystem = true)
        BannerArg.FILE_LIMIT -> copy(fileLimit = true)
    }

private val PLACEHOLDER = Regex("%(\\d+\\$)?[sd]")

/** The banner catalogue in English: every kind of features.md section 13 with its board copy and flags. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class BannerKindTest {
    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val f = FormatSettings(Locale.ENGLISH, is24Hour = true, zone = FIXED_ZONE)

    private fun title(kind: BannerKind, args: BannerArgs = boardArgs(kind)) = BannerText.title(res, kind, args, f)

    private fun body(kind: BannerKind, args: BannerArgs = boardArgs(kind)) = BannerText.body(res, kind, args, f)

    @Test fun everyKindHasItsBoardCopy() {
        assertEquals(BannerKind.entries.toSet(), ENGLISH.keys)
        for (kind in BannerKind.entries) {
            val (title, body, actions) = ENGLISH.getValue(kind)
            val args = boardArgs(kind)
            assertEquals(kind.name, title, BannerText.title(res, kind, args, f))
            assertEquals(kind.name, body, BannerText.body(res, kind, args, f))
            assertEquals(kind.name, actions, kind.actions.map { BannerText.action(res, it) })
        }
    }

    @Test fun variantsOfTheCopy() {
        assertEquals(
            "It has no Opus encoder. You can still record and open documents.",
            BannerText.body(res, BannerKind.NO_ENCODER, BannerArgs(m4a = false), f),
        )
        assertEquals("Your recording is kept.", BannerText.body(res, BannerKind.TX_FAILED, BannerArgs(), f))
        assertEquals(
            "Recording file almost full — about 10\u00A0min left",
            title(BannerKind.REC_STORAGE_LOW, BannerArgs(ms1 = 10 * MIN, fileLimit = true)),
        )
        assertEquals(
            "Cassini ends M4A recordings at 1\u00A0GB and saves them. To keep going, finish and start a new recording.",
            BannerText.body(res, BannerKind.REC_STORAGE_LOW, BannerArgs(fileLimit = true), f),
        )
        assertEquals(
            "No transcript yet.",
            BannerText.body(res, BannerKind.PLAIN_AUDIO, BannerArgs(), f),
        )
        assertEquals("Too long.", BannerText.body(res, BannerKind.PLAIN_AUDIO, BannerArgs(text = "Too long."), f))
        val paused = "Android paused it: background work has a limit of about 6 hours a day. Resume to continue where it stopped."
        assertEquals(paused, BannerText.body(res, BannerKind.TX_PAUSED, BannerArgs(bySystem = true), f))
        assertEquals(paused, BannerText.body(res, BannerKind.TX_PAUSED_EARLY, BannerArgs(bySystem = true), f))
        assertEquals(
            "Android stopped the download. Try again to continue where it stopped.",
            BannerText.body(res, BannerKind.DL_STOPPED, BannerArgs(bySystem = true), f),
        )
        assertEquals("3 recordings recovered", title(BannerKind.RECOVERED, BannerArgs(count = 3)))
        assertEquals(
            "Downloading speaker separation — 50%",
            title(
                BannerKind.DL_DOWNLOADING,
                BannerArgs(part = ModelPartUi.SPEAKERS, bytes = 31 * MIB, bytes2 = 62 * MIB),
            ),
        )
        assertEquals(
            "Paused at 1:02:03 of 1:23:00",
            title(BannerKind.TX_PAUSED, BannerArgs(ms1 = 62 * MIN + 3_000, ms2 = 83 * MIN)),
        )
    }

    @Test fun usesListsExactlyTheArgumentsTheCopyReads() {
        for (kind in BannerKind.entries) {
            val base = BannerArgs()
            val text = BannerText.title(res, kind, base, f) to BannerText.body(res, kind, base, f)
            for (arg in BannerArg.entries) {
                val varied = base.vary(arg)
                val other = BannerText.title(res, kind, varied, f) to BannerText.body(res, kind, varied, f)
                if (arg in kind.uses) {
                    assertNotEquals("$kind should read $arg", text, other)
                } else {
                    assertEquals("$kind should not read $arg", text, other)
                }
            }
        }
    }

    @Test fun flagsTonesAndGroups() {
        assertEquals(
            setOf(BannerKind.TX_CALCULATING, BannerKind.TX_BATTERY),
            BannerKind.entries.filterNot { it.appEmitted }.toSet(),
        )
        val dismissible = BannerKind.entries.filter { it.dismissible }.toSet()
        assertEquals(setOf(BannerKind.RECOVERED, BannerKind.NO_ENCODER), dismissible)
        assertEquals(
            mapOf(
                BannerGroup.TRUST to 7,
                BannerGroup.RECORDING to 8,
                BannerGroup.TRANSCRIPTION to 9,
                BannerGroup.DOWNLOAD to 8,
                BannerGroup.LIBRARY to 11,
            ),
            BannerKind.entries.groupingBy { it.group }.eachCount(),
        )
        assertTrue(BannerKind.entries.all { it.actions.size <= 2 })
        assertFalse(BannerKind.entries.any { it.tone == Tone.Outline })
        assertEquals(Tone.Ok, BannerKind.TRUST_VERIFIED.tone)
        assertEquals(Tone.Plain, BannerKind.LIB_NO_MATCH.tone)
        assertEquals(Tone.Warn, BannerKind.NO_ENCODER.tone)
        assertEquals(Tone.Info, BannerKind.RECOVERED.tone)
        assertEquals(Tone.Error, BannerKind.TX_FAILED.tone)
        assertNull(BannerText.body(res, BannerKind.IMPORTING, BannerArgs(text = "a.m4a"), f))
    }

    @Test fun specDefaultsComeFromTheKind() {
        val spec = BannerSpec(BannerKind.RECOVERED, BannerArgs(count = 1))
        assertEquals(listOf(BannerAction.OPEN), spec.actions)
        assertTrue(spec.dismissible)
        assertFalse(spec.announce)
        assertEquals("RECOVERED", spec.id)
        assertFalse(BannerSpec(BannerKind.TX_MEMORY).dismissible)
        assertEquals("1 recording recovered", BannerText.title(res, spec, f))
    }

    @Test fun noPlaceholderIsLeft() {
        for (kind in BannerKind.entries) {
            for (args in listOf(BannerArgs(), boardArgs(kind))) {
                val title = BannerText.title(res, kind, args, f)
                val body = BannerText.body(res, kind, args, f)
                assertFalse("$kind title: $title", PLACEHOLDER.containsMatchIn(title))
                assertFalse("$kind body: $body", body != null && PLACEHOLDER.containsMatchIn(body))
            }
        }
    }
}

/** The catalogue in Italian: every kind resolves a non-empty title, the body exists where English has one. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "it")
class BannerKindItalianTest {
    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val f = FormatSettings(Locale.ITALIAN, is24Hour = true, zone = FIXED_ZONE)

    private fun title(kind: BannerKind, args: BannerArgs = boardArgs(kind)) = BannerText.title(res, kind, args, f)

    private fun body(kind: BannerKind, args: BannerArgs = boardArgs(kind)) = BannerText.body(res, kind, args, f)

    @Test fun everyKindResolvesInItalian() {
        for (kind in BannerKind.entries) {
            val args = boardArgs(kind)
            val title = BannerText.title(res, kind, args, f)
            val body = BannerText.body(res, kind, args, f)
            assertTrue("$kind title is empty", title.isNotBlank())
            assertFalse("$kind title: $title", PLACEHOLDER.containsMatchIn(title))
            assertEquals("$kind body presence", ENGLISH.getValue(kind).second != null, body != null)
            assertFalse("$kind body: $body", body != null && (body.isBlank() || PLACEHOLDER.containsMatchIn(body)))
            assertTrue(kind.actions.all { BannerText.action(res, it).isNotBlank() })
        }
    }

    @Test fun italianCopyWithArguments() {
        assertEquals("In pausa a 12:40 di 34:12", title(BannerKind.TX_PAUSED))
        assertEquals("1 registrazione recuperata", title(BannerKind.RECOVERED))
        assertEquals("3 registrazioni recuperate", title(BannerKind.RECOVERED, BannerArgs(count = 3)))
        assertEquals("Libera circa 860 MB e riprova.", body(BannerKind.DL_NO_SPACE))
        assertEquals(
            "Questo file richiede circa 1,4 GB liberi. Libera spazio e riprova.",
            body(BannerKind.IMPORT_NO_SPACE),
        )
        assertEquals("Nessun risultato per “fattura”", title(BannerKind.LIB_NO_MATCH))
        assertEquals("Riproduci comunque", BannerText.action(res, BannerAction.PLAY_ANYWAY))
    }
}
