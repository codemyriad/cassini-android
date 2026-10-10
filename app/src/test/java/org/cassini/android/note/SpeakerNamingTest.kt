package org.cassini.android.note

import org.cassini.core.models.ModelCatalog
import org.cassini.core.speakers.Match
import org.cassini.core.speakers.SampleWindow
import org.cassini.core.speakers.Voice
import org.cassini.ui.components.SpeakerNameUi
import org.cassini.ui.note.MergeTargetUi
import org.cassini.ui.note.MergeVersionsUi
import org.cassini.ui.note.RememberReason
import org.cassini.ui.note.SaveState
import org.cassini.ui.theme.SpeakerTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Name a speaker reducer (features.md section 4): every question path, the Save rule, "Not X" and merges. */
class SpeakerNamingTest {
    private val anna = voice("anna", "Anna Ricci", axis = 0)
    private val luca = voice("luca", "Luca Bianchi", axis = 1)
    private val elodie = voice("elodie", "Élodie Martin", axis = 2)

    /** A print close to [anna] (cosine 1 against her, 0 against the others). */
    private val annaPrint = FloatArray(ModelCatalog.VOICEPRINT_DIM).also { it[0] = 1f }

    private val paolo = listOf(MergeTargetUi("s1", SpeakerNameUi.Named("Paolo"), SpeakerTone.Blue, 9_000))

    @Suppress("LongParameterList") // one default per test variation
    private fun input(
        label: SpeakerNameUi = SpeakerNameUi.Placeholder(3),
        match: Match? = null,
        print: FloatArray? = annaPrint,
        seconds: Double = 12.0,
        draft: Boolean = false,
        voices: List<Voice> = listOf(anna, luca, elodie),
        modelReady: Boolean = true,
        targets: List<MergeTargetUi> = paolo,
    ) = NamingInput(
        speakerId = "s3",
        label = label,
        tone = SpeakerTone.Amber,
        speakingMs = 192_000,
        sample = SampleWindow(10_000, 20_000),
        draft = draft,
        print = if (draft) null else print,
        printSeconds = seconds,
        willHavePrint = draft && modelReady,
        match = match,
        voices = voices,
        voiceModelReady = modelReady,
        mergeTargets = targets,
        sealedVersions = MergeVersionsUi(shown = 1, added = 2),
    )

    private fun NamingState.on(vararg events: NamingEvent): NamingState =
        events.fold(this) { state, event -> SpeakerNaming.reduce(state, event).state ?: error("closed on $event") }

    private fun NamingState.effects(event: NamingEvent): List<NamingEffect> = SpeakerNaming.reduce(this, event).effects

    @Test
    fun aPlaceholderOpensEmptyWithRememberOnAndNothingToSave() {
        val state = SpeakerNaming.open(input())
        val ui = SpeakerNaming.ui(state)
        assertEquals("", ui.name)
        assertTrue(ui.remember)
        assertTrue(ui.rememberEnabled)
        assertEquals(SaveState.DISABLED, ui.save)
        assertTrue(ui.ranked)
        assertEquals(listOf("anna", "elodie", "luca"), ui.voices.map { it.id })
        assertTrue("the top voice scores 1.0 ≥ 0.50", ui.voices.first().likely)
        assertFalse(ui.voices[1].likely)
    }

    @Test
    fun aTypedFirstNameAsksAboutTheSamePersonAndBlocksSave() {
        val state = SpeakerNaming.open(input()).on(NamingEvent.NameChanged("anna"))
        val ui = SpeakerNaming.ui(state)
        assertEquals("Anna Ricci", ui.samePerson)
        assertEquals(SaveState.BLOCKED_BY_QUESTION, ui.save)
        val result = SpeakerNaming.reduce(state, NamingEvent.Save)
        assertEquals("Save stays open and moves focus to the question", state, result.state)
        assertEquals(listOf(NamingEffect.FocusQuestion), result.effects)
    }

    @Test
    fun accentsAndCaseDoNotMatterForTheSamePersonQuestion() {
        val state = SpeakerNaming.open(input()).on(NamingEvent.NameChanged("  ELODIE "))
        assertEquals("Élodie Martin", SpeakerNaming.ui(state).samePerson)
    }

