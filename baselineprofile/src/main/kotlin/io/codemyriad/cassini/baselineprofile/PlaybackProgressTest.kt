package io.codemyriad.cassini.baselineprofile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Deterministic slider publications exercise the same replay logic as the real UiDevice adapter. */
class PlaybackProgressTest {
    @Test fun replayCanFinishBeforeItsFirstPostClickObservation() {
        val player = Slider(current = SHORT_END, end = SHORT_END)
        player.run()
        assertEquals(1, player.seeks)
        assertEquals(1, player.plays)
        assertEquals(listOf(SHORT_END), player.playbackObservations)
    }

    @Test fun eachRepeatedOpeningCanReplayFromTheSavedEnd() {
        val player = Slider(current = SHORT_END, end = SHORT_END)
        repeat(3) { player.run() }
        assertEquals(3, player.seeks)
        assertEquals(3, player.plays)
    }

    @Test fun omittingPlayCannotSucceedAfterTheSeek() {
        val player = Slider(current = SHORT_END, end = SHORT_END)
        player.omitPlay = true
        expectFailure("Playback did not reach") { player.run() }
        assertEquals(1, player.seeks)
        assertEquals(0f, player.current, 0f)
    }

    @Test fun playWaitsForTheSliderToAcknowledgeZero() {
        val player = Slider(current = SHORT_END, end = SHORT_END)
        player.acknowledgeSeek = false
        expectFailure("The player did not seek to zero") { player.run() }
        assertEquals(0, player.plays)
    }

    @Test fun playbackFromWithinANotePreservesItsStartingPosition() {
        val player = Slider(current = 500f, end = 4_000f)
        player.run()
        assertEquals(0, player.seeks)
        assertEquals(2_500f, player.current, 0f)
    }

    private fun expectFailure(message: String, action: () -> Unit) {
        try {
            action()
            fail("Expected failure containing $message")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(message))
        }
    }

    private class Slider(var current: Float, private val end: Float) {
        var seeks = 0
        var plays = 0
        var omitPlay = false
        var acknowledgeSeek = true
        val playbackObservations = mutableListOf<Float>()
        private var seekPending = false
        private var playingObserved = false

        fun run() {
            playingObserved = false
            awaitPlaybackProgress(
                seekToStart = {
                    seeks++
                    seekPending = true
                },
                play = {
                    assertTrue("Play ran before the slider acknowledged the seek", !seekPending)
                    plays++
                    playingObserved = true
                    if (!omitPlay) current = minOf(current + 2_000f, end)
                },
                awaitPosition = { message, matches ->
                    var position = PlaybackPosition(current, end)
                    if (playingObserved) playbackObservations += current
                    if (!matches(position) && seekPending && acknowledgeSeek) {
                        current = 0f
                        seekPending = false
                        position = PlaybackPosition(current, end)
                    }
                    check(matches(position)) { message }
                    position
                },
            )
        }
    }

    private companion object {
        const val SHORT_END = 400f
    }
}
