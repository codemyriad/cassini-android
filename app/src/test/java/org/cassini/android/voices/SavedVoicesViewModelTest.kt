package org.cassini.android.voices

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.cassini.core.speakers.Voice
import org.cassini.data.voices.SavedVoice
import org.cassini.data.voices.SavedVoices
import org.cassini.data.voices.VoiceEdit
import org.cassini.data.voices.VoiceProblem
import org.cassini.ui.voices.PersonUi
import org.cassini.ui.voices.SavedVoicesDialog
import org.cassini.ui.voices.SavedVoicesEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Saved voices ViewModel over fake ports (features.md section 10). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SavedVoicesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val ports = FakeVoicesPorts()
    private val saved = SavedStateHandle()
    private var closed = 0

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): SavedVoicesViewModel {
        val viewModel = SavedVoicesViewModel(saved, ports)
        backgroundScope.launch { viewModel.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.close.collect { closed++ } }
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun listsThePeopleInTheRepositoryOrderWithTheirCountsAndStaleFlag() = runTest(dispatcher) {
        val people = listOf(saved("g", "Giulia", 14, 1_320.6), saved("l", "Luca", 2, 30.0, stale = true))
        ports.people.value = SavedVoices(people, problem = null)
        val state = viewModel().state.value!!
        assertEquals(
            listOf(PersonUi("g", "Giulia", 14, 1_320, false), PersonUi("l", "Luca", 2, 30, true)),
            state.people,
        )
        assertEquals(false, state.readOnly)
        assertNull(state.dialog)
    }

    @Test
    fun renameFromTheOptionsMenu() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(SavedVoicesEvent.OpenMenu("g"))
        advanceUntilIdle()
        assertEquals("g", viewModel.state.value!!.menuFor)
        viewModel.onEvent(SavedVoicesEvent.Rename("g"))
        advanceUntilIdle()
        assertNull(viewModel.state.value!!.menuFor)
        assertEquals(SavedVoicesDialog.Rename("g", "Giulia"), viewModel.state.value!!.dialog)
        viewModel.onEvent(SavedVoicesEvent.RenameConfirmed("g", "Giulia R."))
        advanceUntilIdle()
        assertNull(viewModel.state.value!!.dialog)
        assertEquals(listOf("g" to "Giulia R."), ports.renamed)
    }

    @Test
    fun forgetAndForgetEveryoneAskFirst() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(SavedVoicesEvent.Forget("l"))
        advanceUntilIdle()
        assertEquals(SavedVoicesDialog.Forget("l", "Luca"), viewModel.state.value!!.dialog)
        viewModel.onEvent(SavedVoicesEvent.DismissDialog)
        advanceUntilIdle()
        assertTrue(ports.forgotten.isEmpty())

        viewModel.onEvent(SavedVoicesEvent.Forget("l"))
        viewModel.onEvent(SavedVoicesEvent.ConfirmDialog)
        advanceUntilIdle()
        assertEquals(listOf("l"), ports.forgotten)

        viewModel.onEvent(SavedVoicesEvent.ForgetEveryone)
        advanceUntilIdle()
        assertEquals(SavedVoicesDialog.ForgetEveryone(2), viewModel.state.value!!.dialog)
        viewModel.onEvent(SavedVoicesEvent.ConfirmDialog)
        advanceUntilIdle()
        assertEquals(1, ports.everyone)
        assertNull(viewModel.state.value!!.dialog)
    }

    @Test
    fun theOpenDialogSurvivesProcessDeath() = runTest(dispatcher) {
        viewModel().onEvent(SavedVoicesEvent.Forget("g"))
        advanceUntilIdle()
        val restored = SavedVoicesViewModel(SavedStateHandle(mapOf("dialog" to "forget:g")), ports)
        backgroundScope.launch { restored.state.collect {} }
        advanceUntilIdle()
        assertEquals(SavedVoicesDialog.Forget("g", "Giulia"), restored.state.value!!.dialog)
        assertEquals("forget:g", saved.get<String>("dialog"))
    }

    @Test
    fun aListThatCannotBeUsedIsReadOnlyAndIgnoresEdits() = runTest(dispatcher) {
        ports.people.value = SavedVoices(listOf(saved("g", "Giulia", 1, 60.0)), VoiceProblem.NEWER)
        val viewModel = viewModel()
        assertTrue(viewModel.state.value!!.readOnly)
        viewModel.onEvent(SavedVoicesEvent.OpenMenu("g"))
        viewModel.onEvent(SavedVoicesEvent.Forget("g"))
        viewModel.onEvent(SavedVoicesEvent.ForgetEveryone)
        viewModel.onEvent(SavedVoicesEvent.ConfirmDialog)
        viewModel.onEvent(SavedVoicesEvent.RenameConfirmed("g", "X"))
        advanceUntilIdle()
        val state = viewModel.state.value!!
        assertNull(state.menuFor)
        assertNull(state.dialog)
        assertTrue(ports.forgotten.isEmpty() && ports.renamed.isEmpty() && ports.everyone == 0)
    }

    @Test
    fun backCloses() = runTest(dispatcher) {
        viewModel().onEvent(SavedVoicesEvent.Back)
        advanceUntilIdle()
        assertEquals(1, closed)
    }

    private class FakeVoicesPorts : SavedVoicesPorts {
        val people = MutableStateFlow(
            SavedVoices(listOf(saved("g", "Giulia", 14, 1_320.0), saved("l", "Luca", 2, 30.0)), null),
        )
        val renamed = mutableListOf<Pair<String, String>>()
        val forgotten = mutableListOf<String>()
        var everyone = 0

        override fun people(): Flow<SavedVoices> = people

        override suspend fun rename(id: String, name: String): VoiceEdit {
            renamed += id to name
            return VoiceEdit.Done
        }

        override suspend fun forget(id: String): VoiceEdit {
            forgotten += id
            return VoiceEdit.Done
        }

        override suspend fun forgetEveryone(): VoiceEdit {
            everyone++
            return VoiceEdit.Done
        }
    }

    private companion object {
        fun saved(id: String, name: String, notes: Int, seconds: Double, stale: Boolean = false) = SavedVoice(
            Voice(id, name, "model", FloatArray(2), count = 1, seconds = seconds, createdAt = 0, updatedAt = 0),
            notes,
            stale,
        )
    }
}
