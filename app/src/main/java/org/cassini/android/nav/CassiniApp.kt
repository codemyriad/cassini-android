package org.cassini.android.nav

import android.content.Context
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.cassini.android.AppGraph
import org.cassini.android.library.GraphLibraryPorts
import org.cassini.android.library.LibraryRoute
import org.cassini.android.library.LibraryViewModel
import org.cassini.android.licenses.AssetLicensesPorts
import org.cassini.android.licenses.LicensesRoute
import org.cassini.android.licenses.LicensesViewModel
import org.cassini.android.models.GraphModelDownloadPorts
import org.cassini.android.models.ModelDownloadRoute
import org.cassini.android.models.ModelDownloadViewModel
import org.cassini.android.note.GraphNotePorts
import org.cassini.android.note.NoteRoute
import org.cassini.android.note.NoteViewModel
import org.cassini.android.processing.StartOrigin
import org.cassini.android.recording.GraphRecordingPorts
import org.cassini.android.recording.RecordingRoute
import org.cassini.android.recording.RecordingViewModel
import org.cassini.android.settings.GraphSettingsPorts
import org.cassini.android.settings.SettingsRoute
import org.cassini.android.settings.SettingsViewModel
import org.cassini.android.trash.GraphTrashPorts
import org.cassini.android.trash.TrashRoute
import org.cassini.android.trash.TrashViewModel
import org.cassini.android.voices.GraphSavedVoicesPorts
import org.cassini.android.voices.SavedVoicesRoute
import org.cassini.android.voices.SavedVoicesViewModel
import org.cassini.core.library.JobState
import org.cassini.ui.components.LocalNoteBounds
import org.cassini.ui.licenses.LicensesBackTo
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.trash.TrashBackTo

/**
 * The app's navigation (docs/architecture.md section 6, docs/design/ui.md section 9): one [NavDisplay] over the
 * saved back stack, with the Library at the bottom. Each entry keeps its saveable state and its own ViewModels.
 * [links] are the deep links that [org.cassini.android.MainActivity] receives; each one changes the back stack as
 * [DeepLinks] says.
 */
@Composable
@Suppress("FunctionNaming") // Composables are nouns in PascalCase.
fun CassiniApp(graph: AppGraph, links: Flow<DeepLink>, modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(LibraryKey)
    val noteBounds = rememberNoteBounds()
    val context = LocalContext.current
    LaunchedEffect(links) {
        links.collect { link -> open(graph, backStack, link) }
    }
    // A viewed file's note opens when its import ends, also in an activity that was recreated meanwhile.
    LaunchedEffect(graph) {
        graph.viewedImports.ready.collect { ready ->
            for (noteId in ready) {
                backStack.replaceWith(DeepLinks.imported(backStack, noteId))
                graph.viewedImports.take(noteId)
            }
        }
    }
    // The screen on top sets the bar icons; during a back gesture they stay those of that screen until it pops.
    SystemBarsAppearance(darkIcons = darkBarIcons(backStack.lastOrNull(), isSystemInDarkTheme()))
    CompositionLocalProvider(LocalNoteBounds provides noteBounds) {
        NavDisplay(
            backStack = backStack,
            modifier = modifier.fillMaxSize().background(CassiniTheme.colors.bg),
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            transitionSpec = { appPush(noteBounds) },
            popTransitionSpec = { appPop(noteBounds) },
            predictivePopTransitionSpec = { _ -> appPop(noteBounds) },
            entryProvider = entryProvider {
                entry<LibraryKey> {
                    val viewModel = viewModel {
                        LibraryViewModel(createSavedStateHandle(), GraphLibraryPorts(graph, context), graph.clock())
                    }
                    LibraryRoute(viewModel, libraryNavigator(backStack))
                }
                entry<NoteKey> { key ->
                    NoteEntry(key, graph, backStack, noteBounds, context)
                }
                entry<RecordingKey>(metadata = recordingTransitions) {
                    val viewModel = viewModel {
                        RecordingViewModel(createSavedStateHandle(), GraphRecordingPorts(graph))
                    }
                    RecordingRoute(viewModel, recordingNavigator(backStack))
                }
                screenEntries(graph, backStack, context)
            },
        )
    }
}

@Composable
@Suppress("FunctionNaming")
private fun NoteEntry(
    key: NoteKey,
    graph: AppGraph,
    backStack: NavBackStack<NavKey>,
    noteBounds: org.cassini.ui.components.NoteBounds,
    context: Context,
) {
    val viewModel = viewModel {
        NoteViewModel(
            createSavedStateHandle(),
            key,
            GraphNotePorts(graph, context),
            graph.clock(),
            recentWords = graph.recentWords,
        )
    }
    val top = backStack.lastOrNull()
    val withLibrary = top is LibraryKey || (top == key && backStack.below(key) is LibraryKey)
    val transitionRunning = isNoteTransitionRunning(withLibrary)
    val deferLinks = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(transitionRunning) }
    LaunchedEffect(transitionRunning) {
        if (!transitionRunning) deferLinks.value = false
    }
    CompositionLocalProvider(org.cassini.ui.components.LocalDeferWordLinks provides deferLinks.value) {
        NoteRoute(
            viewModel = viewModel,
            navigator = noteNavigator(backStack, noteBounds, key.noteId),
            modifier = Modifier.noteContainerTransition(key.noteId, withLibrary = withLibrary),
            transitionRunning = transitionRunning,
        )
    }
}