    @Test
    fun samePersonBindsTheVoiceAndSaveEnrolsIntoIt() {
        val state = SpeakerNaming.open(input()).on(NamingEvent.NameChanged("Anna"), NamingEvent.SamePerson)
        val ui = SpeakerNaming.ui(state)
        assertEquals("Anna Ricci", ui.name)
        assertNull(ui.samePerson)
        assertEquals(SaveState.ENABLED, ui.save)
        assertEquals(
            listOf(
                NamingEffect.Relabel("s3", "Anna Ricci"),
                NamingEffect.RememberVoice("s3", "anna", "Anna Ricci"),
                NamingEffect.Close,
            ),
            state.effects(NamingEvent.Save),
        )
    }

    @Test
    fun someoneElseKeepsTheTypedNameAndMakesANewVoice() {
        val state = SpeakerNaming.open(input()).on(NamingEvent.NameChanged("Anna"), NamingEvent.SomeoneElse)
        assertNull(SpeakerNaming.ui(state).samePerson)
        assertEquals(SaveState.ENABLED, SpeakerNaming.ui(state).save)
        assertEquals(
            listOf(
                NamingEffect.Relabel("s3", "Anna"),
                NamingEffect.RememberVoice("s3", null, "Anna"),
                NamingEffect.Close,
            ),
            state.effects(NamingEvent.Save),
        )
    }

    @Test
    fun withRememberOffThereIsNoQuestionAndOnlyTheLabelIsWritten() {
        val state = SpeakerNaming.open(input()).on(NamingEvent.RememberToggled(false), NamingEvent.NameChanged("Anna"))
        assertNull(SpeakerNaming.ui(state).samePerson)
        assertEquals(listOf(NamingEffect.Relabel("s3", "Anna"), NamingEffect.Close), state.effects(NamingEvent.Save))
    }

    @Test
    fun withoutAPrintTheListIsByNameAndThereIsNoQuestion() {
        val state = SpeakerNaming.open(input(print = null)).on(NamingEvent.NameChanged("Anna"))
        val ui = SpeakerNaming.ui(state)
        assertFalse(ui.ranked)
        assertEquals(listOf("anna", "elodie", "luca"), ui.voices.map { it.id })
        assertTrue(ui.voices.none { it.likely })
        assertNull(ui.samePerson)
        assertFalse(ui.rememberEnabled)
        assertEquals(RememberReason.TOO_LITTLE_SPEECH, ui.rememberReason)
    }

    @Test
    fun rememberIsDisabledWithTooLittleSpeechOrWithoutTheModel() {
        val short = SpeakerNaming.ui(SpeakerNaming.open(input(seconds = 2.0)))
        assertEquals(RememberReason.TOO_LITTLE_SPEECH, short.rememberReason)
        val noModel = SpeakerNaming.ui(SpeakerNaming.open(input(modelReady = false)))
        assertEquals(RememberReason.NO_MODEL, noModel.rememberReason)
        assertFalse(noModel.remember)
    }

    @Test
    fun pickingAVoiceBindsItAndEditingTheNameAwayUnbindsIt() {
        val picked = SpeakerNaming.open(input()).on(NamingEvent.PickVoice("luca"))
        assertEquals("Luca Bianchi", picked.name)
        assertEquals("luca", picked.boundVoiceId)
        val edited = picked.on(NamingEvent.NameChanged("Luca B."))
        assertNull(edited.boundVoiceId)
    }

    @Test
    fun showAllListsEveryVoiceAfterTheFirstThree() {
        val many = listOf(anna, luca, elodie, voice("m", "Marco", axis = 3), voice("g", "Giulia", axis = 4))
        val state = SpeakerNaming.open(input(voices = many))
        assertEquals(3, SpeakerNaming.ui(state).voices.size)
        assertTrue(SpeakerNaming.ui(state).showAll)
        val all = SpeakerNaming.ui(state.on(NamingEvent.ShowAll))
        assertEquals(5, all.voices.size)
        assertFalse(all.showAll)
    }

