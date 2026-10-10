package org.cassini.android.nav

import android.util.Log
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.Flow
import org.cassini.android.AppGraph
import org.cassini.android.library.GraphLibraryPorts
import org.cassini.android.library.LibraryNavigator
import org.cassini.android.library.LibraryRoute
import org.cassini.android.library.LibraryViewModel
import org.cassini.android.recording.GraphRecordingPorts
import org.cassini.android.recording.RecordingNavigator
import org.cassini.android.recording.RecordingRoute
import org.cassini.android.recording.RecordingViewModel
import org.cassini.data.notes.ImportOutcome
import org.cassini.ui.theme.CassiniTheme

/**
 * The app's navigation (docs/architecture.md section 6, docs/design/ui.md section 9): one [NavDisplay] over the
 * saved back stack, with the Library at the bottom. Each entry keeps its saveable state and its own ViewModels.
 * [links] are the deep links that [org.cassini.android.MainActivity] receives; each one changes the back stack as
 * [DeepLinks] says.
 *
 * The Library and Recording have their routes; the other destinations are placeholders until their ViewModels and
 * routes land (P10 steps 4 and 5).
 */
@Composable
@Suppress("FunctionNaming") // Composables are nouns in PascalCase.
fun CassiniApp(graph: AppGraph, links: Flow<DeepLink>, modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(LibraryKey)
    val context = LocalContext.current
    LaunchedEffect(links) {
        links.collect { link -> open(graph, backStack, link) }
    }
    // Light screens have dark icons in the light theme; Recording has light icons always.
    SystemBarsAppearance(darkIcons = backStack.lastOrNull() != RecordingKey && !isSystemInDarkTheme())
    NavDisplay(
        backStack = backStack,
        modifier = modifier.fillMaxSize().background(CassiniTheme.colors.bg),
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        transitionSpec = { push() },
        popTransitionSpec = { pop() },
        entryProvider = entryProvider {
            entry<LibraryKey> {
                val viewModel = viewModel {
                    LibraryViewModel(createSavedStateHandle(), GraphLibraryPorts(graph, context), graph.clock())
                }
                LibraryRoute(viewModel, libraryNavigator(backStack))
            }
            entry<NoteKey> { key -> Placeholder("Note ${key.noteId}") }
            entry<RecordingKey>(metadata = recordingTransitions) {
                val viewModel = viewModel { RecordingViewModel(createSavedStateHandle(), GraphRecordingPorts(graph)) }
                RecordingRoute(viewModel, recordingNavigator(backStack))
            }
            entry<ModelDownloadKey> { Placeholder("Model download") }
            entry<SettingsKey> {
                Placeholder("Settings") {
                    Destination("Saved voices") { backStack.add(SavedVoicesKey) }
                    Destination("Model download") { backStack.add(ModelDownloadKey()) }
                    Destination("Licenses") { backStack.add(LicensesKey) }
                }
            }
            entry<SavedVoicesKey> { Placeholder("Saved voices") }
            entry<TrashKey> { Placeholder("Trash") }
            entry<LicensesKey> { Placeholder("Licenses") }
        },
    )
}

/** Changes [backStack] for [link]. An import runs first; a failed one leaves the stack as it is. */
private suspend fun open(graph: AppGraph, backStack: NavBackStack<NavKey>, link: DeepLink) {
    val target = when (link) {
        DeepLink.Recording -> DeepLinks.recording(backStack)
        is DeepLink.Note -> DeepLinks.note(backStack, link.noteId)
        is DeepLink.Import -> {
            // Through the import tracker, so the Library shows "Importing <name>…" and a failure's banner.
            val noteId = when (val outcome = graph.notices.imports.start(link.uri).await()) {
                is ImportOutcome.Created -> outcome.noteId
                is ImportOutcome.Existing -> outcome.noteId
                else -> null.also { Log.w(TAG, "Import of a viewed file ended with $outcome") }
            }
            noteId?.let { DeepLinks.imported(backStack, it) } ?: return
        }
    }
    backStack.replaceWith(target)
}

/** The Library's navigation: pushes, and the one Recording entry (pop to it when it is in the stack). */
private fun libraryNavigator(backStack: NavBackStack<NavKey>) = object : LibraryNavigator {
    override fun open(key: NavKey) {
        if (backStack.lastOrNull() != key) backStack.add(key)
    }

    override fun openRecording() = backStack.replaceWith(DeepLinks.recording(backStack))
}

/** The Recording screen's navigation: minimise pops it; Finish puts the note (and Model download) in its place. */
private fun recordingNavigator(backStack: NavBackStack<NavKey>) = object : RecordingNavigator {
    override fun close() {
        val index = backStack.lastIndexOf(RecordingKey)
        if (index > 0) backStack.removeAt(index)
    }

    override fun replaceWith(keys: List<NavKey>) {
        val index = backStack.lastIndexOf(RecordingKey)
        val below = if (index >= 0) backStack.take(index) else backStack.toList()
        backStack.replaceWith(DeepLinks.rooted(below) + keys)
    }
}

/** Makes [this] equal to [target], keeping the entries the two share at the bottom. */
internal fun MutableList<NavKey>.replaceWith(target: List<NavKey>) {
    val shared = zip(target).takeWhile { (a, b) -> a == b }.size
    while (size > shared) removeAt(lastIndex)
    addAll(target.drop(shared))
}

private const val TAG = "CassiniNav"
private const val PUSH_MS = 250
private const val SLIDE_FRACTION = 0.3f

// Wide screens: the content stays at most 560 dp wide and centred (docs/architecture.md section 6).
private const val MAX_CONTENT_WIDTH_DP = 560
private const val GAP_DP = 8

private fun <T> pushSpec() = tween<T>(PUSH_MS, easing = FastOutSlowInEasing)

/** The push of every destination but Recording: in from 30 % of the width on the right, with a fade. */
private fun AnimatedContentTransitionScope<Scene<NavKey>>.push(): ContentTransform {
    val enter = slideInHorizontally(pushSpec()) { (it * SLIDE_FRACTION).toInt() } + fadeIn(pushSpec())
    return enter togetherWith fadeOut(pushSpec())
}

/** The pop of [push], reversed. */
private fun AnimatedContentTransitionScope<Scene<NavKey>>.pop(): ContentTransform {
    val exit = slideOutHorizontally(pushSpec()) { (it * SLIDE_FRACTION).toInt() } + fadeOut(pushSpec())
    return fadeIn(pushSpec()) togetherWith exit
}

/** Recording comes up from the bottom and goes down again, also on a predictive back. */
private val recordingTransitions: Map<String, Any> =
    NavDisplay.transitionSpec { slideInVertically { it } togetherWith fadeOut() } +
        NavDisplay.popTransitionSpec { fadeIn() togetherWith slideOutVertically { it } } +
        NavDisplay.predictivePopTransitionSpec { _ -> fadeIn() togetherWith slideOutVertically { it } }

/** A stand-in screen: the destination's name and the destinations it leads to. */
@Composable
@Suppress("FunctionNaming") // Composables are nouns in PascalCase.
private fun Placeholder(
    title: String,
    background: Color = CassiniTheme.colors.bg,
    ink: Color = CassiniTheme.colors.ink,
    links: @Composable () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(background).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = MAX_CONTENT_WIDTH_DP.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
        ) {
            Text(title, style = CassiniTheme.type.screenTitle, color = ink)
            links()
        }
    }
}

@Composable
@Suppress("FunctionNaming") // Composables are nouns in PascalCase.
private fun Destination(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label) }
}
