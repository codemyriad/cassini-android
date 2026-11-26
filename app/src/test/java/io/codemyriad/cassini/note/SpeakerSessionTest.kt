package io.codemyriad.cassini.note

import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.core.speakers.Match
import io.codemyriad.cassini.core.speakers.SampleWindow
import io.codemyriad.cassini.core.speakers.Voice
import io.codemyriad.cassini.ui.components.SnackbarKind
import io.codemyriad.cassini.ui.components.SpeakerNameUi
import io.codemyriad.cassini.ui.note.SaveState
import io.codemyriad.cassini.ui.theme.SpeakerTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Name sheet with its speaker row and the Background voice switch (cassini-ux D112 / L-86): changes stay while
 * the user moves between speakers, one Save applies them all, and Undo knows how to put them back.
 */
class SpeakerSessionTest {
    private val anna = Voice(
        id = "anna",
        name = "Anna Ricci",
        model = ModelCatalog.voiceprintModel.sha256,
        mean = FloatArray(ModelCatalog.VOICEPRINT_DIM).also { it[0] = 1f },
        count = 1,
        seconds = 30.0,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun input(
        id: String,
        label: SpeakerNameUi = SpeakerNameUi.Placeholder(id.removePrefix("s").toInt()),
        background: Boolean = false,
        draft: Boolean = false,
        match: Match? = null,
    ) = NamingInput(
        speakerId = id,
        label = label,
        tone = SpeakerTone.Unnamed,
        speakingMs = 12_000,
        sample = SampleWindow(0, 5_000),
        draft = draft,
        print = if (draft) null else FloatArray(ModelCatalog.VOICEPRINT_DIM).also { it[1] = 1f },
        printSeconds = 12.0,
        willHavePrint = draft,
        match = match,
        voices = listOf(anna),
        background = background,
        canBackground = !draft,
    )

    /** Seven speakers, as in the ticket's "Done when"; s1 and s2 are named. */
    private val chips = (1..7).map { n ->
        val label = when (n) {
            1 -> SpeakerNameUi.Named("Silvio")
            2 -> SpeakerNameUi.Named("Ruurd")
            else -> SpeakerNameUi.Placeholder(n)
        }
        SessionChip("s$n", label, SpeakerTone.One)
    }

    private fun open(id: String, background: Boolean = false): NamingSession =
        NamingSession.of(SpeakerNaming.open(input(id, chips.first { it.speakerId == id }.label, background)), chips)

    private fun NamingSession.select(id: String, background: Boolean = false): NamingSession =
        forms[id]?.let(::show) ?: show(SpeakerNaming.open(input(id, chips.first { it.speakerId == id }.label, background)))

    private fun NamingSession.on(vararg events: NamingEvent): NamingSession =
        events.fold(this) { session, event ->
            SpeakerNaming.reduce(session, event).session ?: error("closed on $event")
        }

    @Test fun theRowListsEverySpeakerAndMarksBackgroundVoicesAsTheFormsHaveThem() {
        val session = open("s3").on(NamingEvent.BackgroundToggled(true))
        val ui = SpeakerNaming.ui(session)
        assertEquals(chips.map { it.speakerId }, ui.speakers.map { it.speakerId })
        assertTrue(ui.speakers.first { it.speakerId == "s3" }.background)
        assertFalse(ui.speakers.first { it.speakerId == "s4" }.background)
        assertTrue(ui.background)
        assertTrue(ui.canBackground)
        assertEquals(SaveState.ENABLED, ui.save)
    }

    @Test fun aSingleSpeakerHasNoRow() {
        val single = NamingSession.of(SpeakerNaming.open(input("s3")), chips.take(1))
        assertTrue(SpeakerNaming.ui(single).speakers.isEmpty())
    }

    @Test fun nameOneSwitchTwoMoveAroundThenSaveOnce() {
        val session = open("s3")
            .on(NamingEvent.NameChanged("Marta"), NamingEvent.RememberToggled(false))
            .select("s5").on(NamingEvent.BackgroundToggled(true))
            .select("s6").on(NamingEvent.BackgroundToggled(true))
            .select("s7")
            .select("s3")
        // The typed name is still there after moving around.
        assertEquals("Marta", session.form.name)
        assertEquals(SaveState.ENABLED, SpeakerNaming.ui(session).save)
        val result = SpeakerNaming.reduce(session, NamingEvent.Save)
        assertNull(result.session)
        val effects = result.effects
        assertEquals(listOf(NamingEffect.Relabel("s3", "Marta")), effects.filterIsInstance<NamingEffect.Relabel>())
        assertEquals(
            listOf(NamingEffect.Background("s5", true), NamingEffect.Background("s6", true)),
            effects.filterIsInstance<NamingEffect.Background>(),
        )
        assertEquals(NamingEffect.Close, effects.last())
        val undo = SpeakerUndo.of(session, effects) { (it as? SpeakerNameUi.Named)?.text ?: "Speaker" }
        // s3 had no stored label (a placeholder without one): Undo writes back what the file had, its id.
        assertEquals(mapOf("s3" to "s3"), undo.labels)
        assertEquals(mapOf("s5" to false, "s6" to false), undo.background)
        assertEquals(SpeakerUndo.Summary(SnackbarKind.NAMED_AND_BACKGROUND, "Marta", 2), undo.summary)
    }

    @Test fun aBackgroundVoiceKeepsItsNameAndGetsNoVoiceprint() {
        val session = open("s1").on(NamingEvent.NameChanged("Silvio T"), NamingEvent.BackgroundToggled(true))
        val effects = SpeakerNaming.reduce(session, NamingEvent.Save).effects
        assertEquals(listOf(NamingEffect.Background("s1", true), NamingEffect.Close), effects)
        val undo = SpeakerUndo.of(session, effects) { (it as SpeakerNameUi.Named).text }
        assertEquals(SpeakerUndo.Summary(SnackbarKind.BACKGROUND_VOICE, "Silvio", 1), undo.summary)
    }

    @Test fun theBackgroundSwitchDisablesMergeAndHidesNothingWhileOff() {
        val session = open("s3").on(NamingEvent.BackgroundToggled(true), NamingEvent.MergeInto)
        assertNull(session.form.merge)
        assertNull(SpeakerNaming.mergeUi(session))
    }

    @Test fun switchingBackOffNeedsNoName() {
        val session = open("s4", background = true)
        assertTrue(session.form.background)
        assertEquals(SaveState.DISABLED, SpeakerNaming.ui(session).save)
        val off = session.on(NamingEvent.BackgroundToggled(false))
        assertEquals(SaveState.ENABLED, SpeakerNaming.ui(off).save)
        val effects = SpeakerNaming.reduce(off, NamingEvent.Save).effects
        assertEquals(listOf(NamingEffect.Background("s4", false), NamingEffect.Close), effects)
        // Switching a voice back on the conversation is no "background voice" news: no snackbar.
        assertNull(SpeakerUndo.of(off, effects) { "x" }.summary)
    }

    @Test fun severalBackgroundVoicesAloneSumUpAsACount() {
        val session = open("s4").on(NamingEvent.BackgroundToggled(true))
            .select("s5").on(NamingEvent.BackgroundToggled(true))
            .select("s6").on(NamingEvent.BackgroundToggled(true))
            .select("s7").on(NamingEvent.BackgroundToggled(true))
        val effects = SpeakerNaming.reduce(session, NamingEvent.Save).effects
        val undo = SpeakerUndo.of(session, effects) { "x" }
        assertEquals(SpeakerUndo.Summary(SnackbarKind.BACKGROUND_VOICES, null, 4), undo.summary)
    }

    @Test fun anOpenQuestionInAnotherFormIsShownOnSave() {
        // "Anna" matches the saved voice Anna Ricci by first word: the same-person question opens.
        val session = open("s3").on(NamingEvent.NameChanged("Anna"))
            .select("s5").on(NamingEvent.BackgroundToggled(true))
        assertEquals(SaveState.ENABLED, SpeakerNaming.ui(session).save)
        val result = SpeakerNaming.reduce(session, NamingEvent.Save)
        assertEquals("s3", result.session?.active)
        assertEquals(listOf(NamingEffect.FocusQuestion), result.effects)
        assertEquals(SaveState.BLOCKED_BY_QUESTION, SpeakerNaming.ui(result.session!!).save)
    }

    @Test fun cancelDropsEveryChange() {
        val session = open("s3").on(NamingEvent.NameChanged("Marta"))
            .select("s5").on(NamingEvent.BackgroundToggled(true))
        assertEquals(listOf(NamingEffect.Close), SpeakerNaming.reduce(session, NamingEvent.Cancel).effects)
    }

    @Test fun aDraftHasNoBackgroundSwitch() {
        val draft = NamingSession.of(SpeakerNaming.open(input("s3", draft = true)), chips)
        val toggled = SpeakerNaming.reduce(draft, NamingEvent.BackgroundToggled(true)).session!!
        assertFalse(toggled.form.background)
        assertFalse(SpeakerNaming.ui(toggled).canBackground)
    }

    @Test fun undoTakesBackAnEnrolmentAndRestoresTheEarlierMatch() {
        val earlier = Match("anna", 0.4f, Match.State.SUGGESTED)
        val form = SpeakerNaming.open(input("s3", match = earlier))
        val session = NamingSession.of(form, chips)
            .on(NamingEvent.NameChanged("Marta"), NamingEvent.RememberToggled(true))
            .select("s5").on(NamingEvent.BackgroundToggled(true))
        val effects = SpeakerNaming.reduce(session, NamingEvent.Save).effects
        assertTrue(effects.any { it is NamingEffect.RememberVoice && it.speakerId == "s3" })
        val recorder = SpeakerUndo.Recorder(session)
        recorder.onEnrolled("s3")
        val undo = SpeakerUndo.of(session, effects) { "x" }.copy(voiceOps = recorder.ops())
        assertEquals(listOf(VoiceUndoOp.Unenrol("s3", earlier)), undo.voiceOps)
    }

    @Test fun visitingAnUnchangedNamedSpeakerDoesNotEnrolItWhenSavingAnotherSpeaker() {
        // s1 is already named "Silvio" (unconfirmed, with a print); visiting s1 and marking s5 background must only
        // save s5, whether Save is pressed on s5 or after switching back to s1.
        val onBackground = open("s1")
            .select("s5").on(NamingEvent.BackgroundToggled(true))
        assertEquals(
            listOf(NamingEffect.Background("s5", true), NamingEffect.Close),
            SpeakerNaming.reduce(onBackground, NamingEvent.Save).effects,
        )
        val backOnNamed = onBackground.select("s1")
        assertEquals(
            listOf(NamingEffect.Background("s5", true), NamingEffect.Close),
            SpeakerNaming.reduce(backOnNamed, NamingEvent.Save).effects,
        )
    }

    @Test fun visitingAnUnchangedAutoMatchedSpeakerDoesNotConfirmItWhenSavingAnotherSpeaker() {
        val autoForm = SpeakerNaming.open(
            input("s1", label = SpeakerNameUi.Named("Anna Ricci"), match = Match("anna", 0.8f, Match.State.AUTO)),
        )
        val visitedPlaceholder = NamingSession.of(autoForm, chips).select("s5")
        assertEquals(SaveState.DISABLED, SpeakerNaming.ui(visitedPlaceholder).save)

        val markedBackground = visitedPlaceholder.on(NamingEvent.BackgroundToggled(true))
        assertEquals(
            listOf(NamingEffect.Background("s5", true), NamingEffect.Close),
            SpeakerNaming.reduce(markedBackground, NamingEvent.Save).effects,
        )
        val backOnAuto = markedBackground.select("s1")
        assertEquals(
            listOf(NamingEffect.Background("s5", true), NamingEffect.Close),
            SpeakerNaming.reduce(backOnAuto, NamingEvent.Save).effects,
        )

        // Saving directly on the AUTO-matched speaker with no other edits still confirms it.
        val direct = NamingSession.of(autoForm, chips)
        assertEquals(SaveState.ENABLED, SpeakerNaming.ui(direct).save)
        assertEquals(
            listOf(NamingEffect.RememberVoice("s1", "anna", "Anna Ricci"), NamingEffect.Close),
            SpeakerNaming.reduce(direct, NamingEvent.Save).effects,
        )
    }

    @Test fun switchingBackgroundOffForAnUnchangedNamedSpeakerDoesNotEnrolIt() {
        val session = open("s1", background = true).on(NamingEvent.BackgroundToggled(false))
        assertEquals(SaveState.ENABLED, SpeakerNaming.ui(session).save)
        assertEquals(
            listOf(NamingEffect.Background("s1", false), NamingEffect.Close),
            SpeakerNaming.reduce(session, NamingEvent.Save).effects,
        )
    }
}
