package org.cassini.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** The note screen: a header, the note's state as chips, running work, the transcript, and playback pinned below. */
class DeckViews(private val context: Context) {
    private val views = NoteViews(context)
    private fun dp(value: Int) = views.dp(value)
    private fun LinearLayout.add(view: View, width: Int = LinearLayout.LayoutParams.MATCH_PARENT, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
                                 weight: Float = 0f, marginTop: Int = 0) {
        addView(view, LinearLayout.LayoutParams(width, height, weight).apply { topMargin = dp(marginTop) })
    }

    val root = views.column().apply {
        setBackgroundColor(Palette.background)
        // Opening a note should start with the page focused, rather than its search keyboard.
        isFocusableInTouchMode = true
    }
    val library = views.iconButton(R.drawable.ic_back, R.string.back_to_notes).apply { id = R.id.notes_button }
    val menu = views.iconButton(R.drawable.ic_more, R.string.more_actions).apply { id = R.id.more_button }
    val filename = views.text(18f, medium = true).apply { id = R.id.recording_name; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    val caption = views.text(13f, Palette.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    val trust = views.chip(Palette.okSoft, Palette.ok).apply { id = R.id.document_trust }
    val version = views.chip(Palette.surface, Palette.text, Palette.lineStrong).apply {
        setCompoundDrawablesRelative(null, null, views.icon(R.drawable.ic_chevron_down, Palette.text, 14), null)
        compoundDrawablePadding = dp(4); isClickable = true; isFocusable = true; visibility = View.GONE
    }
    val transcribe = views.button(R.string.transcribe, NoteViews.Style.PRIMARY).apply { id = R.id.transcribe_button }
    val cancelOperation = views.button(R.string.cancel, NoteViews.Style.OUTLINE, height = 44).apply { id = R.id.cancel_operation; visibility = View.GONE }
    val status = views.text(14f, Palette.muted).apply {
        id = R.id.operation_status; setPadding(0, dp(8), 0, dp(8)); setLineSpacing(dp(3).toFloat(), 1f)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        id = R.id.operation_progress; max = 100; visibility = View.GONE; contentDescription = context.getString(R.string.busy_description)
        progressTintList = android.content.res.ColorStateList.valueOf(Palette.accent)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Palette.line)
        indeterminateTintList = android.content.res.ColorStateList.valueOf(Palette.accent)
    }
    val details = views.text(12f, Palette.muted, mono = true).apply { id = R.id.transcript_details }
    val search = views.input(R.string.search_hint).apply {
        id = R.id.search_input; textSize = 15f; minimumHeight = dp(48)
        setCompoundDrawablesRelative(views.icon(R.drawable.ic_search, Palette.muted, 18), null, null, null)
        compoundDrawablePadding = dp(8); setPadding(dp(14), dp(8), dp(14), dp(8))
        background = views.shape(Palette.surface, 24, Palette.line)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
    }
    val clearSearch = views.iconButton(R.drawable.ic_close, R.string.clear_search).apply { id = R.id.clear_search_button }
    val matches = views.text(13f, Palette.muted).apply { id = R.id.search_matches }
    val draft = views.chip(Palette.surface, Palette.muted, Palette.lineStrong).apply {
        id = R.id.transcript_draft; setText(R.string.transcript_draft); visibility = View.GONE
        background = views.shape(Palette.surface, 10, Palette.muted, dashed = true)
    }
    val voice = views.text(13f, Palette.accentDeep, medium = true).apply { setText(R.string.unidentified_voice) }
    val transcript = views.text(17f).apply {
        id = R.id.transcript_text; setLineSpacing(dp(6).toFloat(), 1.05f)
        setPadding(0, dp(8), 0, dp(24)); highlightColor = Palette.accentSoft
    }
    val emptyTitle = views.title(22f).apply { gravity = Gravity.CENTER }
    val emptyHint = views.text(15f, Palette.muted).apply { gravity = Gravity.CENTER; setLineSpacing(dp(3).toFloat(), 1f) }
    val empty = views.column().apply { setPadding(dp(12), dp(32), dp(12), dp(32)); gravity = Gravity.CENTER }
    val searchRow = views.row()
    val scroll = ScrollView(context).apply { id = R.id.content_scroll; isFillViewport = true; isVerticalScrollBarEnabled = false }
    val position = views.text(12f, Palette.muted, mono = true).apply { id = R.id.playback_position }
    val seek = SeekBar(context).apply {
        id = R.id.playback_seek; contentDescription = context.getString(R.string.seek_description)
        minimumHeight = dp(40); setPadding(dp(8), 0, dp(8), 0)
        progressTintList = android.content.res.ColorStateList.valueOf(Palette.accent)
        thumbTintList = android.content.res.ColorStateList.valueOf(Palette.accent)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Palette.lineStrong)
    }
    val play = views.button(R.string.play, NoteViews.Style.DARK, R.drawable.ic_play, height = 56).apply { id = R.id.play_button }
    val back = views.iconButton(R.drawable.ic_replay, R.string.back_ten).apply { id = R.id.back_button }
    val forward = views.iconButton(R.drawable.ic_forward, R.string.forward_ten).apply { id = R.id.forward_button }