    @Test
    fun aConfirmedVoiceIsBoundAtOpenAndUnchangedMeansNothingToSave() {
        val state = SpeakerNaming.open(
            input(label = SpeakerNameUi.Named("Anna Ricci"), match = Match("anna", 0.8f, Match.State.CONFIRMED)),
        )
        assertEquals("anna", state.boundVoiceId)
        assertEquals(SaveState.DISABLED, SpeakerNaming.ui(state).save)
        assertEquals("Anna Ricci", SpeakerNaming.ui(state).notName)
    }

    @Test
    fun renamingAConfirmedSpeakerAsksToRenameTheSavedVoice() {
        val confirmed = input(
            label = SpeakerNameUi.Named("Anna Ricci"),
            match = Match("anna", 0.8f, Match.State.CONFIRMED),
        )
        val typed = SpeakerNaming.open(confirmed).on(NamingEvent.NameChanged("Annette"))
        assertEquals("Anna Ricci", SpeakerNaming.ui(typed).renameSaved)
        assertEquals(SaveState.BLOCKED_BY_QUESTION, SpeakerNaming.ui(typed).save)

        val renamed = typed.on(NamingEvent.RenameSaved)
        assertNull(SpeakerNaming.ui(renamed).renameSaved)
        assertEquals(
            listOf(
                NamingEffect.Relabel("s3", "Annette"),
                NamingEffect.RenameVoice("anna", "Annette"),
                NamingEffect.Close,
            ),
            renamed.effects(NamingEvent.Save),
        )

        val someoneElse = typed.on(NamingEvent.KeepSaved)
        assertEquals(
            listOf(
                NamingEffect.Relabel("s3", "Annette"),
                NamingEffect.Unenrol("s3"),
                NamingEffect.RememberVoice("s3", null, "Annette"),
                NamingEffect.Close,
            ),
            someoneElse.effects(NamingEvent.Save),
        )
    }

    @Test
    fun anAutoMatchIsConfirmedBySaveAndClearedWhenTheNameChangesWithoutRemember() {
        val auto = input(label = SpeakerNameUi.Named("Luca Bianchi"), match = Match("luca", 0.75f, Match.State.AUTO))
        val state = SpeakerNaming.open(auto)
        assertEquals("Save is enabled: the match is only AUTO", SaveState.ENABLED, SpeakerNaming.ui(state).save)
        assertEquals(
            listOf(NamingEffect.RememberVoice("s3", "luca", "Luca Bianchi"), NamingEffect.Close),
            state.effects(NamingEvent.Save),
        )
        val renamed = state.on(NamingEvent.RememberToggled(false), NamingEvent.NameChanged("Marco"))
        assertEquals(
            listOf(NamingEffect.Relabel("s3", "Marco"), NamingEffect.ClearMatch("s3"), NamingEffect.Close),
            renamed.effects(NamingEvent.Save),
        )
    }

    @Test
    fun notThisPersonRejectsAndReturnsTheLabelToThePlaceholder() {
        val auto = SpeakerNaming.open(
            input(label = SpeakerNameUi.Named("Luca Bianchi"), match = Match("luca", 0.75f, Match.State.AUTO)),
        )
        val result = SpeakerNaming.reduce(auto, NamingEvent.NotThisPerson)
        assertNull(result.state)
        assertEquals(
            listOf(
                NamingEffect.Reject(
                    "s3",
                    "luca",
                    "Luca Bianchi",
                    unenrol = false,
                    restorePlaceholder = true,
                    draft = false,
                ),
                NamingEffect.Close,
            ),
            result.effects,
        )
        val confirmed = SpeakerNaming.open(
            input(label = SpeakerNameUi.Named("Anna"), match = Match("anna", 0.8f, Match.State.CONFIRMED)),
        )
        val reject = SpeakerNaming.reduce(confirmed, NamingEvent.NotThisPerson).effects.first() as NamingEffect.Reject
        assertTrue("a CONFIRMED match is unenrolled first", reject.unenrol)
        assertFalse("the label is not the voice's name", reject.restorePlaceholder)
    }

