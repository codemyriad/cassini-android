// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package org.cassini.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.cassini.ui.theme.CassiniTheme

/**
 * A placeholder for a screen that P8 has not drawn yet: an empty bg surface tagged with [name]. Each XxxScreen
 * calls it until its own P8 step replaces the body; then this file goes.
 */
@Composable
internal fun ScreenStub(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(CassiniTheme.colors.bg)
            .testTag(name),
    )
}
