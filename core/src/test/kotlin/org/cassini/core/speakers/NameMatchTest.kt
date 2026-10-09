package org.cassini.core.speakers

import org.cassini.core.models.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class NameMatchTest {
    private val pinned = ModelCatalog.voiceprintModel.sha256
    private val dim = ModelCatalog.VOICEPRINT_DIM
    private val suggest = Voiceprints.THRESHOLDS.getValue(pinned).suggest

    private fun axis(i: Int) = FloatArray(dim).also { it[i] = 1f }

    /** A unit vector at cosine [score] from axis 0, leaning toward axis [other]. */
    private fun near(score: Float, other: Int) = FloatArray(dim).also {
        it[0] = score
        it[other] = sqrt(1 - score * score)
    }

    private fun voice(id: String, name: String, mean: FloatArray = axis(0), model: String = pinned) =
        Voice(id, name, model, mean, 1, 10.0, 0, 0)

    @Test fun normaliseStripsAccentsCaseAndSpaces() {
        assertEquals("elodie", NameMatch.normalise("Élodie"))
        assertEquals("elodie", NameMatch.normalise("  ELODIE "))
        // A precomposed and a decomposed "é" are the same name.
        assertEquals(NameMatch.normalise("José"), NameMatch.normalise("José"))
        assertEquals("anna ricci", NameMatch.normalise(" Anna \t Ricci "))
        assertEquals("niccolo", NameMatch.normalise("Niccolò"))
        assertEquals("strasse", NameMatch.normalise("Straße"))
        assertEquals("", NameMatch.normalise("   "))
    }

    @Test fun anAccentedNameMatchesItsPlainForm() {
        val elodie = voice("e", "Élodie")
        assertEquals(listOf(elodie), NameMatch.samePersonCandidates("elodie", listOf(elodie)))
        assertEquals(listOf(elodie), NameMatch.samePersonCandidates(" ÉLODIE", listOf(elodie)))
        val plain = voice("p", "Elodie")
        assertEquals(listOf(plain), NameMatch.samePersonCandidates("Élodie", listOf(plain)))
    }

    @Test fun aFirstNameMatchesTheFullName() {
        val anna = voice("a", "Anna Ricci")
        assertEquals(listOf(anna), NameMatch.samePersonCandidates("Anna", listOf(anna)))
        assertEquals(listOf(anna), NameMatch.samePersonCandidates("anna ricci", listOf(anna)))
    }

    @Test fun aFullNameDoesNotMatchAShorterSavedName() {
        val anna = voice("a", "Anna")
        assertEquals(emptyList<Voice>(), NameMatch.samePersonCandidates("Anna Ricci", listOf(anna)))
        assertEquals(listOf(anna), NameMatch.samePersonCandidates("Anna", listOf(anna)))
    }

    @Test fun aPartOfAWordOrALaterWordIsNoMatch() {
        val voices = listOf(voice("a", "Annalisa"), voice("b", "Maria Anna"), voice("c", "Ricci Anna"))
        assertEquals(emptyList<Voice>(), NameMatch.samePersonCandidates("Anna", voices))
        assertEquals(emptyList<Voice>(), NameMatch.samePersonCandidates("  ", voices))
    }

    @Test fun theHighestScoreIsPicked() {
        val ricci = voice("r", "Anna Ricci")
        val bianchi = voice("b", "Anna Bianchi")
        val scores = mapOf("r" to 0.61f, "b" to 0.40f)
        assertEquals(ricci, NameMatch.samePerson("Anna", listOf(bianchi, ricci), scores))
    }

    @Test fun aTieIsPickedAlphabetically() {
        val ricci = voice("r", "Anna Ricci")
        val bianchi = voice("b", "Anna Bianchi")
        assertEquals(bianchi, NameMatch.samePerson("Anna", listOf(ricci, bianchi), mapOf("r" to 0.5f, "b" to 0.5f)))
        // Without scores (no print) everything ties.
        assertEquals(bianchi, NameMatch.samePerson("Anna", listOf(ricci, bianchi), emptyMap()))
        // An accent does not move a name in the order: "Élodie" sorts as "elodie".
        val elodie = voice("e", "Élodie Martin")
        val emma = voice("m", "Elodie Zanetti")
        assertEquals(elodie, NameMatch.samePerson("elodie", listOf(emma, elodie), emptyMap()))
    }

    @Test fun aScoredCandidateBeatsAnUnscoredOne() {
        val ricci = voice("r", "Anna Ricci")
        val bianchi = voice("b", "Anna Bianchi")
        assertEquals(ricci, NameMatch.samePerson("Anna", listOf(bianchi, ricci), mapOf("r" to 0.1f)))
    }

    @Test fun noCandidateNoPick() {
        assertNull(NameMatch.samePerson("Luca", listOf(voice("a", "Anna")), emptyMap()))
    }

    @Test fun rankByScoreThenNameWithStaleVoicesLast() {
        val voices = listOf(
            voice("old", "Zeno", model = "0".repeat(64)),
            voice("low", "Bruno", near(0.30f, 2)),
            voice("top", "Paolo", near(0.70f, 1)),
            voice("tie2", "Carla", near(0.30f, 3)),
            voice("old2", "Aldo", FloatArray(192).also { it[0] = 1f }),
        )
        val rows = NameMatch.rank(axis(0), voices, suggest)
        assertEquals(listOf("top", "low", "tie2", "old2", "old"), rows.map { it.voice.id })
        assertEquals(listOf(false, false, false, true, true), rows.map { it.stale })
        assertEquals(0.70f, rows[0].score!!, 1e-5f)
        assertNull(rows[3].score)
        assertNull(rows[4].score)
        assertEquals(listOf(true, false, false, false, false), rows.map { it.likely })
    }

    @Test fun likelyNeedsTheSuggestThreshold() {
        val at = NameMatch.rank(axis(0), listOf(voice("a", "Anna", near(0.50f, 1))), 0.50f)
        assertTrue(at.single().likely)
        val below = NameMatch.rank(axis(0), listOf(voice("a", "Anna", near(0.49f, 1))), 0.50f)
        assertFalse(below.single().likely)
    }

    @Test fun atMostOneRowIsLikely() {
        val voices = listOf(voice("a", "Anna", near(0.90f, 1)), voice("b", "Bruno", near(0.80f, 2)))
        assertEquals(1, NameMatch.rank(axis(0), voices, suggest).count { it.likely })
    }

    @Test fun withoutAPrintTheListIsAlphabeticalAndNothingIsLikely() {
        val voices = listOf(
            voice("p", "paolo"),
            voice("s", "Zeno", model = "x"),
            voice("e", "Élodie"),
            voice("a", "Anna"),
        )
        val rows = NameMatch.rank(null, voices, suggest)
        assertEquals(listOf("a", "e", "p", "s"), rows.map { it.voice.id })
        assertTrue(rows.all { it.score == null && !it.likely })
    }

    @Test fun noVoicesNoRows() {
        assertEquals(emptyList<NameMatch.Ranked>(), NameMatch.rank(axis(0), emptyList(), suggest))
    }
}
