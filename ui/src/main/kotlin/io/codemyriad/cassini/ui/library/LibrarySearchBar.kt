@file:Suppress("FunctionNaming", "MagicNumber") // Native M3 bar and the approved 24dp mark.

package io.codemyriad.cassini.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.rememberSyncedText
import io.codemyriad.cassini.ui.theme.CassiniIcon
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

/** Search stays in its native collapsed surface; results use the existing Library list, never an overlay. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList", "LongMethod") // Native input slot and its fixed leading/trailing controls.
internal fun LibrarySearchBar(
    query: String,
    hasResults: Boolean,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    onSearch: () -> Unit,
    onTrash: () -> Unit,
    onSettings: () -> Unit,
) {
    val c = CassiniTheme.colors
    val text = rememberSyncedText(query)
    var focused by remember { mutableStateOf(false) }
    val active = focused || text.value.text.isNotEmpty() || query.isNotEmpty() || hasResults
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val close = {
        text.set("")
        focus.clearFocus()
        keyboard?.hide()
        onClose()
    }
    BackHandler(enabled = active, onBack = close)
    val description = stringResource(R.string.ui_library_search_description)
    val colors = SearchBarDefaults.colors(containerColor = c.neutralTint)
    CompositionLocalProvider(LocalTextStyle provides CassiniTheme.type.body16) {
        SearchBar(
            state = rememberSearchBarState(),
            modifier = Modifier.fillMaxWidth().testTag("library-search-bar"),
            colors = colors,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            inputField = {
                SearchBarDefaults.InputField(
                    query = text.value.text,
                    onQueryChange = {
                        // M3 owns caret/composition; retain the existing delayed StateFlow acknowledgement policy.
                        text.edit(TextFieldValue(it, TextRange(it.length)), send = onQuery)
                    },
                    onSearch = { onSearch() },
                    expanded = active,
                    onExpandedChange = {}, // Actual input focus owns empty-search activation, including navigation focus loss.
                    modifier = Modifier.onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = description },
                    placeholder = {
                        Text(
                            stringResource(R.string.ui_library_search_placeholder),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        Crossfade(active, label = "Library search leading icon") { searching ->
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                if (searching) {
                                    SearchIcon(Glyph.Back, stringResource(R.string.ui_search_close), close)
                                } else {
                                    Image(
                                        painterResource(R.drawable.ui_saturn_nav),
                                        contentDescription = null,
                                        modifier = Modifier.size(37.dp, 24.dp).testTag("saturn-nav"),
                                    )
                                }
                            }
                        }
                    },
                    trailingIcon = {
                        Crossfade(active, label = "Library search trailing icons") { searching ->
                            Row(Modifier.width(96.dp)) {
                                if (searching) {
                                    Spacer(Modifier.size(48.dp))
                                    SearchIcon(Glyph.Close, stringResource(R.string.ui_search_clear)) {
                                        text.set("")
                                        onClear()
                                    }
                                } else {
                                    SearchIcon(Glyph.Trash, stringResource(R.string.ui_library_trash), onTrash)
                                    SearchIcon(Glyph.Settings, stringResource(R.string.ui_library_settings), onSettings)
                                }
                            }
                        }
                    },
                    colors = SearchBarDefaults.inputFieldColors(
                        focusedTextColor = c.ink,
                        unfocusedTextColor = c.ink,
                        cursorColor = c.accent,
                        focusedPlaceholderColor = c.text2,
                        unfocusedPlaceholderColor = c.text2,
                        focusedContainerColor = c.neutralTint,
                        unfocusedContainerColor = c.neutralTint,
                    ),
                )
            },
        )
    }
}

@Composable
private fun SearchIcon(glyph: Glyph, description: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(48.dp)) {
        CassiniIcon(glyph, 24.dp, 1.8f, CassiniTheme.colors.text2, description)
    }
}
