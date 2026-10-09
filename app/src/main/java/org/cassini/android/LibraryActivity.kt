package org.cassini.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
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
    private lateinit var live: TextView
    private lateinit var undo: LinearLayout
    private lateinit var undoText: TextView
    private var notes = emptyList<LibraryNote>()
    private var loading = true
    private var trashedId: String? = null
    private val worker = Executors.newSingleThreadExecutor()

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = NoteViews(this)
        store = LibraryStore(filesDir)
        val root = views.column().apply { setBackgroundColor(Palette.background) }
        val heading = views.row().apply { setPadding(views.dp(20), views.dp(16), views.dp(8), views.dp(8)) }
        views.add(heading, views.title(26f).apply { setText(R.string.notes) }, 0, weight = 1f)
        views.add(heading, views.iconButton(R.drawable.ic_trash, R.string.trash).apply {
            setOnClickListener { startActivity(Intent(this@LibraryActivity, TrashActivity::class.java)) }
        }, -2)
        views.add(heading, views.iconButton(R.drawable.ic_settings, R.string.settings).apply {
            id = R.id.settings_button
            setOnClickListener { startActivity(Intent(this@LibraryActivity, SettingsActivity::class.java)) }
        }, -2)
        views.add(root, heading)
        query = views.input(R.string.library_search).apply {
            id = R.id.library_search; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setCompoundDrawablesRelative(views.icon(R.drawable.ic_search, Palette.muted, 20), null, null, null)
            compoundDrawablePadding = views.dp(10)
            setText(savedInstanceState?.getString("query").orEmpty())
        }
        val searchArea = views.column().apply { setPadding(views.dp(16), views.dp(4), views.dp(16), 0) }
        views.add(searchArea, query)
        summary = views.text(13f, Palette.muted).apply {
            id = R.id.library_status; gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelative(views.icon(R.drawable.ic_shield, Palette.ok, 15), null, null, null)
            compoundDrawablePadding = views.dp(6); setPadding(views.dp(4), 0, 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        views.add(searchArea, summary, top = 10)
        views.add(root, searchArea)
        // A recording running in the background is one tap away.
        live = views.text(14f, Palette.surface, medium = true).apply {
            setText(R.string.recording_in_progress); gravity = Gravity.CENTER_VERTICAL
            background = views.shape(Palette.record, 16); setPadding(views.dp(16), views.dp(12), views.dp(16), views.dp(12))
            setCompoundDrawablesRelative(null, null, views.icon(R.drawable.ic_chevron_right, Palette.surface, 18), null)
            visibility = View.GONE
            setOnClickListener { startActivity(Intent(this@LibraryActivity, RecordingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        }
        views.clickable(live)
        val liveArea = views.column().apply { setPadding(views.dp(16), 0, views.dp(16), 0) }
        views.add(liveArea, live, top = 12)
        views.add(root, liveArea)
        cards = views.column().apply { id = R.id.library_cards; setPadding(views.dp(12), views.dp(4), views.dp(12), views.dp(16)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(cards) }
        views.add(root, scroll, height = 0, weight = 1f)
        undoText = views.text(14f, Palette.nightText).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        undo = views.row().apply {
            background = views.shape(Palette.night, 14); setPadding(views.dp(16), views.dp(2), views.dp(4), views.dp(2)); visibility = View.GONE
        }
        views.add(undo, undoText, 0, weight = 1f)
        views.add(undo, views.button(R.string.undo, NoteViews.Style.TEXT, height = 44).apply {
            setTextColor(Palette.accentTrack); setOnClickListener { undoTrash() }
        }, -2)
        val footer = views.column().apply { setPadding(views.dp(16), views.dp(8), views.dp(16), views.dp(20)) }
        views.add(footer, undo)
        val actions = views.row()
        open = views.button(R.string.import_audio, NoteViews.Style.OUTLINE, R.drawable.ic_import).apply {
            id = R.id.open_button
            setOnClickListener { startActivity(Intent(this@LibraryActivity, MainActivity::class.java).putExtra(MainActivity.REQUEST_IMPORT, true)) }
        }
        record = views.button(R.string.record_note, NoteViews.Style.RECORD, height = 60).apply {
            id = R.id.record_button; textSize = 17f
            setCompoundDrawablesRelative(views.shape(Palette.surface, 8).apply { setBounds(0, 0, views.dp(16), views.dp(16)) }, null, null, null)
            compoundDrawablePadding = views.dp(10)
            setOnClickListener { startActivity(Intent(this@LibraryActivity, RecordingActivity::class.java)) }
        }
        views.add(actions, open, 0, weight = 1f)
        views.space(actions, 12)
        views.add(actions, record, 0, weight = 1f)
        views.add(footer, actions, top = 8); views.add(root, footer)
        setContentView(root)
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderNotes() }
            override fun afterTextChanged(s: Editable?) {}
        })
        record.isEnabled = false; open.isEnabled = false
        summary.setText(R.string.library_loading)
        trashedId = savedInstanceState?.getString(TRASHED) ?: intent.getStringExtra(TRASHED)
        worker.execute {
            try {
                store.migrate(SessionStore(filesDir).load())
                RecoveryScanner.recover(filesDir, RecordingService.live, getString(R.string.recording_recovered))
                Trash(filesDir, store).purge(System.currentTimeMillis())
                val recovered = store.load()
                runOnUiThread { if (!isDestroyed) { notes = recovered; loading = false; refresh() } }
            } catch (error: Exception) {
                android.util.Log.e("Cassini", "Library load failed", error)
                runOnUiThread { if (!isDestroyed) summary.setText(R.string.library_error) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(TRASHED)?.let { trashedId = it }
    }

    override fun onResume() {
        super.onResume()
        live.visibility = if (RecordingService.live != null) View.VISIBLE else View.GONE
        if (!loading) {
            try { notes = store.load(); refresh() }
            catch (error: Exception) { android.util.Log.e("Cassini", "Library refresh failed", error); summary.setText(R.string.library_error) }
        }
    }

    private fun refresh() {
        record.isEnabled = true; open.isEnabled = true
        val model = ModelStore(File(filesDir, "parakeet-v3"))
        summary.setText(if (model.ready()) R.string.library_local else R.string.library_needs_model)
        val trashed = trashedId?.let { id -> notes.firstOrNull { it.id == id && it.trashedAt != null } }
        if (trashed == null) trashedId = null
        undo.visibility = if (trashed != null) View.VISIBLE else View.GONE
        if (trashed != null) undoText.text = getString(R.string.trash_moved, trashed.title)
        renderNotes()
    }

    private fun undoTrash() {
        val id = trashedId ?: return
        trashedId = null
        try { store.restore(id); notes = store.load() }
        catch (error: Exception) { android.util.Log.e("Cassini", "Could not restore note", error); summary.setText(R.string.library_error) }
        refresh()
    }

    private fun renderNotes() {
        cards.removeAllViews()
        val search = query.text.toString().trim()
        val matches = notes.filter { it.trashedAt == null && it.matches(search) }
        if (matches.isEmpty()) {
            views.add(cards, views.title(22f).apply { setText(if (search.isBlank()) R.string.library_empty else R.string.no_matches); setPadding(views.dp(8), 0, views.dp(8), 0) }, top = 40)
            views.add(cards, views.text(15f, Palette.muted).apply {
                setText(if (search.isBlank()) R.string.library_empty_hint else R.string.library_search_hint)
                setPadding(views.dp(8), 0, views.dp(8), 0); setLineSpacing(views.dp(3).toFloat(), 1f)
            }, top = 10)
            return
        }
        var group = ""
        matches.forEach { note ->
            val date = dayLabel(note.createdAt)
            if (date != group) {
                group = date
                views.add(cards, views.text(12f, Palette.muted, medium = true).apply {
                    text = date; isAllCaps = true; letterSpacing = .06f; setPadding(views.dp(8), 0, 0, 0)
                }, top = 18)
            }
            views.add(cards, card(note, search), top = 6)
        }
    }

    /** One tap target per note: title and length, what was said, when, and its state. */
    private fun card(note: LibraryNote, search: String): View {
        val card = views.column().apply {
            background = views.shape(Palette.surface, 16, Palette.line)
            setPadding(views.dp(14), views.dp(12), views.dp(14), views.dp(12))
            setOnClickListener {
                startActivity(Intent(this@LibraryActivity, MainActivity::class.java)
                    .putExtra(MainActivity.NOTE_ID, note.id).putExtra(MainActivity.SEARCH_QUERY, search))
            }
        }
        views.clickable(card)
        val titleRow = views.row().apply { gravity = Gravity.TOP }
        views.add(titleRow, views.text(16f, medium = true).apply { text = note.title; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, 0, weight = 1f)
        views.add(titleRow, views.text(13f, Palette.muted, mono = true).apply {
            text = clock(note.session.durationMs); setPadding(views.dp(12), views.dp(2), 0, 0)
        }, -2)
        views.add(card, titleRow)
        val snippet = note.snippet(search)
        if (snippet.isNotEmpty()) views.add(card, views.text(14f, Palette.textSoft).apply {
            text = snippet; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(views.dp(2).toFloat(), 1f)
        }, top = 6)
        val meta = views.row()
        views.add(meta, views.text(13f, Palette.muted).apply { text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(note.createdAt)) }, -2)
        views.space(meta, 8)
        views.add(meta, stateChip(note), -2)
        views.add(card, meta, top = 8)
        return card
    }

    private fun stateChip(note: LibraryNote): TextView = when {
        note.session.processingPaused -> views.chip(Palette.warnSoft, Palette.warn).apply { setText(R.string.state_paused) }
        note.session.document != null || note.session.transcript != null -> views.chip(Palette.okSoft, Palette.ok).apply { setText(R.string.state_transcribed) }
        else -> views.chip(Palette.surface, Palette.textSoft, Palette.lineStrong).apply { setText(R.string.state_audio) }
    }

    private fun dayLabel(time: Long): String {
        fun day(value: Long) = Calendar.getInstance().apply { timeInMillis = value }.let { it.get(Calendar.YEAR) to it.get(Calendar.DAY_OF_YEAR) }
        val today = Calendar.getInstance()
        if (day(time) == day(today.timeInMillis)) return getString(R.string.today)
        today.add(Calendar.DAY_OF_YEAR, -1)
        return if (day(time) == day(today.timeInMillis)) getString(R.string.yesterday) else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", query.text.toString()); outState.putString(TRASHED, trashedId)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    companion object { const val TRASHED = "trashedNote" }
}
