package org.cassini.android.licenses

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cassini.ui.licenses.LicenseEntry
import org.cassini.ui.licenses.LicenseText
import org.cassini.ui.licenses.LicensesBackTo
import org.cassini.ui.licenses.LicensesEvent
import org.cassini.ui.licenses.LicensesUiState
import org.cassini.ui.licenses.SOURCE_CODE_URL

/** Reads the text of a Licenses row; [AssetLicensesPorts] reads the APK's assets, the tests pass a fake. */
fun interface LicensesPorts {
    suspend fun text(entry: LicenseEntry): String
}

/** [LicensesPorts] over the app's assets, read on [io]. */
class AssetLicensesPorts(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) : LicensesPorts {
    private val assets = context.applicationContext.assets
    private val texts = LicenseTexts(
        read = { path -> assets.open(path).bufferedReader().use { it.readText() } },
        list = { path -> assets.list(path)?.toList().orEmpty() },
    )

    override suspend fun text(entry: LicenseEntry): String = withContext(io) { texts.text(entry) }
}

/** What the Licenses route does for the ViewModel. */
sealed interface LicensesEffect {
    data object Close : LicensesEffect

    /** "Source code on GitHub": the browser. */
    data class OpenUrl(val url: String) : LicensesEffect
}

/**
 * Licenses, "Source code and notices" (features.md section 11; ui.md sections 7 and 8): every [LicenseEntry] in
 * order. A row opens its text in the pane; "Source code on GitHub" opens the browser instead. The opened row is in
 * [saved], so its pane comes back after process death (the text is read again). [backTo] is the screen under
 * Licenses (Settings or Model download), for the back button's label.
 */
class LicensesViewModel(
    private val saved: SavedStateHandle,
    private val ports: LicensesPorts,
    private val backTo: LicensesBackTo,
) : ViewModel() {
    private val effectChannel = Channel<LicensesEffect>(Channel.BUFFERED)

    /** One-shot navigation requests; the route collects them. */
    val effects: Flow<LicensesEffect> = effectChannel.receiveAsFlow()

    private val opened = saved.getStateFlow<String?>(KEY_OPEN, null)
    private val loaded = MutableStateFlow<LicenseText?>(null)

    private val entries = LicenseEntry.entries.toImmutableList()

    val state: StateFlow<LicensesUiState> = combine(opened, loaded) { open, text ->
        LicensesUiState(entries = entries, open = text?.takeIf { it.entry.name == open }, backTo = backTo)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LicensesUiState(entries, null, backTo))

    init {
        opened.value?.let { name -> LicenseEntry.entries.firstOrNull { it.name == name } }?.let(::load)
    }

    fun onEvent(event: LicensesEvent) {
        when (event) {
            LicensesEvent.Back -> effectChannel.trySend(LicensesEffect.Close)
            is LicensesEvent.Open -> open(event.entry)
            LicensesEvent.CloseText -> {
                saved[KEY_OPEN] = null
                loaded.value = null
            }
        }
    }

    private fun open(entry: LicenseEntry) {
        if (entry == LicenseEntry.SOURCE_CODE) {
            effectChannel.trySend(LicensesEffect.OpenUrl(SOURCE_CODE_URL))
            return
        }
        saved[KEY_OPEN] = entry.name
        load(entry)
    }

    private fun load(entry: LicenseEntry) {
        viewModelScope.launch {
            val text = try {
                ports.text(entry)
            } catch (error: CancellationException) {
                throw error
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Log.e(TAG, "Cannot read the text of $entry", error)
                saved[KEY_OPEN] = null
                return@launch
            }
            if (saved.get<String>(KEY_OPEN) == entry.name) loaded.value = LicenseText(entry, text)
        }
    }

    private companion object {
        const val TAG = "CassiniLicenses"
        const val STOP_TIMEOUT_MS = 5_000L
        const val KEY_OPEN = "open"
    }
}