    /** Shows Play or Pause with its icon; the label is what tests and screen readers read. */
    fun showPlaying(playing: Boolean) {
        val label = context.getString(if (playing) R.string.pause else R.string.play)
        if (play.text.toString() != label) {
            play.text = label
            play.setCompoundDrawablesRelative(views.icon(if (playing) R.drawable.ic_pause else R.drawable.ic_play, Palette.surface, 20), null, null, null)
        }
    }

    fun showTrust(state: String, label: Int, description: Int) {
        val good = state == "ok"
        trust.setText(label); trust.contentDescription = context.getString(description)
        trust.setTextColor(if (good) Palette.ok else Palette.warn)
        trust.background = views.shape(if (good) Palette.okSoft else Palette.warnSoft, 12)
        trust.setCompoundDrawablesRelative(if (good) views.icon(R.drawable.ic_check, Palette.ok, 14) else null, null, null, null)
        trust.compoundDrawablePadding = dp(4)
    }

    private val operation = views.column().apply {
        setPadding(dp(16), dp(10), dp(16), dp(10)); visibility = View.GONE
        background = views.shape(Palette.surface, 0, Palette.line)
    }
    /** Where status and progress sit when nothing is running: under the Transcribe action. */
    private val home = views.column()

    /** Running work remains visible while the transcript scrolls. Idle status returns to its place under the actions. */
    fun pinOperation(pinned: Boolean) {
        val destination = if (pinned) operation else home
        if (status.parent === destination) return
        listOf(status, progress, cancelOperation).forEach { (it.parent as ViewGroup).removeView(it) }
        destination.add(status)
        destination.add(progress, height = dp(4))
        destination.add(cancelOperation, LinearLayout.LayoutParams.WRAP_CONTENT, marginTop = 8)
        operation.visibility = if (pinned) View.VISIBLE else View.GONE
    }

    init {
        val heading = views.row().apply { setPadding(dp(4), dp(8), dp(4), dp(4)) }
        heading.add(library, LinearLayout.LayoutParams.WRAP_CONTENT)
        val names = views.column().apply { setPadding(dp(4), 0, dp(4), 0) }
        names.add(filename)
        names.add(caption)
        heading.add(names, 0, weight = 1f)
        heading.add(menu, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.add(heading)
        root.add(operation)

        val content = views.column().apply { setPadding(dp(16), dp(4), dp(16), dp(12)) }
        val chips = views.row()
        chips.add(trust, LinearLayout.LayoutParams.WRAP_CONTENT)
        views.space(chips, 8)
        chips.add(version, LinearLayout.LayoutParams.WRAP_CONTENT)
        content.add(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        content.add(transcribe, marginTop = 12)
        home.add(status)
        home.add(progress, height = dp(4))
        home.add(cancelOperation, LinearLayout.LayoutParams.WRAP_CONTENT, marginTop = 8)
        content.add(home)

        val page = views.card(20).apply { setPadding(dp(16), dp(12), dp(16), dp(4)) }
        val badges = views.row()
        badges.add(draft, LinearLayout.LayoutParams.WRAP_CONTENT)
        views.space(badges, 8)
        badges.add(details, 0, weight = 1f)
        page.add(badges)
        searchRow.add(search, 0, weight = 1f)
        views.space(searchRow, 4)
        searchRow.add(clearSearch, LinearLayout.LayoutParams.WRAP_CONTENT)
        page.add(searchRow, marginTop = 10)
        page.add(matches, marginTop = 4)
        page.add(voice, marginTop = 14)
        page.add(transcript)
        empty.add(OrbitView(context), dp(96), dp(96))
        empty.add(emptyTitle, marginTop = 16)
        empty.add(emptyHint, marginTop = 8)
        page.add(empty)
        content.add(page, marginTop = 8)
        scroll.addView(content)
        root.add(scroll, height = 0, weight = 1f)

        val playback = views.column().apply {
            background = views.shape(Palette.surface, 0, Palette.line); setPadding(dp(12), dp(8), dp(12), dp(14))
        }
        playback.add(seek, height = dp(40))
        val controls = views.row().apply { gravity = Gravity.CENTER_VERTICAL }
        controls.add(position.apply { setPadding(dp(8), 0, 0, 0) }, 0, weight = 1f)
        controls.add(back, LinearLayout.LayoutParams.WRAP_CONTENT)
        views.space(controls, 8)
        controls.add(play, dp(128))
        views.space(controls, 8)
        controls.add(forward, LinearLayout.LayoutParams.WRAP_CONTENT)
        controls.add(Space(context), 0, dp(1), 1f)
        playback.add(controls)
        root.add(playback)
    }

    /** Cassini's mark: a planet and its ring, drawn in the accent colour. */
    private class OrbitView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.accent }
        override fun onDraw(canvas: Canvas) {
            val scale = width / 28f
            canvas.save(); canvas.scale(scale, height / 28f)
            paint.strokeWidth = 1.2f
            canvas.drawCircle(14f, 14f, 5.5f, paint)
            canvas.rotate(-20f, 14f, 14f)
            canvas.drawOval(2f, 9.8f, 26f, 18.2f, paint)
            canvas.restore()
        }
    }
}
