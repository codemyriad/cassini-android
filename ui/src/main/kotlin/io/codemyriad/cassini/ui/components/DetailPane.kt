package io.codemyriad.cassini.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * Where the detail pane sits in the window when the app shows the Library and a meeting side by side (an unfolded
 * foldable or a tablet, QA 25 Nov): its distance from the window's start and end edges. [CassiniSheet] rises from the
 * bottom of that pane at its width, and [CassiniDialog] centres its card over it, so the actions sheet, Name a speaker
 * and Document info stay beside the Library. Null on a single pane, where both use the whole window.
 */
val LocalDetailPane: ProvidableCompositionLocal<PaddingValues?> = compositionLocalOf { null }