    @Test
    fun noMatchMeansNoNotThisPersonButton() {
        val state = SpeakerNaming.open(input())
        assertNull(SpeakerNaming.ui(state).notName)
        assertEquals(state, SpeakerNaming.reduce(state, NamingEvent.NotThisPerson).state)
    }

    @Test
    fun mergeAsksForConfirmationAndAddsAVersionOnASealedNote() {
        val open = SpeakerNaming.open(input())
        assertTrue(SpeakerNaming.ui(open).canMerge)
        val list = open.on(NamingEvent.MergeInto)
        val ui = SpeakerNaming.mergeUi(list)!!
        assertNull(ui.confirm)
        assertEquals(MergeVersionsUi(shown = 1, added = 2), ui.sealed)
        val confirm = list.on(NamingEvent.MergeTarget("s1"))
        assertEquals("s1", SpeakerNaming.mergeUi(confirm)!!.confirm?.speakerId)
        assertNull(SpeakerNaming.mergeUi(confirm.on(NamingEvent.CancelMerge))!!.confirm)
        assertEquals(
            listOf(NamingEffect.MergeSealed("s3", "s1"), NamingEffect.Close),
            confirm.effects(NamingEvent.ConfirmMerge),
        )
    }

    @Test
    fun aDraftMergeChangesTheDraftWithTheTargetsName() {
        val confirm = SpeakerNaming.open(input(draft = true)).on(NamingEvent.MergeInto, NamingEvent.MergeTarget("s1"))
        assertNull("This changes the draft.", SpeakerNaming.mergeUi(confirm)!!.sealed)
        assertEquals(
            listOf(NamingEffect.MergeDraft("s3", "s1", "Paolo"), NamingEffect.Close),
            confirm.effects(NamingEvent.ConfirmMerge),
        )
    }

    @Test
    fun aDraftSaveRenamesAndRemembersANewOrBoundVoice() {
        val draft = SpeakerNaming.open(input(draft = true))
        assertTrue(SpeakerNaming.ui(draft).draft)
        val typed = draft.on(NamingEvent.NameChanged("Giorgio"))
        assertEquals(
            listOf(
                NamingEffect.DraftName("s3", "Giorgio", voiceId = "", confirm = false, score = 1f),
                NamingEffect.Close,
            ),
            typed.effects(NamingEvent.Save),
        )
        val bound = draft.on(NamingEvent.PickVoice("luca"))
        val effect = bound.effects(NamingEvent.Save).first() as NamingEffect.DraftName
        assertEquals("luca", effect.voiceId)
        assertTrue(effect.confirm)
        val forget = draft.on(NamingEvent.RememberToggled(false), NamingEvent.NameChanged("Giorgio"))
        assertNull((forget.effects(NamingEvent.Save).first() as NamingEffect.DraftName).voiceId)
    }

    @Test
    fun theSamplePlaysOnlyWithAPlayer() {
        val state = SpeakerNaming.open(input())
        assertEquals(listOf(NamingEffect.PlayRange(10_000, 20_000)), state.effects(NamingEvent.PlaySample))
        val noPlayer = SpeakerNaming.open(input().copy(canSample = false))
        assertTrue(noPlayer.effects(NamingEvent.PlaySample).isEmpty())
        assertFalse(SpeakerNaming.ui(noPlayer).canSample)
    }

    @Test
    fun cancelCloses() {
        val result = SpeakerNaming.reduce(SpeakerNaming.open(input()), NamingEvent.Cancel)
        assertNull(result.state)
        assertEquals(listOf(NamingEffect.Close), result.effects)
    }

    @Test
    fun theNameIsCutAt80Characters() {
        val state = SpeakerNaming.open(input()).on(NamingEvent.NameChanged("x".repeat(100)))
        assertEquals(SpeakerNaming.MAX_NAME, state.name.length)
    }

    private fun voice(id: String, name: String, axis: Int) = Voice(
        id = id,
        name = name,
        model = ModelCatalog.voiceprintModel.sha256,
        mean = FloatArray(ModelCatalog.VOICEPRINT_DIM).also { it[axis] = 1f },
        count = 1,
        seconds = 30.0,
        createdAt = 0,
        updatedAt = 0,
    )
}