/** Model download, Settings, Saved voices, Trash and Licenses, each with its ViewModel over [graph]. */
private fun EntryProviderScope<NavKey>.screenEntries(
    graph: AppGraph,
    backStack: NavBackStack<NavKey>,
    context: Context,
) {
    entry<ModelDownloadKey> { key ->
        val viewModel = viewModel {
            ModelDownloadViewModel(key.noteId, GraphModelDownloadPorts(graph, context), createSavedStateHandle())
        }
        ModelDownloadRoute(viewModel, screenNavigator(backStack, key))
    }
    entry<SettingsKey> {
        val viewModel = viewModel { SettingsViewModel(GraphSettingsPorts(graph)) }
        SettingsRoute(viewModel, screenNavigator(backStack, SettingsKey))
    }
    entry<SavedVoicesKey> {
        val viewModel = viewModel { SavedVoicesViewModel(createSavedStateHandle(), GraphSavedVoicesPorts(graph)) }
        SavedVoicesRoute(viewModel, screenNavigator(backStack, SavedVoicesKey))
    }
    entry<TrashKey> {
        val backTo = if (backStack.below(TrashKey) is SettingsKey) TrashBackTo.SETTINGS else TrashBackTo.LIBRARY
        val viewModel = viewModel {
            TrashViewModel(createSavedStateHandle(), GraphTrashPorts(graph), backTo, graph.clock())
        }
        TrashRoute(viewModel, screenNavigator(backStack, TrashKey))
    }
    entry<LicensesKey> {
        val below = backStack.below(LicensesKey)
        val backTo = if (below is ModelDownloadKey) LicensesBackTo.MODEL_DOWNLOAD else LicensesBackTo.SETTINGS
        val viewModel = viewModel { LicensesViewModel(createSavedStateHandle(), AssetLicensesPorts(context), backTo) }
        LicensesRoute(viewModel, screenNavigator(backStack, LicensesKey))
    }
}

/**
 * Changes [backStack] for [link]. An import only starts here ([AppGraph.viewedImports]); its note opens when it ends,
 * and a failed one leaves the stack as it is.
 */
private fun open(graph: AppGraph, backStack: NavBackStack<NavKey>, link: DeepLink) {
    val target = when (link) {
        DeepLink.Recording -> DeepLinks.recording(backStack)
        is DeepLink.Note -> {
            // "Resume" of the paused-by-Android notification: the app is visible now, so the note can start again.
            if (link.resume) graph.appScope.launch { resume(graph, link.noteId) }
            DeepLinks.note(backStack, link.noteId)
        }
        is DeepLink.Import -> {
            // Through the import tracker, so the Library shows "Importing <name>…" and a failure's banner.
            graph.viewedImports.start(link.uri)
            return
        }
    }
    backStack.replaceWith(target)
}

/**
 * Puts a paused note in the queue again with its job's kind, from a visible screen (it clears a SYSTEM hold). A note
 * that is no longer paused (resumed already, or the intent is old) is left as it is.
 */
@Suppress("TooGenericExceptionCaught") // a failed resume is logged; the note screen still offers Resume
private suspend fun resume(graph: AppGraph, noteId: String) {
    try {
        val job = graph.awaitCatalogue().jobs.job(noteId)?.takeIf { it.state == JobState.PAUSED } ?: return
        graph.processing.transcribe(noteId, job.kind, StartOrigin.SCREEN)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w(TAG, "Cannot resume $noteId from the notification", error)
    }
}

/** The key under the last [key] in this stack, or null. */
internal fun List<NavKey>.below(key: NavKey): NavKey? = getOrNull(lastIndexOf(key) - 1)

/** Makes [this] equal to [target], keeping the entries the two share at the bottom. */
internal fun MutableList<NavKey>.replaceWith(target: List<NavKey>) {
    val shared = zip(target).takeWhile { (a, b) -> a == b }.size
    while (size > shared) removeAt(lastIndex)
    addAll(target.drop(shared))
}

private const val TAG = "CassiniNav"
private const val PUSH_MS = 250
internal const val SLIDE_FRACTION = 0.3f

private fun <T> pushSpec() = tween<T>(PUSH_MS, easing = FastOutSlowInEasing)

/** The push of every destination but Recording: in from 30 % of the width on the right, with a fade. */
internal fun <S : Scene<*>> AnimatedContentTransitionScope<S>.push(): ContentTransform {
    val enter = slideInHorizontally(pushSpec()) { (it * SLIDE_FRACTION).toInt() } + fadeIn(pushSpec())
    return enter togetherWith fadeOut(pushSpec())
}

/** The pop of [push], reversed. */
internal fun <S : Scene<*>> AnimatedContentTransitionScope<S>.pop(): ContentTransform {
    val exit = slideOutHorizontally(pushSpec()) { (it * SLIDE_FRACTION).toInt() } + fadeOut(pushSpec())
    return fadeIn(pushSpec()) togetherWith exit
}

/** Recording comes up from the bottom and goes down again, also on a predictive back. */
private val recordingTransitions: Map<String, Any> =
    NavDisplay.transitionSpec { slideInVertically { it } togetherWith fadeOut() } +
        NavDisplay.popTransitionSpec { fadeIn() togetherWith slideOutVertically { it } } +
        NavDisplay.predictivePopTransitionSpec { _ -> fadeIn() togetherWith slideOutVertically { it } }
