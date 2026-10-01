package org.cassini.android

import org.junit.Assert.*
import org.junit.Test

class AudioDecoderTest {
    @Test fun truncatedFrameTimesStayContiguous() {
        for ((rate, frame) in listOf(48000 to 1024, 44100 to 1024, 44100 to 1152, 16000 to 1024, 48000 to 960)) {
            var next: Int? = null
            repeat(2000) { index ->
                val start = index.toLong() * frame
                // Extractors and codecs report whole microseconds, truncated.
                assertEquals("$rate Hz frame $index", start.toInt(), AudioDecoder.framePosition(start * 1_000_000 / rate, rate, next))
                next = start.toInt() + frame
            }
        }
    }

    @Test fun realContainerGapsAndOverlapsKeepTheirClock() {
        assertEquals(96000, AudioDecoder.framePosition(2_000_000, 48000, 48000))
        assertEquals(24000, AudioDecoder.framePosition(500_000, 48000, 48000))
        assertEquals(0, AudioDecoder.framePosition(-20_000, 48000, null))
        // A first buffer has nothing to continue, so even a sub-millisecond start offset is kept.
        assertEquals(43, AudioDecoder.framePosition(900, 48000, null))
        assertEquals(0, AudioDecoder.framePosition(900, 48000, 0))
        // Timestamp jitter below a millisecond is not a gap.
        assertEquals(48000, AudioDecoder.framePosition(1_000_900, 48000, 48000))
        assertEquals(48096, AudioDecoder.framePosition(1_002_000, 48000, 48000))
    }
}
