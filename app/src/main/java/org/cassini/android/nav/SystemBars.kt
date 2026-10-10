package org.cassini.android.nav

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.navigation3.runtime.NavKey

/**
 * Sets the icon colour of the status and navigation bars (docs/design/ui.md section 9): [darkIcons] on light screens
 * in the light theme, light icons on Recording always. The app is edge to edge, so the bars have no colour of their
 * own; sheets do not change them.
 */
@Composable
@Suppress("FunctionNaming") // Composables are nouns in PascalCase.
fun SystemBarsAppearance(darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.activity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }
}

/**
 * Whether the screen of [key] wants dark bar icons in a [darkTheme] or light theme (docs/design/ui.md section 9).
 * Recording is a dark screen in both themes, so its icons are always light. Every other destination (Library, Note,
 * Model download, Settings, Saved voices, Trash, Licenses) is a light screen in the light theme and a dark one in the
 * dark theme. A sheet or a dialog keeps the bars of the screen under it.
 */
internal fun darkBarIcons(key: NavKey?, darkTheme: Boolean): Boolean = when (key) {
    RecordingKey -> false
    else -> !darkTheme
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
