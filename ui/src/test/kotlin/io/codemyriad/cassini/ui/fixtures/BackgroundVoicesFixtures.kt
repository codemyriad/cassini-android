package io.codemyriad.cassini.ui.fixtures

import io.codemyriad.cassini.ui.components.SnackbarKind
import io.codemyriad.cassini.ui.components.SnackbarSpec
import io.codemyriad.cassini.ui.components.SpeakerNameUi
import io.codemyriad.cassini.ui.components.TurnUi
import io.codemyriad.cassini.ui.note.MeetingSpeakerUi
import io.codemyriad.cassini.ui.note.NameSheetUi
import io.codemyriad.cassini.ui.note.NoteChipsUi
import io.codemyriad.cassini.ui.note.SaveState
import io.codemyriad.cassini.ui.note.SpeakerChipUi
import io.codemyriad.cassini.ui.note.TrustUi
import io.codemyriad.cassini.ui.note.VoiceOptionUi
import io.codemyriad.cassini.ui.theme.SpeakerTone
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * Background voices and the speaker row (cassini-ux D112 / L-86; board Feature-BackgroundVoices 2F): the meeting
 * "Incontro ORA" with seven speakers, two of them background voices.
 */
internal object BackgroundVoicesFixtures {
    private val silvio = TurnUi(
        id = "bg1",
        speakerId = "s1",
        name = SpeakerNameUi.Named("Silvio"),
        tone = SpeakerTone.One,
        startMs = clock(4, 12),
        words = timedWords("Allora, partiamo dal calendario. La prima consegna resta a fine mese?", clock(4, 12)),
    )
    private val ruurd = TurnUi(
        id = "bg2",
        speakerId = "s2",
        name = SpeakerNameUi.Named("Ruurd"),
        tone = SpeakerTone.Two,
        startMs = clock(4, 20),
        words = timedWords("Yes, end of the month for the first part. The second can slip a week.", clock(4, 20)),
    )
    private val stranger5 = TurnUi(
        id = "bg3",
        speakerId = "s5",
        name = SpeakerNameUi.Placeholder(5),
        tone = SpeakerTone.Unnamed,
        startMs = clock(4, 31),
        words = timedWords("Two cappuccinos and a croissant, please.", clock(4, 31)),
        background = true,
    )
    private val stranger6 = TurnUi(
        id = "bg4",
        speakerId = "s6",
        name = SpeakerNameUi.Placeholder(6),
        tone = SpeakerTone.Unnamed,
        startMs = clock(4, 36),
        words = timedWords("…no, the other one, by the window…", clock(4, 36)),
        background = true,
    )
    private val marta = TurnUi(
        id = "bg5",
        speakerId = "s3",
        name = SpeakerNameUi.Named("Marta"),
        tone = SpeakerTone.Three,
        startMs = clock(4, 41),
        words = timedWords("Then I’ll book the room for the review on the fifteenth.", clock(4, 41)),
    )

    val transcript = BoardFixtures.readAndListen.copy(
        title = "Incontro ORA",
        chips = NoteChipsUi(trust = TrustUi.VERIFIED, speakers = 7),
        turns = persistentListOf(silvio, ruurd, stranger5, stranger6, marta),
        meetingSpeakers = listOf(silvio, ruurd, marta).map {
            MeetingSpeakerUi(it.speakerId, it.name, it.tone, startMs = it.startMs)
        }.toImmutableList(),
        activeWord = null,
        playback = BoardFixtures.readAndListen.playback?.copy(playing = false),
    )

    val snackbar = transcript.copy(
        snackbar = SnackbarSpec(
            SnackbarKind.NAMED_AND_BACKGROUND,
            "Marta",
            count = 4,
            undo = true,
            durationMs = 60_000,
        ),
    )

    private val chips = listOf(
        SpeakerChipUi("s1", SpeakerNameUi.Named("Silvio"), SpeakerTone.One),
        SpeakerChipUi("s2", SpeakerNameUi.Named("Ruurd"), SpeakerTone.Two),
        SpeakerChipUi("s3", SpeakerNameUi.Placeholder(3), SpeakerTone.Unnamed),
        SpeakerChipUi("s4", SpeakerNameUi.Placeholder(4), SpeakerTone.Unnamed, background = true),
        SpeakerChipUi("s5", SpeakerNameUi.Placeholder(5), SpeakerTone.Unnamed, background = true),
        SpeakerChipUi("s6", SpeakerNameUi.Placeholder(6), SpeakerTone.Unnamed),
        SpeakerChipUi("s7", SpeakerNameUi.Placeholder(7), SpeakerTone.Unnamed),
    ).toImmutableList()

    /** 2C: Speaker 3, named as today, with the row of every speaker. */
    val sheetRow = NameSheetUi(
        speakerId = "s3",
        label = SpeakerNameUi.Placeholder(3),
        tone = SpeakerTone.Unnamed,
        speakingMs = clock(3, 10),
        quote = "Then I’ll book the room for the review on the fifteenth.",
        canSample = true,
        name = "",
        voices = persistentListOf(
            VoiceOptionUi(id = "lea", name = "Lea", likely = true, tone = SpeakerTone.One),
            VoiceOptionUi(id = "giulia", name = "Giulia Rossi", tone = SpeakerTone.Two),
        ),
        ranked = true,
        remember = true,
        rememberEnabled = true,
        canMerge = true,
        save = SaveState.ENABLED,
        speakers = chips,
        canBackground = true,
    )

    /** 2F: Speaker 6 switched to a background voice. */
    val sheetBackground = sheetRow.copy(
        speakerId = "s6",
        label = SpeakerNameUi.Placeholder(6),
        speakingMs = 9_000,
        quote = "…no, the other one, by the window…",
        voices = persistentListOf(),
        background = true,
        speakers = chips.map { if (it.speakerId == "s6") it.copy(background = true) else it }.toImmutableList(),
    )
}
