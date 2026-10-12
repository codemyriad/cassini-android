package org.cassini.core.speakers

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerTintTest {
    @Test fun namedSpeakersCycleBlueTealViolet() {
        val order = listOf("a", "b", "c", "d", "e")
        val tints = SpeakerTint.assign(order) { false }
        assertEquals(
            listOf(SpeakerTint.BLUE, SpeakerTint.TEAL, SpeakerTint.VIOLET, SpeakerTint.BLUE, SpeakerTint.TEAL),
            order.map { tints.getValue(it) },
        )
    }

    @Test fun placeholdersAreAmberAndTakeNoSlot() {
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
                "s1" to SpeakerTint.BLUE,
                "s2" to SpeakerTint.AMBER,
                "s3" to SpeakerTint.TEAL,
                "s4" to SpeakerTint.AMBER,
                "s5" to SpeakerTint.VIOLET,
            ),
            tints,
        )
    }

    @Test fun aSpeakerWithoutALabelIsAPlaceholder() {
        val tints = SpeakerTint.assign(listOf("s1", "s2"), mapOf("s2" to "Luca"))
        assertEquals(SpeakerTint.AMBER, tints["s1"])
        assertEquals(SpeakerTint.BLUE, tints["s2"])
    }

    @Test fun theOrderIsTheGivenFirstTurnOrder() {
        val labels = mapOf("a" to "Anna", "b" to "Bruno")
        assertEquals(SpeakerTint.BLUE, SpeakerTint.assign(listOf("b", "a"), labels)["b"])
        assertEquals(SpeakerTint.TEAL, SpeakerTint.assign(listOf("b", "a"), labels)["a"])
    }

    @Test fun theCycleWraps() {
        assertEquals(SpeakerTint.BLUE, SpeakerTint.named(0))
        assertEquals(SpeakerTint.VIOLET, SpeakerTint.named(2))
        assertEquals(SpeakerTint.BLUE, SpeakerTint.named(3))
    }
}
