package org.cassini.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.concurrent.Executors

/** Notes moved to the trash: restore one, delete one now, or empty it. Everything else waits [Trash.KEEP_MS]. */
class TrashActivity : Activity() {
    private lateinit var views: NoteViews
    private lateinit var store: LibraryStore
    private lateinit var list: LinearLayout
    private lateinit var empty: Button
    private val worker = Executors.newSingleThreadExecutor()

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = NoteViews(this)
        store = LibraryStore(filesDir)
        val root = views.column().apply { setBackgroundColor(Palette.background) }
        val heading = views.row().apply { setPadding(views.dp(8), views.dp(12), views.dp(8), views.dp(4)) }
        views.add(heading, views.iconButton(R.drawable.ic_back, R.string.back_to_notes).apply { setOnClickListener { finish() } }, -2)
        views.add(heading, views.title(22f).apply { setText(R.string.trash); setPadding(views.dp(4), 0, 0, 0) }, 0, weight = 1f)
        empty = views.button(R.string.trash_empty, NoteViews.Style.DANGER, height = 44).apply { setOnClickListener { confirmEmpty() } }
        views.add(heading, empty, -2)
        views.add(root, heading)
        views.add(root, views.text(14f, Palette.textSoft).apply {
            setText(R.string.trash_explained); setLineSpacing(views.dp(3).toFloat(), 1f)
            setPadding(views.dp(20), views.dp(4), views.dp(20), views.dp(8))
        })
        list = views.column().apply { setPadding(views.dp(16), 0, views.dp(16), views.dp(24)) }
        views.add(root, ScrollView(this).apply { isFillViewport = true; addView(list) }, height = 0, weight = 1f)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        list.removeAllViews()
        val now = System.currentTimeMillis()
        val trashed = try { store.load().filter { it.trashedAt != null }.sortedByDescending { it.trashedAt } }
            catch (error: Exception) { android.util.Log.e("Cassini", "Could not read the trash", error); emptyList() }
        empty.visibility = if (trashed.isEmpty()) View.GONE else View.VISIBLE
        if (trashed.isEmpty()) {
            views.add(list, views.text(16f, Palette.muted).apply { setText(R.string.trash_none); gravity = Gravity.CENTER }, top = 48)
            return
        }
        trashed.forEach { note ->
            val card = views.card(16).apply { setPadding(views.dp(14), views.dp(12), views.dp(14), views.dp(6)) }
            val titleRow = views.row().apply { gravity = Gravity.TOP }
            views.add(titleRow, views.text(16f, medium = true).apply { text = note.title; maxLines = 2 }, 0, weight = 1f)
            views.add(titleRow, views.text(13f, Palette.muted, mono = true).apply { text = clock(note.session.durationMs); setPadding(views.dp(12), views.dp(2), 0, 0) }, -2)
            views.add(card, titleRow)
            val days = Trash.daysLeft(note, now)
            views.add(card, views.text(13f, Palette.muted).apply { text = resources.getQuantityString(R.plurals.trash_days_left, days, days) }, top = 2)
            val actions = views.row()
            views.add(actions, views.button(R.string.trash_restore, NoteViews.Style.OUTLINE, R.drawable.ic_restore, height = 44).apply {
                setOnClickListener { work { store.restore(note.id) } }
            }, -2)
            views.add(actions, views.button(R.string.trash_delete_now, NoteViews.Style.DANGER, height = 44).apply {
                setOnClickListener { confirm(getString(R.string.trash_delete_confirm, note.title)) { Trash(filesDir, store).delete(note.id) } }
            }, -2)
            views.add(card, actions, top = 8)
            views.add(list, card, top = 8)
        }
    }

    private fun confirmEmpty() = confirm(getString(R.string.trash_empty_confirm)) { Trash(filesDir, store).empty() }

    /** Deleting for good cannot be undone, so it always asks. */
    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setMessage(message).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.trash_delete_now) { _, _ -> work(action) }.show()
    }

    private fun work(action: () -> Unit) {
        worker.execute {
            try { action() } catch (error: Exception) { android.util.Log.e("Cassini", "Trash operation failed", error) }
            runOnUiThread { if (!isDestroyed) render() }
        }
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
