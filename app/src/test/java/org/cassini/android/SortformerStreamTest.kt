package org.cassini.android

import org.cassini.core.audio.PcmAudio
import org.junit.Assert.fail
import org.junit.Test

class SortformerStreamTest {
    @Test fun invalidNativeConfigurationIsRejectedBeforeLoadingModels() {
        for (threads in listOf(0, -1)) {
            try {
                SortformerStream.turns(PcmAudio(floatArrayOf(0f), 16000), "", threads)
                fail()
            } catch (_: IllegalArgumentException) {}
        }
    }
}
