package org.cassini.android

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * A context whose resources use the app language (architecture 5.10): the text of the services, the notifications,
 * and every other text the app builds outside an Activity.
 *
 * Below API 33 this is `ContextCompat.getContextForLanguage`, which reads the locales that AppCompat stores. From
 * API 33 that function returns [this] unchanged, on the assumption that Android has put the app language into every
 * context of the process. The application context does not always have it: on a Pixel 8 (API 37) and on an API 34
 * emulator the screens were Italian and the processing notifications English. So from API 33 the locales come from `LocaleManager` (the
 * app language, or the phone's when the app has none), and the context gets a configuration with them when its own
 * configuration differs.
 */
fun Context.inAppLanguage(): Context {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return ContextCompat.getContextForLanguage(this)
    val configuration = resources.configuration
    val locales = appLocales(this)?.takeUnless { it == configuration.locales }
    return locales?.let { createConfigurationContext(Configuration(configuration).apply { setLocales(it) }) } ?: this
}

/** The app language, else the phone's languages; null when `LocaleManager` gives neither. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun appLocales(context: Context): LocaleList? {
    val manager = context.getSystemService(LocaleManager::class.java) ?: return null
    return manager.applicationLocales.takeUnless { it.isEmpty }
        ?: manager.systemLocales.takeUnless { it.isEmpty }
}
