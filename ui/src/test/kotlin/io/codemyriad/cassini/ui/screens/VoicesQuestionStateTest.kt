package io.codemyriad.cassini.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.note.NoteSheet
import io.codemyriad.cassini.ui.note.VoicesQuestionSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicesQuestionStateTest : BoardTest() {
    @Test fun pendingChoiceDisablesBothButtonsAndFailureAllowsRetry() {
        val ui = mutableStateOf(NoteSheet.VoicesQuestion(saving = true))
        val choices = mutableListOf<Boolean>()
        show { VoicesQuestionSheet(choices::add, {}, ui.value) }
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("Yes, remember voices").assertIsNotEnabled()
        rule.onNodeWithText("Not now").assertIsNotEnabled()
        rule.runOnIdle { ui.value = NoteSheet.VoicesQuestion(error = true) }
        rule.onNodeWithText("Couldn’t save this choice. Try again.").assertExists()
        rule.onNodeWithText("Yes, remember voices").assertIsEnabled()
        rule.onNodeWithText("Not now").assertIsEnabled().performClick()
        assertEquals(listOf(false), choices)
    }

    @Test fun systemDismissalRequestsPersistenceWithoutHidingTheQuestion() {
        var dismissals = 0
        show { VoicesQuestionSheet({}, { dismissals++ }) }
        rule.mainClock.autoAdvance = true
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).onFirst()
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        assertTrue(dismissals > 0)
        rule.onNodeWithText("Recognise people you name?").assertIsDisplayed()
    }
}
