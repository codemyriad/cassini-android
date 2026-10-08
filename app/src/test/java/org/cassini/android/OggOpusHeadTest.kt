package org.cassini.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OggOpusHeadTest {
    private fun page(magic: String) = ByteArray(OggOpus.HEAD_BYTES).also {
        "OggS".toByteArray().copyInto(it); it[26] = 1; magic.toByteArray().copyInto(it, 28)
    }
    @Test fun recognisesOpusFirstPage() = assertTrue(OggOpus.looksLikeOpus(page("OpusHead")))
    @Test fun rejectsVorbisWavAndShortInput() {
        assertFalse(OggOpus.looksLikeOpus(page("\u0001vorbis\u0000")))
        assertFalse(OggOpus.looksLikeOpus("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray().copyOf(OggOpus.HEAD_BYTES)))
        assertFalse(OggOpus.looksLikeOpus(page("OpusHead").copyOf(20)))
    }
}
