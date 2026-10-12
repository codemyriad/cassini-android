package org.cassini.android.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PlaybackController (architecture 5.9) with a fake player on virtual time: the position flow, the saves (every 5 s,
 * final on pause and leave), focus and noisy pauses, the reload after a document replace, and speaker samples.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {
    private val audio = File("capture.opus")
    private val players = mutableListOf<FakePlayer>()
    private val platform = FakePlaybackPlatform()
    private val saves = mutableListOf<Save>()

    /** When set, prepare waits for it (a long file). */
    private var prepareGate: CompletableDeferred<Unit>? = null

    private data class Save(val noteId: String, val positionMs: Long, val final: Boolean)

    private fun TestScope.controller(): PlaybackController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = { testScheduler.currentTime }
        return PlaybackController(
            PlaybackController.Parts(
                scope = backgroundScope,
                main = dispatcher,
                player = { FakePlayer(clock, DURATION_MS) { prepareGate }.also(players::add) },
                platform = platform,
                store = { noteId, positionMs, final -> saves += Save(noteId, positionMs, final) },
                clock = clock,
            ),
        )
    }

    @Test fun thePositionIsSavedEveryFiveSecondsAndFinallyOnPauseAndLeave() = runTest {
        val playback = controller()
        assertTrue(playback.open(NOTE, audio, positionMs = 1_000))
        assertEquals(1_000L, playback.state.value.positionMs)

        playback.play()
        assertTrue(platform.focused)
        assertTrue(platform.noisyWatched)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(playback.state.value.playing)
        val position = playback.state.value.positionMs
        assertTrue("the position follows playback: $position", position in 1_850L..2_000L)

        // Ticks every 150 ms: the 5 s saves come at 5.1 s and 10.2 s of playback.
        advanceTimeBy(9_300)
        runCurrent()
        assertEquals(listOf(false, false), saves.map { it.final })
        assertEquals(6_100L, saves[0].positionMs)
        assertEquals(11_200L, saves[1].positionMs)

        playback.pause()
        runCurrent()
        assertEquals(Save(NOTE, 11_300, final = true), saves.last())
        assertFalse(platform.focused)
        assertFalse(platform.noisyWatched)
        val count = saves.size
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals("no saves while paused", count, saves.size)

        playback.seekTo(30_000)
        playback.leave()
        assertEquals(Save(NOTE, 30_000, final = true), saves.last())
        assertTrue(players.single().released)
        assertEquals(PlaybackState(), playback.state.value)
    }

    @Test fun focusLossAndUnpluggedHeadphonesPause() = runTest {
        val playback = controller()
        playback.open(NOTE, audio, positionMs = 0)
        playback.play()
        advanceTimeBy(2_000)
        platform.loseFocus()
        runCurrent()
        assertFalse(playback.state.value.playing)
        assertEquals(Save(NOTE, 2_000, final = true), saves.last())

        playback.play()
        advanceTimeBy(1_000)
        platform.unplug()
        runCurrent()
        assertFalse(playback.state.value.playing)
        assertEquals(Save(NOTE, 3_000, final = true), saves.last())
    }

    @Test fun aSpeakerSampleRestoresThePreviousPosition() = runTest {
        val playback = controller()
        playback.open(NOTE, audio, positionMs = 10_000)

        assertTrue(playback.playRange(NOTE, startMs = 30_000, endMs = 32_000))
        assertTrue(playback.state.value.playing)
        assertTrue(playback.state.value.sample)
        advanceTimeBy(2_200)
        runCurrent()
        assertFalse(playback.state.value.playing)
        assertFalse(playback.state.value.sample)
        assertEquals(10_000L, playback.state.value.positionMs)
        assertTrue("a sample position is never saved", saves.none { it.positionMs >= 30_000 })

        // While playing, the sample returns to the playing position and plays on.
        playback.play()
        advanceTimeBy(1_000)
        playback.playRange(NOTE, startMs = 40_000, endMs = 41_000)
        advanceTimeBy(1_200)
        runCurrent()
        assertTrue(playback.state.value.playing)
        val position = playback.state.value.positionMs
        assertTrue("back at about 11 s: $position", position in 11_000L..11_400L)

        assertFalse("another note", playback.playRange("other", 0, 1_000))
    }

    @Test fun aPauseDuringASampleThatInterruptedPlaybackStaysPaused() = runTest {
        val playback = controller()
        playback.open(NOTE, audio, positionMs = 10_000)

        // Unplugged headphones, a focus loss and the Pause button: each one restores the position and stays paused.
        val interruptions = listOf(platform::unplug, platform::loseFocus, playback::pause)
        interruptions.forEachIndexed { index, interrupt ->
            playback.play()
            advanceTimeBy(1_000)
            // The player's position, not the last 150 ms tick.
            val before = players.single().positionMs
            assertTrue(playback.playRange(NOTE, startMs = 40_000, endMs = 45_000))
            advanceTimeBy(500)
            interrupt()
            runCurrent()
            val state = playback.state.value
            assertFalse("interruption $index: paused", state.playing)
            assertFalse("interruption $index: no sample", state.sample)
            assertEquals("interruption $index: position from before", before, state.positionMs)
            assertFalse("interruption $index: focus given back", platform.focused)
            assertFalse("interruption $index: noisy not watched", platform.noisyWatched)
            assertEquals(Save(NOTE, before, final = true), saves.last())
            advanceTimeBy(5_000)
            runCurrent()
            assertFalse("interruption $index: still paused", playback.state.value.playing)
        }
    }

    @Test fun leavingDuringASampleSavesThePositionFromBefore() = runTest {
        val playback = controller()
        playback.open(NOTE, audio, positionMs = 5_000)
        playback.playRange(NOTE, startMs = 20_000, endMs = 25_000)
        advanceTimeBy(1_000)
        playback.leave()
        assertEquals(Save(NOTE, 5_000, final = true), saves.single())
    }

    @Test fun aReplacedDocumentIsPreparedAgainAtTheSamePosition() = runTest {
        val playback = controller()
        playback.open(NOTE, audio, positionMs = 0)
        playback.play()
        advanceTimeBy(3_000)

        val sealed = File("document.opus")
        assertTrue(playback.reload(sealed))
        assertEquals(2, players.size)
        assertTrue(players[0].released)
        assertEquals(sealed, players[1].file)
        assertEquals(3_000L, players[1].positionMs)
        assertTrue("still playing", playback.state.value.playing)
        advanceTimeBy(600)
        runCurrent()
        assertEquals(3_600L, playback.state.value.positionMs)
    }

    @Test fun aNoteLeftWhileItsFileIsPreparedAgainKeepsNoPlayer() = runTest {
        val playback = controller()
        playback.open(NOTE, audio, positionMs = 0)
        playback.play()
        advanceTimeBy(3_000)
        val gate = CompletableDeferred<Unit>()
        prepareGate = gate
        val reloaded = backgroundScope.async { playback.reload(File("document.opus")) }
        runCurrent()

        playback.leave()
        gate.complete(Unit)
        assertFalse(reloaded.await())
        runCurrent()
        assertTrue("the new player is released", players[1].released)
        assertEquals(PlaybackState(), playback.state.value)
        assertFalse(platform.focused)
        assertFalse(platform.noisyWatched)
        assertEquals(Save(NOTE, 3_000, final = true), saves.last())
    }

    @Test fun overlappingOpensOfTwoNotesKeepOnlyTheLastPlayer() = runTest {
        val playback = controller()
        val screenA = Any()
        val screenB = Any()
        val gateA = CompletableDeferred<Unit>()
        prepareGate = gateA
        val openA = backgroundScope.async { playback.open(NOTE, audio, positionMs = 0, owner = screenA) }
        runCurrent()
        // The screen of A goes while its file prepares; then the screen of B opens, and B prepares first.
        playback.leave(screenA)
        val gateB = CompletableDeferred<Unit>()
        prepareGate = gateB
        val openB = backgroundScope.async { playback.open(OTHER, audio, positionMs = 2_000, owner = screenB) }
        runCurrent()
        gateB.complete(Unit)
        assertTrue(openB.await())
        gateA.complete(Unit)
        assertFalse("the overtaken open of A ends without a player", openA.await())

        assertEquals(2, players.size)
        assertTrue("the late player of A is released", players[0].released)
        assertFalse(players[1].released)
        assertEquals(OTHER, playback.state.value.noteId)
        assertEquals(2_000L, playback.state.value.positionMs)
        // The late leave of the cleared screen of A does not touch B.
        playback.leave(screenA)
        assertFalse(players[1].released)
        playback.play()
        assertTrue(playback.state.value.playing)

        // Without a leave between them, the open that came last wins, also when the other one prepares last.
        val gateC = CompletableDeferred<Unit>()
        prepareGate = gateC
        val openC = backgroundScope.async { playback.open(NOTE, audio, positionMs = 0, owner = screenA) }
        runCurrent()
        val gateD = CompletableDeferred<Unit>()
        prepareGate = gateD
        val openD = backgroundScope.async { playback.open(OTHER, audio, positionMs = 0, owner = screenB) }
        runCurrent()
        gateD.complete(Unit)
        gateC.complete(Unit)
        assertTrue(openD.await())
        assertFalse(openC.await())
        assertEquals(4, players.size)
        assertEquals(listOf(true, true, true, false), players.map { it.released })
        assertEquals(OTHER, playback.state.value.noteId)
    }

    @Test fun aSecondScreenOfTheSameNoteKeepsItsPlayerAfterTheFirstLeaves() = runTest {
        val playback = controller()
        val first = Any()
        val second = Any()
        val gate1 = CompletableDeferred<Unit>()
        prepareGate = gate1
        val open1 = backgroundScope.async { playback.open(NOTE, audio, positionMs = 0, owner = first) }
        runCurrent()
        playback.leave(first)
        val gate2 = CompletableDeferred<Unit>()
        prepareGate = gate2
        val open2 = backgroundScope.async { playback.open(NOTE, audio, positionMs = 4_000, owner = second) }
        runCurrent()
        // The first open prepares last: it must not replace the player of the second screen.
        gate2.complete(Unit)
        assertTrue(open2.await())
        gate1.complete(Unit)
        assertFalse(open1.await())
        assertEquals(2, players.size)
        assertTrue("the player of the first open is released", players[0].released)
        assertFalse(players[1].released)

        // The first screen's late leave (its ViewModel was cleared during the open) leaves the second one playing.
        playback.play()
        playback.leave(first)
        runCurrent()
        assertFalse(players[1].released)
        assertTrue(playback.state.value.playing)
        assertEquals(NOTE, playback.state.value.noteId)
        assertTrue("no final save for the screen that is gone", saves.none { it.final })
        assertFalse("only the owner reloads", playback.reload(File("document.opus"), owner = first))
        assertEquals(2, players.size)

        // The same note and file for a third screen keeps the player and moves it to that screen.
        val third = Any()
        assertTrue(playback.open(NOTE, audio, positionMs = 0, owner = third))
        assertEquals(2, players.size)
        playback.leave(second)
        assertFalse(players[1].released)
        playback.leave(third)
        assertTrue(players[1].released)
        assertEquals(PlaybackState(), playback.state.value)
        assertEquals(NOTE, saves.last().noteId)
        assertTrue(saves.last().final)
    }

    @Test fun anOpenOfAnotherNoteDuringAReloadKeepsOnlyTheNewPlayer() = runTest {
        val playback = controller()
        playback.open(NOTE, audio, positionMs = 0)
        playback.play()
        advanceTimeBy(3_000)
        val reloadGate = CompletableDeferred<Unit>()
        prepareGate = reloadGate
        val reloaded = backgroundScope.async { playback.reload(File("document.opus")) }
        runCurrent()
        prepareGate = null
        assertTrue(playback.open(OTHER, audio, positionMs = 0))
        reloadGate.complete(Unit)
        assertFalse(reloaded.await())
        runCurrent()

        assertEquals(3, players.size)
        assertEquals(listOf(true, true, false), players.map { it.released })
        assertEquals(OTHER, playback.state.value.noteId)
        assertFalse("the focus of the first note is given back", platform.focused)
        assertEquals("the position of the first note is saved", Save(NOTE, 3_000, final = true), saves.last())
    }

    @Test fun aFileThatCannotBePlayedFails() = runTest {
        val playback = controller()
        assertFalse(playback.open(NOTE, File(FakePlayer.BROKEN), positionMs = 0))
        assertTrue(playback.state.value.failed)
        assertTrue(players.single().released)
        playback.play()
        assertFalse(platform.focused)
    }

    private companion object {
        const val NOTE = "note-1"
        const val OTHER = "note-2"
        const val DURATION_MS = 60_000L
    }
}

