package io.codemyriad.cassini.core.speakers

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerTintTest {
    @Test fun namedSpeakersCycleThroughSixSlotsInFirstTurnOrder() {
        val order = listOf("a", "b", "c", "d", "e", "f", "g")
        val tints = SpeakerTint.assign(order) { false }
        assertEquals(
            listOf(
                SpeakerTint.ONE,
                SpeakerTint.TWO,
                SpeakerTint.THREE,
                SpeakerTint.FOUR,
                SpeakerTint.FIVE,
                SpeakerTint.SIX,
                SpeakerTint.ONE,
            ),
            order.map { tints.getValue(it) },
        )
    }

    @Test fun placeholdersHideTheirFirstSpeechSlotUntilNamed() {
        val labels = mapOf(
            "s1" to "Giulia",
            "s2" to "Speaker 2",
            "s3" to "Paolo Ferri",
            "s4" to "Voce 4",
            "s5" to "Anna",
        )
        val tints = SpeakerTint.assign(listOf("s1", "s2", "s3", "s4", "s5"), labels)
        assertEquals(
            mapOf(
                "s1" to SpeakerTint.ONE,
                "s2" to SpeakerTint.UNNAMED,
                "s3" to SpeakerTint.THREE,
                "s4" to SpeakerTint.UNNAMED,
                "s5" to SpeakerTint.FIVE,
            ),
            tints,
        )
    }

    @Test fun aSpeakerWithoutALabelIsAPlaceholder() {
        val tints = SpeakerTint.assign(listOf("s1", "s2"), mapOf("s2" to "Luca"))
        assertEquals(SpeakerTint.UNNAMED, tints["s1"])
        assertEquals(SpeakerTint.TWO, tints["s2"])
    }

    @Test fun theOrderIsTheGivenFirstTurnOrder() {
        val labels = mapOf("a" to "Anna", "b" to "Bruno")
        assertEquals(SpeakerTint.ONE, SpeakerTint.assign(listOf("b", "a"), labels)["b"])
        assertEquals(SpeakerTint.TWO, SpeakerTint.assign(listOf("b", "a"), labels)["a"])
    }

    @Test fun theCycleWraps() {
        assertEquals(SpeakerTint.ONE, SpeakerTint.named(0))
        assertEquals(SpeakerTint.THREE, SpeakerTint.named(2))
        assertEquals(SpeakerTint.SIX, SpeakerTint.named(5))
        assertEquals(SpeakerTint.ONE, SpeakerTint.named(6))
    }

    @Test fun namingAndReorderingRevealStoredSlotsWithoutReassigningOtherSpeakers() {
        val slots = mapOf("a" to 0, "b" to 1, "c" to 2, "g" to 6)
        val before = SpeakerTint.assign(listOf("a", "b", "c", "g"), slots) { it == "b" }
        val after = SpeakerTint.assign(listOf("g", "c", "b", "a"), slots) { false }
        assertEquals(SpeakerTint.UNNAMED, before["b"])
        assertEquals(SpeakerTint.TWO, after["b"])
        for (id in listOf("a", "c", "g")) assertEquals(before[id], after[id])
        assertEquals(SpeakerTint.ONE, after["g"])
    }
}
