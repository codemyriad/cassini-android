@file:Suppress("DEPRECATION") // Platform preferences keep this native prototype dependency-light.

package org.cassini.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import android.view.MenuItem
import java.io.File

class SettingsActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.settings)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            fragmentManager.beginTransaction().replace(android.R.id.content, SettingsFragment()).commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragment() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val context = activity
            val screen = preferenceManager.createPreferenceScreen(context)
            preferenceScreen = screen
            fun category(title: Int) = PreferenceCategory(context).apply {
                setTitle(title); screen.addPreference(this)
            }
            val general = category(R.string.settings_general)
            val language = ListPreference(context).apply {
                key = "interface_language"; isPersistent = false
                setTitle(R.string.interface_language); setDialogTitle(R.string.interface_language)
                entries = arrayOf(getString(R.string.system_language), getString(R.string.english_name), getString(R.string.italian_name))
                entryValues = arrayOf("", "en", "it")
                value = AppLanguage.selected(context).takeIf { it in entryValues } ?: ""
                summary = entry
                setNegativeButtonText(R.string.cancel)
                setOnPreferenceChangeListener { _, chosen ->
                    AppLanguage.set(context, chosen as String)
                    true
                }
            }
            general.addPreference(language)

            val transcription = category(R.string.settings_transcription)
            transcription.addPreference(Preference(context).apply {
                setTitle(R.string.recognition_model); summary = modelSummary(context)
                isSelectable = false
            })
            val bundle = ModelBundle.inFiles(context.filesDir)
            listOf(Triple(R.string.speaker_model, R.string.speaker_model_summary, bundle.speakers.ready()),
                Triple(R.string.voiceprint_model, R.string.voiceprint_model_summary, bundle.voiceprint.ready())).forEach { (title, detail, ready) ->
                transcription.addPreference(Preference(context).apply {
                    setTitle(title); summary = getString(if (ready) R.string.model_ready else R.string.model_missing, getString(detail))
                    isSelectable = false
                })
            }
            transcription.addPreference(Preference(context).apply {
                setTitle(R.string.processing_location); setSummary(R.string.processing_local_summary)
                isSelectable = false
            })

            val app = category(R.string.settings_app)
            app.addPreference(Preference(context).apply {
                setTitle(R.string.about)
                summary = getString(R.string.app_version, context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                setOnPreferenceClickListener {
                    AlertDialog.Builder(context).setTitle(R.string.about).setMessage(R.string.about_body)
                        .setPositiveButton(R.string.done, null).show()
                    true
                }
            })
        }

        private fun modelSummary(context: Context): String {
            val models = ModelStore(File(context.filesDir, "parakeet-v3"))
            return getString(R.string.settings_model_summary, models.precision,
                getString(R.string.model_size_int8),
                getString(if (models.ready()) R.string.model_installed else R.string.model_download_needed))
        }
    }
}
