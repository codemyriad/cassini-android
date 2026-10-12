package org.cassini.android.nav

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.cassini.ui.theme.CassiniTheme

/** The screen background alone, while a route waits for its first state. */
@Composable
@Suppress("FunctionNaming") // Composables are nouns in PascalCase.
fun ScreenBackground(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(CassiniTheme.colors.bg))
}

/** Starts [intent] (a system screen or the browser); a device without an activity for it only logs. */
fun Context.startSystemScreen(intent: Intent) {
    try {
        startActivity(intent)
    } catch (missing: ActivityNotFoundException) {
        Log.w("CassiniNav", "No activity for ${intent.action}", missing)
    }
}
