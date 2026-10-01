package org.cassini.android

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object AppLanguage {
    private const val PREFERENCES = "interface"
    private const val KEY = "language"

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val tag = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (tag.isEmpty()) return context
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(tag)))
        })
    }

    fun selected(context: Context): String = if (Build.VERSION.SDK_INT >= 33)
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
    else context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun set(activity: Activity, tag: String) {
        require(tag in listOf("", "en", "it"))
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
            activity.recreate()
        }
    }
}
