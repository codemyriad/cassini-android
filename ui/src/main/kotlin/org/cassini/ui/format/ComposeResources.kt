package org.cassini.ui.format

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources

/**
 * The resources the composable forms of the formatters read. [LocalResources] changes with the configuration, so a
 * language switch recomposes every caller.
 */
@Composable
@ReadOnlyComposable
internal fun resources(): Resources = LocalResources.current

/** The [FormatSettings] the composable forms read: [ProvideSystemFormat] in the app, [ProvideFixedFormat] in tests. */
@Composable
@ReadOnlyComposable
internal fun format(): FormatSettings = LocalFormat.current
