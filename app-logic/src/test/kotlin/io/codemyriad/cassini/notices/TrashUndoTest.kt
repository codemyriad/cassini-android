package io.codemyriad.cassini.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrashUndoTest {
    @Test fun consumingAnOlderSnackbarDoesNotDiscardTheNewerOne() {
        val undo = TrashUndo()
        val old = TrashedNote("old", "Old")
        val next = TrashedNote("next", "Next")
        undo.offer(old)
        undo.offer(next)
        undo.take(old)
        assertEquals(next, undo.pending.value)
        undo.take(next)
        assertNull(undo.pending.value)
    }
}
