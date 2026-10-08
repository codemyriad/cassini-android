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
import android.text.format.DateUtils
import android.view.MenuItem
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

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
        companion object {
            /** Shared across fragment instances, so work started before a rotation still finishes and reaches the new screen. */
            internal val background = Executors.newSingleThreadExecutor()
            private val main = android.os.Handler(android.os.Looper.getMainLooper())
            private var resumed = java.lang.ref.WeakReference<SettingsFragment>(null)
        }
        private lateinit var people: PreferenceCategory
        private val app get() = activity.application as CassiniApplication

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

            people = category(R.string.people_title)

            category(R.string.settings_app).addPreference(Preference(context).apply {
                setTitle(R.string.about)
                summary = getString(R.string.app_version, context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                setOnPreferenceClickListener {
                    AlertDialog.Builder(context).setTitle(R.string.about).setMessage(R.string.about_body)
                        .setPositiveButton(R.string.done, null).show()
                    true
                }
            })
        }

        override fun onResume() { super.onResume(); resumed = java.lang.ref.WeakReference(this); buildPeople() }
        override fun onPause() { if (resumed.get() === this) resumed = java.lang.ref.WeakReference(null); super.onPause() }

        private var counts: Map<String, Int>? = null
        private var countRequest = 0

        /** Saved voices, rebuilt on each resume so names given in a note appear here. Note counts scan every note, so they follow. */
        private fun buildPeople() {
            showPeople()
            val request = ++countRequest
            background.execute {
                val found = try { app.noteVoices.noteCounts() } catch (error: Exception) { android.util.Log.w("Settings", "Could not count notes", error); return@execute }
                main.post { if (isAdded && !isDetached && request == countRequest) { counts = found; showPeople() } }
            }
        }

        private fun showPeople() {
            val context = activity ?: return
            people.removeAll()
            val voices = app.voices.load().sortedBy { it.name.lowercase() }
            val counts = counts
            if (voices.isEmpty()) people.addPreference(Preference(context).apply { setTitle(R.string.people_empty); isEnabled = false })
            voices.forEach { voice ->
                people.addPreference(Preference(context).apply {
                    isPersistent = false; title = voice.name
                    val speech = DateUtils.formatElapsedTime(voice.seconds.toLong())
                    summary = when {
                        voice.model != VoiceprintModel.model.sha256 -> getString(R.string.people_needs_reenrol)
                        counts == null -> getString(R.string.people_summary_counting, speech)
                        else -> resources.getQuantityString(R.plurals.people_summary, counts[voice.id] ?: 0, counts[voice.id] ?: 0, speech)
                    }
                    setOnPreferenceClickListener { editPerson(voice); true }
                })
            }
            if (voices.isNotEmpty()) people.addPreference(Preference(context).apply {
                isPersistent = false; setTitle(R.string.people_forget_all)
                setOnPreferenceClickListener {
                    confirm(getString(R.string.people_forget_all_confirm)) { it.voices.clear(); it.noteVoices.clear() }; true
                }
            })
            people.addPreference(Preference(context).apply { setSummary(R.string.people_privacy); isSelectable = false })
        }

        private fun editPerson(voice: Voice) {
            val context = activity
            val input = SpeakerDialog.nameInput(context, voice.name).apply { id = R.id.person_name_input }
            val note = TextView(context).apply { setText(R.string.people_rename_note) }
            AlertDialog.Builder(context).setTitle(voice.name).setView(SpeakerDialog.frame(context, SpeakerDialog.column(context, input, note)))
                .setPositiveButton(R.string.people_rename) { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty() && name != voice.name) { app.voices.rename(voice.id, name); buildPeople() }
                }
                .setNeutralButton(R.string.people_forget) { _, _ ->
                    confirm(getString(R.string.people_forget_confirm, voice.name)) { it.voices.forget(voice.id); it.noteVoices.forget(voice.id) }
                }
                .setNegativeButton(R.string.cancel, null).show()
        }

        /** Asks first, then runs [forget] off the main thread, since scrubbing notes scans every note file. */
        private fun confirm(message: String, forget: (CassiniApplication) -> Unit) {
            val app = app
            AlertDialog.Builder(activity).setMessage(message).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.people_forget) { _, _ ->
                    background.execute {
                        try { forget(app) } catch (error: Exception) { android.util.Log.w("Settings", "Could not forget voices", error) }
                        // A rotation may have replaced this fragment meanwhile: refresh whichever one is on screen.
                        main.post { resumed.get()?.takeIf { it.isAdded }?.buildPeople() }
                    }
                }.show()
        }

        private fun modelSummary(context: Context): String {
            val models = ModelStore(File(context.filesDir, "parakeet-v3"))
            return getString(R.string.settings_model_summary, models.precision,
                getString(R.string.model_size_int8),
                getString(if (models.ready()) R.string.model_installed else R.string.model_download_needed))
        }
    }
}
