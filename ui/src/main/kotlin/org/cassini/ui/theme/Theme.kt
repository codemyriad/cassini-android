@file:Suppress("MagicNumber") // The fixed on-colours of the Material mapping in docs/design/ui.md section 4.2.

package org.cassini.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import org.cassini.ui.format.ProvideSystemFormat

/**
 * The Cassini theme: provides [CassiniColors] (light or dark), [CassiniType], [CassiniShapes], [RecordingColors],
 * the system format settings and a [MaterialTheme] mapped from the tokens (docs/design/ui.md section 4.2).
 * There is no dynamic colour.
 */
@Composable
@Suppress("FunctionNaming") // Composables are nouns in PascalCase.
fun CassiniTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(
        LocalCassiniColors provides colors,
        LocalCassiniType provides CassiniTypography,
        LocalCassiniShapes provides CassiniShapeSet,
        LocalRecordingColors provides CassiniRecordingColors,
    ) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = CassiniTypography.toMaterialTypography(),
            shapes = CassiniShapeSet.toMaterialShapes(),
        ) {
            ProvideSystemFormat(content)
        }
    }
}

/** Reads the tokens of the enclosing [CassiniTheme]. Screens use only these, never raw values. */
object CassiniTheme {
    val colors: CassiniColors
        @Composable @ReadOnlyComposable
        get() = LocalCassiniColors.current

    val type: CassiniType
        @Composable @ReadOnlyComposable
        get() = LocalCassiniType.current

    val shapes: CassiniShapes
        @Composable @ReadOnlyComposable
        get() = LocalCassiniShapes.current

    val recording: RecordingColors
        @Composable @ReadOnlyComposable
        get() = LocalRecordingColors.current

    val spacing: CassiniSpacing
        get() = CassiniSpacing
}

private val White = Color(0xFFFFFFFF)

/**
 * The Material roles of docs/design/ui.md section 4.2, the same mapping for both sets. In light, the on-colours of
 * teal, amber and redInk are white as written there. In dark those fills are light colours, so their on-colour is
 * [CassiniColors.bg]: white on them would fail contrast. The inverse roles follow the snackbar tokens, which give
 * the values of ui.md (#9DB4FF, #14171A on #F2F3F5 in light; #ECEEF1 and #14171A in dark).
 */
internal fun CassiniColors.toColorScheme(): ColorScheme {
    val onStrong = if (isDark) bg else White
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accentFill,
        onPrimary = onAccentFill,
        primaryContainer = accentTint,
        onPrimaryContainer = accentInk,
        inversePrimary = snackbarAction,
        secondary = teal,
        onSecondary = onStrong,
        secondaryContainer = tealTint,
        onSecondaryContainer = tealInk,
        tertiary = amber,
        onTertiary = onStrong,
        tertiaryContainer = amberTint,
        onTertiaryContainer = amberInk,
        error = redInk,
        onError = onStrong,
        errorContainer = redTint,
        onErrorContainer = redInk,
        background = bg,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = bg,
        onSurfaceVariant = text2,
        surfaceTint = Color.Transparent,
        surfaceBright = surface,
        surfaceDim = boardBg,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surface,
        inverseSurface = snackbarBg,
        inverseOnSurface = snackbarText,
        outline = line2,
        outlineVariant = line,
        scrim = scrim.copy(alpha = 1f),
    )
}