/** A player whose position advances with the virtual clock while it plays. */
private class FakePlayer(
    private val clock: () -> Long,
    override val durationMs: Long,
    private val gate: () -> CompletableDeferred<Unit>? = { null },
) : AudioPlayer {
    private var base = 0L
    private var startedAt: Long? = null
    private var completion: (() -> Unit)? = null
    var file: File? = null
    var released = false

    override val positionMs: Long
        get() = (base + (startedAt?.let { clock() - it } ?: 0L)).coerceAtMost(durationMs)
    override val playing: Boolean get() = startedAt != null

    override suspend fun prepare(file: File) {
        gate()?.await()
        check(file.name != BROKEN) { "Cannot play $file" }
        this.file = file
    }

    override fun play() {
        if (startedAt == null) startedAt = clock()
    }

    override fun pause() {
        base = positionMs
        startedAt = null
    }

    override fun seekTo(positionMs: Long) {
        base = positionMs
        if (startedAt != null) startedAt = clock()
    }

    override fun onCompletion(listener: () -> Unit) {
        completion = listener
    }

    override fun release() {
        released = true
        startedAt = null
    }

    companion object {
        const val BROKEN = "broken.opus"
    }
}

private class FakePlaybackPlatform : PlaybackPlatform {
    var focused = false
    var noisyWatched = false
    private var onLoss: (() -> Unit)? = null
    private var onNoisy: (() -> Unit)? = null

    fun loseFocus() = onLoss?.invoke()

    fun unplug() = onNoisy?.invoke()

    override fun requestFocus(onLoss: () -> Unit): Boolean {
        focused = true
        this.onLoss = onLoss
        return true
    }

    override fun abandonFocus() {
        focused = false
        onLoss = null
    }

    override fun watchNoisy(onNoisy: () -> Unit) {
        noisyWatched = true
        this.onNoisy = onNoisy
    }

    override fun unwatchNoisy() {
        noisyWatched = false
        onNoisy = null
    }
}
