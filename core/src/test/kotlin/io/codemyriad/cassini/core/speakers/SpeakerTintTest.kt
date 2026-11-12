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

    @Test fun placeholdersAreStoneAndTakeNoNamedSlot() {
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
                "s3" to SpeakerTint.TWO,
                "s4" to SpeakerTint.UNNAMED,
                "s5" to SpeakerTint.THREE,
            ),
            tints,
        )
    }

    @Test fun aSpeakerWithoutALabelIsAPlaceholder() {
        val tints = SpeakerTint.assign(listOf("s1", "s2"), mapOf("s2" to "Luca"))
        assertEquals(SpeakerTint.UNNAMED, tints["s1"])
        assertEquals(SpeakerTint.ONE, tints["s2"])
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
}
