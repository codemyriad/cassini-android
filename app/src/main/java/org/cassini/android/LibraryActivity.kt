package org.cassini.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors

class LibraryActivity : Activity() {
    private lateinit var views: NoteViews
    private lateinit var store: LibraryStore
    private lateinit var cards: LinearLayout
    private lateinit var query: EditText
    private lateinit var summary: TextView
    private lateinit var record: Button
    private lateinit var open: Button
    private var notes = emptyList<LibraryNote>()
    private var loading = true
    private val worker = Executors.newSingleThreadExecutor()

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = NoteViews(this)
        store = LibraryStore(filesDir)
        val root = views.column().apply { setBackgroundColor(DeckViews.ink) }
        val heading = views.row().apply { setPadding(views.dp(20), views.dp(20), views.dp(20), views.dp(18)) }
        views.add(heading, views.text(36f).apply { setText(R.string.notes); typeface = android.graphics.Typeface.DEFAULT_BOLD }, 0, weight = 1f)
        views.add(heading, views.button(R.string.settings).apply {
            id = R.id.settings_button
            setOnClickListener { startActivity(Intent(this@LibraryActivity, SettingsActivity::class.java)) }
        }, -2)
        views.add(root, heading)
        query = views.input(R.string.library_search).apply {
            id = R.id.library_search; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setText(savedInstanceState?.getString("query").orEmpty())
        }
        val searchArea = views.column().apply { setPadding(views.dp(20), 0, views.dp(20), views.dp(12)) }
        views.add(searchArea, query); views.add(root, searchArea)
        cards = views.column().apply { id = R.id.library_cards; setPadding(views.dp(20), 0, views.dp(20), views.dp(20)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(cards) }
        views.add(root, scroll, height = 0, weight = 1f)
        val footer = views.column().apply { setPadding(views.dp(20), views.dp(12), views.dp(20), views.dp(20)) }
        summary = views.text(12f, DeckViews.muted).apply { id = R.id.library_status; gravity = android.view.Gravity.CENTER; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        views.add(footer, summary)
        val actions = views.row()
        open = views.button(R.string.import_audio).apply {
            id = R.id.open_button
            setOnClickListener { startActivity(Intent(this@LibraryActivity, MainActivity::class.java).putExtra(MainActivity.REQUEST_IMPORT, true)) }
        }
        record = views.button(R.string.record_note, views.record, DeckViews.ink).apply {
            id = R.id.record_button
            setOnClickListener { startActivity(Intent(this@LibraryActivity, RecordingActivity::class.java)) }
        }
        views.add(actions, open, 0, weight = 1f)
        views.add(actions, Space(this), views.dp(12), 1)
        views.add(actions, record, 0, weight = 1.2f)
        views.add(footer, actions, top = 16); views.add(root, footer)
        setContentView(root)
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderNotes() }
            override fun afterTextChanged(s: Editable?) {}
        })
        record.isEnabled = false; open.isEnabled = false
        summary.setText(R.string.library_loading)
        worker.execute {
            try {
                store.migrate(SessionStore(filesDir).load())
                val recovered = store.load()
                runOnUiThread { if (!isDestroyed) { notes = recovered; loading = false; refresh() } }
            } catch (error: Exception) {
                android.util.Log.e("Cassini", "Library load failed", error)
                runOnUiThread { if (!isDestroyed) summary.setText(R.string.library_error) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!loading) {
            try { notes = store.load(); refresh() }
            catch (error: Exception) { android.util.Log.e("Cassini", "Library refresh failed", error); summary.setText(R.string.library_error) }
        }
    }

    private fun refresh() {
        record.isEnabled = true; open.isEnabled = true
        val model = ModelStore(File(filesDir, "parakeet-v3"))
        summary.setText(if (model.ready()) R.string.library_local else R.string.library_needs_model)
        renderNotes()
    }

    private fun renderNotes() {
        cards.removeAllViews()
        val search = query.text.toString().trim()
        val matches = notes.filter { it.matches(search) }
        if (matches.isEmpty()) {
            views.add(cards, views.text(25f).apply { setText(if (search.isBlank()) R.string.library_empty else R.string.no_matches) }, top = 40)
            views.add(cards, views.text(16f, DeckViews.muted).apply { setText(if (search.isBlank()) R.string.library_empty_hint else R.string.library_search_hint) }, top = 14)
            return
        }
        var group = ""
        matches.forEach { note ->
            val date = dayLabel(note.createdAt)
            if (date != group) {
                group = date
                views.add(cards, views.text(12f, DeckViews.muted).apply { text = date; letterSpacing = .08f }, top = 18)
            }
            val card = views.column().apply {
                background = views.shape(DeckViews.surface)
                setPadding(views.dp(16), views.dp(16), views.dp(16), views.dp(16))
                isClickable = true; isFocusable = true
                setOnClickListener {
                    startActivity(Intent(this@LibraryActivity, MainActivity::class.java)
                        .putExtra(MainActivity.NOTE_ID, note.id).putExtra(MainActivity.SEARCH_QUERY, search))
                }
            }
            val titleRow = views.row()
            views.add(titleRow, views.text(20f).apply { text = note.session.name; maxLines = 2; typeface = android.graphics.Typeface.DEFAULT_BOLD }, 0, weight = 1f)
            views.add(titleRow, views.text(12f, DeckViews.muted, true).apply {
                text = clock(note.session.durationMs)
                setPadding(views.dp(12), 0, 0, 0)
            }, -2)
            views.add(card, titleRow)
            val snippet = note.snippet(search)
            views.add(card, views.text(15f, if (snippet.isEmpty()) views.accent else DeckViews.paper).apply {
                text = snippet.ifEmpty { getString(if (note.session.document != null) R.string.library_document else R.string.library_audio_ready) }
                maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(views.dp(3).toFloat(), 1f)
            }, top = 10)
            views.add(card, views.text(12f, DeckViews.muted).apply {
                text = getString(R.string.library_note_metadata, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(note.createdAt)),
                    getString(if (note.session.document != null) R.string.library_saved_document else R.string.library_recorded))
            }, top = 12)
            views.add(cards, card, top = 10)
        }
    }

    private fun dayLabel(time: Long): String {
        fun day(value: Long) = Calendar.getInstance().apply { timeInMillis = value }.let { it.get(Calendar.YEAR) to it.get(Calendar.DAY_OF_YEAR) }
        val today = Calendar.getInstance()
        if (day(time) == day(today.timeInMillis)) return getString(R.string.today)
        today.add(Calendar.DAY_OF_YEAR, -1)
        return if (day(time) == day(today.timeInMillis)) getString(R.string.yesterday) else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putString("query", query.text.toString()); super.onSaveInstanceState(outState) }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
