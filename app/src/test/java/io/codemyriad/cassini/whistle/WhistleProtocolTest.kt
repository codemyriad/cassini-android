package io.codemyriad.cassini.whistle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhistleProtocolTest {
    @Test
    fun ownsOnlyItsExactProcessOrIsolatedComponentSuffix() {
        val app = "io.codemyriad.cassini"
        assertTrue(WhistleProtocol.ownsProcess(app, "$app:whistle"))
        assertTrue(WhistleProtocol.ownsProcess(app, "$app:whistle:WhistleService"))
        for (process in listOf(app, "$app:other", "$app:whistlex", "other:whistle")) {
            assertFalse(WhistleProtocol.ownsProcess(app, process))
        }
    }
}
