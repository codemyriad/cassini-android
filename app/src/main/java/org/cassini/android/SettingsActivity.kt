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
import android.preference.SwitchPreference
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
            val sessions = SessionStore(context.filesDir)
            val model = ListPreference(context).apply {
                key = "transcription_model"; isPersistent = false
                setTitle(R.string.recognition_model); setDialogTitle(R.string.recognition_model)
                entries = arrayOf(getString(R.string.automatic_model), getString(R.string.int8_option), getString(R.string.fp32_option))
                entryValues = arrayOf("auto", "int8", "fp32")
                value = sessions.load().modelChoice
                summary = modelSummary(context, value)
                setNegativeButtonText(R.string.cancel)
                setOnPreferenceChangeListener { preference, chosen ->
                    val choice = chosen as String
                    val fp32 = ModelPolicy.resolve(context, choice)
                    sessions.save(sessions.load().copy(fp32 = fp32, modelChoice = choice))
                    preference.summary = modelSummary(context, choice)
                    true
                }
            }
            transcription.addPreference(model)
            transcription.addPreference(SwitchPreference(context).apply {
                key = "live_recording"; isPersistent = false
                setTitle(R.string.live_recording); setSummary(R.string.live_recording_summary)
                isChecked = RecordingPreferences.live(context)
                setOnPreferenceChangeListener { _, enabled ->
                    RecordingPreferences.setLive(context, enabled as Boolean)
                    true
                }
            })
            transcription.addPreference(Preference(context).apply {
                setTitle(R.string.automatic_model); setSummary(R.string.automatic_model_explanation)
                isSelectable = false
            })
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

        private fun modelSummary(context: Context, choice: String): String {
            val fp32 = ModelPolicy.resolve(context, choice)
            val models = ModelStore(File(context.filesDir, if (fp32) "parakeet-v3-fp32" else "parakeet-v3"), fp32)
            return getString(R.string.settings_model_summary, if (choice == "auto") getString(R.string.automatic_precision, models.precision) else models.precision,
                getString(if (fp32) R.string.model_size_fp32 else R.string.model_size_int8),
                getString(if (models.ready()) R.string.model_installed else R.string.model_download_needed))
        }
    }
}
