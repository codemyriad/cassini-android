package org.cassini.android

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VoiceClipCacheTest {
    @Test fun longMeetingsKeepOnlyTheBoundedSpeakerSampleBudget() {
        val file = File.createTempFile("voice-cache", ".pcm")
        PcmFile(file).use { source ->
            val cache = VoiceClipCache(source)
            val chunk = PcmAudio(FloatArray(28 * Limits.ASR_RATE) { 0.1f }, Limits.ASR_RATE)
            // 26 minutes of one voice cannot create a 26-minute PCM cache.
            repeat(60) { index ->
                cache.accept(chunk, listOf(SpeakerTurn(0, 28000, 0)),
                    listOf(Word("diar_test_1", 0, 28000, "hello")), index * 26000L)
            }
            assertTrue(source.durationMs <= 45_000)
            assertTrue(source.durationMs >= 43_000)
            assertTrue(file.length() <= 45 * 16000 * 4L)
            assertEquals(setOf("diar_test_1"), cache.windows.keys)
            assertTrue(cache.windows.values.flatten().all { it.endSample - it.startSample <= 10 * Limits.ASR_RATE })
        }
    }

    @Test fun speakerSlotsKeepTheirIdsWhenTheOrderWithinAChunkChanges() {
        val word = Word("spk_1", 100, 500, "hello")
        val first = Diarization.assign(listOf(word), listOf(SpeakerTurn(0, 1000, 3)), "diar_test_", stableIds = true)
        val later = Diarization.assign(listOf(word), listOf(SpeakerTurn(0, 1000, 3), SpeakerTurn(1200, 2000, 0)), "diar_test_", stableIds = true)
        assertEquals("diar_test_4", first.single().speaker)
        assertEquals(first.single().speaker, later.single().speaker)
    }
}
