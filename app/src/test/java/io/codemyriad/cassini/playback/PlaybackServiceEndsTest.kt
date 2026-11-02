package io.codemyriad.cassini.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review of #40: when the playback session ends, so that no started service, session or notification stays. */
class PlaybackServiceEndsTest {
    private val playing = PlaybackState(noteId = "n1", durationMs = 60_000, playing = true)
    private val paused = playing.copy(playing = false)

    @Test fun anOpenNoteKeepsTheSessionWhilePlayingOrPausedInTheApp() {
        assertFalse(PlaybackService.ends(playing, taskRemoved = false))
        assertFalse(PlaybackService.ends(paused, taskRemoved = false))
    }

    @Test fun noNoteOrAFailedPlayerEndsIt() {
        assertTrue(PlaybackService.ends(PlaybackState(), taskRemoved = false))
        assertTrue(PlaybackService.ends(PlaybackState(noteId = "n1", failed = true), taskRemoved = false))
    }

    @Test fun afterSwipeAwayItEndsWhenPlaybackPauses() {
        assertFalse(PlaybackService.ends(playing, taskRemoved = true))
        assertTrue(PlaybackService.ends(paused, taskRemoved = true))
    }
}
