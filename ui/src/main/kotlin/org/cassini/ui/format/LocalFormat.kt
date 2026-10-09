// The file is named after LocalFormat, as docs/design/ui.md section 1 lists it.
@file:Suppress("MatchingDeclarationName")

package org.cassini.ui.format

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.ZoneId
import java.util.Locale

/**
 * What the formatters in this package need besides the resources: the [locale] for number grouping and date
 * patterns, the user's 12/24-hour choice and the [zone] that turns instants into local times.
 */
@Immutable
data class FormatSettings(val locale: Locale, val is24Hour: Boolean, val zone: ZoneId)

/** Provided by `CassiniTheme` (system values) or by [ProvideFixedFormat] in tests and previews. */
val LocalFormat = staticCompositionLocalOf<FormatSettings> {
    error("No FormatSettings: wrap the content in CassiniTheme or ProvideFixedFormat")
}

/**
 * Provides the system's locale (from the configuration, so per-app languages apply), 12/24-hour setting and zone.
 * Neither a time-zone change nor the 12/24-hour switch is a configuration change: a receiver for
 * `ACTION_TIMEZONE_CHANGED` and `ACTION_TIME_CHANGED` (which Settings sends for the 12/24-hour switch) reads both
 * values again while the content is shown.
 */
@Composable
@Suppress("FunctionNaming") // Composables are nouns in PascalCase.
fun ProvideSystemFormat(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val locale = configuration.locales[0] ?: Locale.ROOT
    var timeSettings by remember(context) { mutableStateOf(readTimeSettings(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                timeSettings = readTimeSettings(context)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
        }
        // Both are system broadcasts, which a not-exported receiver still gets.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        // A change while no receiver was registered (the activity was recreated meanwhile) is read here.
        timeSettings = readTimeSettings(context)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val (is24Hour, zone) = timeSettings
    val settings = remember(locale, is24Hour, zone) { FormatSettings(locale, is24Hour, zone) }
    CompositionLocalProvider(LocalFormat provides settings, content = content)
}

private fun readTimeSettings(context: Context): Pair<Boolean, ZoneId> =
    DateFormat.is24HourFormat(context) to ZoneId.systemDefault()

/**
 * Pins all three format inputs, so a screenshot or preview does not depend on the machine. The resources still
 * follow the configuration: tests set the matching locale qualifier too (`+it`).
 */
@Composable
@Suppress("FunctionNaming") // Composables are nouns in PascalCase.
fun ProvideFixedFormat(
    locale: Locale,
    is24Hour: Boolean = true,
    zone: ZoneId = FIXED_ZONE,
    content: @Composable () -> Unit,
) {
    val settings = remember(locale, is24Hour, zone) { FormatSettings(locale, is24Hour, zone) }
    CompositionLocalProvider(LocalFormat provides settings, content = content)
}

/** The zone of the fixtures and previews: the boards show times in Italy. */
val FIXED_ZONE: ZoneId = ZoneId.of("Europe/Rome")
